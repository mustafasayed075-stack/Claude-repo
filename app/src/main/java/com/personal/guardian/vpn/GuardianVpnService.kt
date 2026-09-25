package com.personal.guardian.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.personal.guardian.blocklist.BlocklistManager
import com.personal.guardian.util.GuardianLog
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stage 2 — local DNS-filtering VPN.
 *
 * This is a **local** VpnService: it establishes a loopback tunnel on the device
 * and inspects DNS queries only. No browsing data ever leaves the device through
 * this service — allowed queries are forwarded to a normal public resolver exactly
 * as they would be without the app; blocked queries are answered locally with
 * NXDOMAIN and never sent anywhere.
 *
 * Design (chosen for stability + simplicity, per the spec):
 *  - The tunnel advertises a single sentinel DNS server ([SENTINEL_DNS]) and routes
 *    only that /32 into the tunnel. All non-DNS traffic bypasses the VPN entirely,
 *    so ordinary connectivity is untouched and there is no risk of over-blocking
 *    non-DNS flows.
 *  - Every packet read from the tunnel is therefore a DNS query. We parse the
 *    queried name, check it against [BlocklistManager]; matches get a synthesized
 *    NXDOMAIN, everything else is handed to [DnsForwarder], which resolves it
 *    against [UPSTREAM_DNS] concurrently over protected sockets and writes the
 *    reply back into the tunnel. The tunnel loop itself never waits on the network.
 */
class GuardianVpnService : VpnService() {

    private val running = AtomicBoolean(false)

    @Volatile private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel("stop requested")
            stopSelf()
            return START_NOT_STICKY
        }
        if (running.compareAndSet(false, true)) {
            startTunnel()
        }
        // Sticky so the OS revives DNS filtering if the process is reclaimed.
        return START_STICKY
    }

    private fun startTunnel() {
        BlocklistManager.ensureLoaded(applicationContext)
        try {
            val builder = Builder()
                .setSession("Guardian DNS Filter")
                .addAddress(TUN_LOCAL_ADDR, 32)
                .addDnsServer(SENTINEL_DNS)
                // Route ONLY DNS-to-sentinel into the tunnel; everything else bypasses.
                .addRoute(SENTINEL_DNS, 32)
                .setBlocking(true)
                .setMtu(MTU)

            // Never route our own traffic through the tunnel (avoids loops).
            runCatching { builder.addDisallowedApplication(packageName) }

            val pfd = builder.establish()
            if (pfd == null) {
                GuardianLog.e(applicationContext, "VPN establish() returned null; not permitted?")
                running.set(false)
                stopSelf()
                return
            }
            tunnel = pfd
            GuardianLog.i(applicationContext, "DNS filter tunnel established (${BlocklistManager.size} domains loaded).")

            worker = Thread({ runLoop(pfd) }, "guardian-dns-loop").apply {
                isDaemon = true
                start()
            }
        } catch (t: Throwable) {
            GuardianLog.e(applicationContext, "Failed to start DNS filter tunnel.", t)
            running.set(false)
            stopSelf()
        }
    }

    private fun runLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val packet = ByteArray(MTU)

        // Allowed queries are forwarded concurrently off this thread, so this loop
        // only ever does cheap work (parse + O(1) set lookup) and never waits on
        // the network. Each lookup gets its own protected upstream socket.
        val forwarder = DnsForwarder(
            upstream = InetSocketAddress(UPSTREAM_DNS, DnsPacket.DNS_PORT),
            timeoutMs = UPSTREAM_TIMEOUT_MS,
            socketFactory = {
                DatagramSocket().also {
                    if (!protect(it)) {
                        it.close()
                        throw IOException("VpnService.protect() failed for upstream DNS socket")
                    }
                }
            },
            onFailure = { query, t ->
                // Fail closed for this one query (no reply written); the client
                // resolver will retry. Warn-level to avoid log spam on flaky networks.
                GuardianLog.w(applicationContext, "Upstream DNS forward failed for ${query.qName}.", t)
            }
        )
        // Replies are written from the forwarder's threads as well as this one;
        // serialize writes so each packet reaches the tunnel intact.
        val writeToTunnel: (ByteArray) -> Unit = { bytes ->
            synchronized(output) { output.write(bytes) }
        }

        try {
            while (running.get()) {
                val n = try {
                    input.read(packet)
                } catch (t: Throwable) {
                    if (running.get()) GuardianLog.w(applicationContext, "Tunnel read error.", t)
                    break
                }
                if (n <= 0) continue

                val query = DnsPacket.parseQuery(packet, n) ?: continue
                val host = query.qName

                if (host != null && BlocklistManager.isBlocked(host)) {
                    // Blocked: answer locally, nothing leaves the device.
                    writeToTunnel(DnsPacket.buildBlockedResponse(query))
                    GuardianLog.i(applicationContext, "BLOCKED dns query: $host")
                } else {
                    // Allowed: forward to the real resolver without blocking this loop.
                    forwarder.forward(query) { reply ->
                        if (running.get()) runCatching { writeToTunnel(reply) }
                    }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) GuardianLog.e(applicationContext, "DNS loop terminated abnormally.", t)
        } finally {
            forwarder.shutdown()
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    private fun stopTunnel(reason: String) {
        if (!running.compareAndSet(true, false)) return
        GuardianLog.i(applicationContext, "Stopping DNS filter tunnel: $reason")
        worker?.interrupt()
        worker = null
        runCatching { tunnel?.close() }
        tunnel = null
    }

    override fun onRevoke() {
        // Another VPN was selected, or the user revoked consent.
        GuardianLog.w(applicationContext, "VPN consent revoked by system/user.")
        stopTunnel("onRevoke")
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel("onDestroy")
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.personal.guardian.vpn.STOP"

        // Loopback-only tunnel addressing.
        private const val TUN_LOCAL_ADDR = "10.111.222.1"
        private const val SENTINEL_DNS = "10.111.222.2"
        private const val MTU = 1500

        // Public upstream resolver for allowed queries (Quad9, privacy-respecting).
        private const val UPSTREAM_DNS = "9.9.9.9"
        private const val UPSTREAM_TIMEOUT_MS = 5_000

        fun start(context: Context) {
            context.startService(Intent(context, GuardianVpnService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GuardianVpnService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
