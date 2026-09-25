package com.personal.guardian.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.personal.guardian.blocklist.BlocklistManager
import com.personal.guardian.util.GuardianLog
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
 *    NXDOMAIN, everything else is forwarded to [UPSTREAM_DNS] over a protected
 *    socket and the reply is written back into the tunnel.
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

        // One protected UDP socket for forwarding allowed queries upstream. Created
        // inside the try so any failure is logged and cleaned up like any other.
        var upstream: DatagramSocket? = null
        try {
            val socket = DatagramSocket().also { protect(it) }
            upstream = socket
            val upstreamAddr = InetAddress.getByName(UPSTREAM_DNS)

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
                    val response = DnsPacket.buildBlockedResponse(query)
                    output.write(response)
                    GuardianLog.i(applicationContext, "BLOCKED dns query: $host")
                } else {
                    // Allowed: forward to the real resolver and relay the answer.
                    forward(query, socket, upstreamAddr, output)
                }
            }
        } catch (t: Throwable) {
            if (running.get()) GuardianLog.e(applicationContext, "DNS loop terminated abnormally.", t)
        } finally {
            runCatching { upstream?.close() }
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    private fun forward(
        query: DnsPacket.Query,
        upstream: DatagramSocket,
        upstreamAddr: InetAddress,
        output: FileOutputStream
    ) {
        try {
            val out = DatagramPacket(query.dnsPayload, query.dnsPayload.size, upstreamAddr, DnsPacket.DNS_PORT)
            upstream.send(out)

            val buf = ByteArray(MTU)
            val reply = DatagramPacket(buf, buf.size)
            upstream.soTimeout = UPSTREAM_TIMEOUT_MS
            upstream.receive(reply)

            val answer = buf.copyOfRange(0, reply.length)
            output.write(DnsPacket.buildForwardedResponse(query, answer))
        } catch (t: Throwable) {
            // On upstream failure, fail closed for this one query (no reply written);
            // the client resolver will retry. Log at warn to avoid log spam on flaky
            // networks.
            GuardianLog.w(applicationContext, "Upstream DNS forward failed for ${query.qName}.", t)
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
