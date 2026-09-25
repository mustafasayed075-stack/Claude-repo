package com.personal.guardian.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Forwards allowed DNS queries to the upstream resolver **concurrently**.
 *
 * The tunnel loop must never wait on the network: if it did, every lookup on the
 * device would queue behind the one in flight, and a single lost UDP reply would
 * stall all DNS for the full timeout. Instead each query is handed to a small
 * thread pool and gets its own short-lived socket, so:
 *  - lookups proceed in parallel, and a slow/lost one only delays itself;
 *  - replies can never be mismatched between queries (each socket has its own
 *    ephemeral port, and the transaction id is checked as well).
 *
 * Kept free of Android APIs (the socket factory is injected) so it can be tested
 * on the plain JVM against a local fake resolver.
 */
class DnsForwarder(
    private val upstream: InetSocketAddress,
    private val timeoutMs: Int,
    /** Creates an upstream socket; on device this also calls VpnService.protect(). */
    private val socketFactory: () -> DatagramSocket,
    private val onFailure: (DnsPacket.Query, Throwable) -> Unit = { _, _ -> },
    private val maxReplyBytes: Int = DEFAULT_MAX_REPLY_BYTES
) {

    private val executor = ThreadPoolExecutor(
        POOL_SIZE, POOL_SIZE,
        IDLE_KEEPALIVE_S, TimeUnit.SECONDS,
        LinkedBlockingQueue(MAX_QUEUED),
        DaemonThreadFactory(),
        // Only reached under extreme load: drop the query, the client resolver retries.
        ThreadPoolExecutor.DiscardPolicy()
    ).apply { allowCoreThreadTimeOut(true) }

    /**
     * Queues [query] for forwarding and returns immediately. [deliver] is invoked
     * from a pool thread with the full IPv4/UDP reply packet to write to the tunnel.
     * If the upstream fails or times out, nothing is delivered (the client retries).
     */
    fun forward(query: DnsPacket.Query, deliver: (ByteArray) -> Unit) {
        if (executor.isShutdown) return
        executor.execute { forwardBlocking(query, deliver) }
    }

    private fun forwardBlocking(query: DnsPacket.Query, deliver: (ByteArray) -> Unit) {
        try {
            socketFactory().use { socket ->
                socket.soTimeout = timeoutMs
                val payload = query.dnsPayload
                socket.send(DatagramPacket(payload, payload.size, upstream))

                val buf = ByteArray(maxReplyBytes)
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
                while (true) {
                    val reply = DatagramPacket(buf, buf.size)
                    socket.receive(reply) // throws SocketTimeoutException when out of time
                    if (isReplyTo(payload, reply)) {
                        deliver(DnsPacket.buildForwardedResponse(query, buf.copyOfRange(0, reply.length)))
                        return
                    }
                    // Stray datagram (wrong sender or txn id): ignore it and keep waiting
                    // for the real answer within the remaining time budget.
                    val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                    if (remainingMs <= 0) throw java.net.SocketTimeoutException("No matching DNS reply")
                    socket.soTimeout = remainingMs.toInt()
                }
            }
        } catch (t: Throwable) {
            onFailure(query, t)
        }
    }

    private fun isReplyTo(queryPayload: ByteArray, reply: DatagramPacket): Boolean {
        if (reply.length < 12 || queryPayload.size < 2) return false
        if (reply.port != upstream.port || reply.address != upstream.address) return false
        val data = reply.data
        val off = reply.offset
        return data[off] == queryPayload[0] && data[off + 1] == queryPayload[1]
    }

    /** Stops accepting work; in-flight lookups finish or time out on their own. */
    fun shutdown() {
        executor.shutdownNow()
    }

    private class DaemonThreadFactory : ThreadFactory {
        private val seq = AtomicInteger()
        override fun newThread(r: Runnable): Thread =
            Thread(r, "guardian-dns-fwd-${seq.incrementAndGet()}").apply { isDaemon = true }
    }

    companion object {
        /** Max concurrent upstream lookups. Browsers fire dozens of lookups per page. */
        const val POOL_SIZE = 32
        private const val MAX_QUEUED = 1024
        private const val IDLE_KEEPALIVE_S = 30L

        /** Largest DNS reply that still fits in one tunnel packet (MTU 1500 - IP/UDP headers). */
        const val DEFAULT_MAX_REPLY_BYTES = 1500 - 28
    }
}
