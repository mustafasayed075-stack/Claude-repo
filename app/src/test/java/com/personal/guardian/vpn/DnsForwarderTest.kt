package com.personal.guardian.vpn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure-JVM tests for [DnsForwarder] against a fake resolver on localhost. These
 * pin down the property the tunnel depends on: a slow or lost upstream reply must
 * only delay its own query, never the lookups queued behind it.
 */
class DnsForwarderTest {

    /** Per-name behaviour of the fake resolver. */
    private val delaysMs = ConcurrentHashMap<String, Long>()
    private val dropNames: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val sendStrayFirst: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private lateinit var server: DatagramSocket
    private val serverPool = Executors.newCachedThreadPool()
    private lateinit var forwarder: DnsForwarder

    @Before
    fun setUp() {
        server = DatagramSocket(0, InetAddress.getLoopbackAddress())
        Thread({
            val buf = ByteArray(1500)
            while (!server.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                try { server.receive(pkt) } catch (_: Exception) { break }
                val query = buf.copyOfRange(0, pkt.length)
                val from = pkt.socketAddress
                serverPool.execute { answer(query, from) }
            }
        }, "fake-resolver").apply { isDaemon = true; start() }
    }

    @After
    fun tearDown() {
        if (::forwarder.isInitialized) forwarder.shutdown()
        server.close()
        serverPool.shutdownNow()
    }

    private fun answer(query: ByteArray, to: java.net.SocketAddress) {
        val name = qName(query)
        if (name in dropNames) return
        delaysMs[name]?.let { Thread.sleep(it) }
        val reply = query.copyOf()
        reply[2] = (reply[2].toInt() or 0x80).toByte() // QR=1
        if (name in sendStrayFirst) {
            val stray = reply.copyOf()
            stray[0] = (stray[0].toInt() xor 0xFF).toByte() // wrong txn id
            server.send(DatagramPacket(stray, stray.size, to))
        }
        server.send(DatagramPacket(reply, reply.size, to))
    }

    private fun newForwarder(timeoutMs: Int, onFailure: (DnsPacket.Query, Throwable) -> Unit = { _, _ -> }) =
        DnsForwarder(
            upstream = InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort),
            timeoutMs = timeoutMs,
            socketFactory = { DatagramSocket() },
            onFailure = onFailure
        ).also { forwarder = it }

    @Test
    fun slowUpstreamReplyDoesNotStallOtherLookups() {
        delaysMs["slow.example"] = 2_000
        val fwd = newForwarder(timeoutMs = 5_000)

        val fastCount = 50
        val fastDone = CountDownLatch(fastCount)
        val slowDone = CountDownLatch(1)

        val start = System.nanoTime()
        fwd.forward(query("slow.example", 1)) { slowDone.countDown() }
        for (i in 0 until fastCount) {
            fwd.forward(query("site$i.example", 100 + i)) { fastDone.countDown() }
        }

        assertTrue("fast lookups should all finish", fastDone.await(1, TimeUnit.SECONDS))
        val fastMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        println("DnsForwarderTest: $fastCount lookups finished in $fastMs ms while one upstream reply was delayed 2000 ms")
        assertTrue("fast lookups must not wait behind the slow one (took $fastMs ms)", fastMs < 1_000)
        assertEquals("slow lookup still pending", 1L, slowDone.count)
        assertTrue("slow lookup eventually answered", slowDone.await(4, TimeUnit.SECONDS))
    }

    @Test
    fun manyConcurrentLookupsCompleteInParallel() {
        // Realistic per-lookup upstream latency. Serially, 200 lookups would take ~6 s.
        val latencyMs = 30L
        val count = 200
        for (i in 0 until count) delaysMs["host$i.example"] = latencyMs
        val fwd = newForwarder(timeoutMs = 5_000)

        val done = CountDownLatch(count)
        val start = System.nanoTime()
        for (i in 0 until count) fwd.forward(query("host$i.example", i)) { done.countDown() }
        assertTrue("all lookups answered", done.await(5, TimeUnit.SECONDS))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        val serialMs = count * latencyMs
        println("DnsForwarderTest: $count lookups @ ${latencyMs}ms upstream latency took $elapsedMs ms (serial forwarding would be ~$serialMs ms)")
        assertTrue("expected parallel forwarding, took $elapsedMs ms", elapsedMs < serialMs / 4)
    }

    @Test
    fun eachReplyIsMatchedToItsOwnQuery() {
        sendStrayFirst.add("stray.example")
        val fwd = newForwarder(timeoutMs = 2_000)

        val got = ConcurrentHashMap<Int, Int>() // query id -> reply id
        val done = CountDownLatch(21)
        fwd.forward(query("stray.example", 0x0A0B)) { got[0x0A0B] = replyId(it); done.countDown() }
        for (i in 1..20) {
            val id = 0x2000 + i
            fwd.forward(query("n$i.example", id)) { got[id] = replyId(it); done.countDown() }
        }
        assertTrue(done.await(3, TimeUnit.SECONDS))
        got.forEach { (queryId, replyId) -> assertEquals("reply id for query $queryId", queryId, replyId) }
        assertEquals(21, got.size)
    }

    @Test
    fun unansweredQueryFailsAfterTimeoutWithoutDelivering() {
        dropNames.add("lost.example")
        val failed = CountDownLatch(1)
        val fwd = newForwarder(timeoutMs = 300) { _, _ -> failed.countDown() }

        val delivered = AtomicBoolean(false)
        fwd.forward(query("lost.example", 7)) { delivered.set(true) }
        assertTrue("failure reported", failed.await(2, TimeUnit.SECONDS))
        assertFalse("nothing written for a lost query", delivered.get())
    }

    // ---- helpers ----

    private fun query(name: String, id: Int): DnsPacket.Query = DnsPacket.Query(
        srcAddr = byteArrayOf(10, 111, 222.toByte(), 1),
        dstAddr = byteArrayOf(10, 111, 222.toByte(), 2),
        srcPort = 40_000,
        dstPort = DnsPacket.DNS_PORT,
        dnsPayload = dnsQuery(name, id),
        qName = name
    )

    private fun dnsQuery(name: String, id: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf((id ushr 8).toByte(), id.toByte(), 0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(byteArrayOf(0, 0x00, 0x01, 0x00, 0x01))
        return out.toByteArray()
    }

    private fun qName(dns: ByteArray): String {
        val sb = StringBuilder()
        var pos = 12
        while (pos < dns.size && dns[pos].toInt() != 0) {
            val len = dns[pos].toInt()
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, pos + 1, len))
            pos += 1 + len
        }
        return sb.toString()
    }

    /** Txn id of the DNS message inside a full IPv4/UDP reply packet. */
    private fun replyId(packet: ByteArray): Int =
        ((packet[28].toInt() and 0xFF) shl 8) or (packet[29].toInt() and 0xFF)
}
