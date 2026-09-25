package com.personal.guardian.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the IPv4/UDP/DNS packet helpers. We hand-build a realistic
 * DNS query packet, parse it, and check the synthesized blocked response.
 */
class DnsPacketTest {

    /** Builds an IPv4/UDP DNS query for [domain] from src->dst. */
    private fun buildQueryPacket(domain: String): ByteArray {
        val dns = buildDnsQuery(domain)
        val udpLen = 8 + dns.size
        val total = 20 + udpLen
        val p = ByteArray(total)
        // IPv4
        p[0] = 0x45
        p[9] = 17 // UDP
        // total length
        p[2] = ((total ushr 8) and 0xFF).toByte(); p[3] = (total and 0xFF).toByte()
        // src 10.111.222.1
        p[12] = 10; p[13] = 111; p[14] = 222.toByte(); p[15] = 1
        // dst 10.111.222.2 (sentinel)
        p[16] = 10; p[17] = 111; p[18] = 222.toByte(); p[19] = 2
        // UDP header
        val u = 20
        p[u] = 0xC0.toByte(); p[u + 1] = 0x10       // src port 49168
        p[u + 2] = 0x00; p[u + 3] = 0x35            // dst port 53
        p[u + 4] = ((udpLen ushr 8) and 0xFF).toByte(); p[u + 5] = (udpLen and 0xFF).toByte()
        System.arraycopy(dns, 0, p, u + 8, dns.size)
        return p
    }

    /** Minimal DNS query message: header + one A-record question. */
    private fun buildDnsQuery(domain: String): ByteArray {
        val labels = domain.split('.')
        val body = ArrayList<Byte>()
        // header: id=0x1234, flags=0x0100 (RD), qd=1
        body.addAll(listOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00).map { it.toByte() })
        for (label in labels) {
            body.add(label.length.toByte())
            for (c in label) body.add(c.code.toByte())
        }
        body.add(0) // root
        body.addAll(listOf(0x00, 0x01, 0x00, 0x01).map { it.toByte() }) // QTYPE=A, QCLASS=IN
        return body.toByteArray()
    }

    @Test
    fun parsesQueryNameAndAddressing() {
        val pkt = buildQueryPacket("www.example.com")
        val q = DnsPacket.parseQuery(pkt, pkt.size)
        assertNotNull(q)
        q!!
        assertEquals("www.example.com", q.qName)
        assertEquals(53, q.dstPort)
        assertEquals(0xC010, q.srcPort)
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 1), q.srcAddr)
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 2), q.dstAddr)
    }

    @Test
    fun rejectsNonIpv4() {
        val notIp = ByteArray(40) // version nibble = 0
        assertEquals(null, DnsPacket.parseQuery(notIp, notIp.size))
    }

    @Test
    fun buildsNxdomainResponseAddressedBackToClient() {
        val pkt = buildQueryPacket("blocked.example")
        val q = DnsPacket.parseQuery(pkt, pkt.size)!!
        val resp = DnsPacket.parseQuery(DnsPacket.buildBlockedResponse(q), 512)
        assertNotNull(resp)
        resp!!
        // response src/dst are swapped relative to the query
        assertArrayEquals(q.dstAddr, resp.srcAddr)
        assertArrayEquals(q.srcAddr, resp.dstAddr)
        assertEquals(q.srcPort, resp.dstPort)

        // DNS flags: QR=1 and RCODE=3 (NXDOMAIN)
        val dns = resp.dnsPayload
        assertTrue("QR bit set", (dns[2].toInt() and 0x80) != 0)
        assertEquals("RCODE=3", 3, dns[3].toInt() and 0x0F)
        // transaction id preserved
        assertEquals(0x12, dns[0].toInt() and 0xFF)
        assertEquals(0x34, dns[1].toInt() and 0xFF)
        // no answers
        assertEquals(0, ((dns[6].toInt() and 0xFF) shl 8) or (dns[7].toInt() and 0xFF))
    }
}
