package com.personal.guardian.vpn

import java.nio.ByteBuffer

/**
 * Minimal IPv4 + UDP + DNS packet helpers for the DNS-filtering VPN.
 *
 * The VPN routes only DNS traffic (a single sentinel resolver address) into the
 * tunnel, so every packet we read here is expected to be an IPv4/UDP DNS query.
 * These helpers parse just enough to:
 *   - confirm a packet is IPv4/UDP,
 *   - extract the queried name,
 *   - build a reply datagram (either a synthesized NXDOMAIN for blocked names, or
 *     a wrapper around an upstream resolver's response for allowed names).
 *
 * Everything is done on plain byte arrays to avoid per-packet allocation churn in
 * the tunnel loop where practical. IPv6 is intentionally not handled — the tunnel
 * only advertises an IPv4 DNS server, so no IPv6 DNS is routed to us.
 */
object DnsPacket {

    const val PROTO_UDP = 17
    const val DNS_PORT = 53

    /** Parsed view of an IPv4/UDP DNS query read from the tunnel. */
    data class Query(
        val srcAddr: ByteArray,   // 4 bytes
        val dstAddr: ByteArray,   // 4 bytes
        val srcPort: Int,
        val dstPort: Int,
        val dnsPayload: ByteArray, // the DNS message (starts with the 2-byte txn id)
        val qName: String?         // queried domain, lowercased, no trailing dot
    )

    /**
     * Parses an IPv4/UDP packet. Returns null if the packet is not IPv4/UDP or is
     * malformed — callers should simply drop such packets.
     */
    fun parseQuery(packet: ByteArray, length: Int): Query? {
        if (length < 28) return null // 20 (IP) + 8 (UDP) minimum
        val version = (packet[0].toInt() ushr 4) and 0xF
        if (version != 4) return null
        val ihl = (packet[0].toInt() and 0xF) * 4
        if (ihl < 20 || ihl > length) return null
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != PROTO_UDP) return null

        val srcAddr = packet.copyOfRange(12, 16)
        val dstAddr = packet.copyOfRange(16, 20)

        val udpStart = ihl
        if (udpStart + 8 > length) return null
        val srcPort = readU16(packet, udpStart)
        val dstPort = readU16(packet, udpStart + 2)
        val udpLen = readU16(packet, udpStart + 4)

        val payloadStart = udpStart + 8
        val payloadLen = (udpLen - 8).coerceAtMost(length - payloadStart)
        if (payloadLen <= 0 || payloadStart + payloadLen > length) return null

        val dnsPayload = packet.copyOfRange(payloadStart, payloadStart + payloadLen)
        val qName = runCatching { readQName(dnsPayload) }.getOrNull()

        return Query(srcAddr, dstAddr, srcPort, dstPort, dnsPayload, qName)
    }

    /** Reads the first question's QNAME from a DNS message. */
    private fun readQName(dns: ByteArray): String? {
        if (dns.size < 12) return null
        val qdCount = readU16(dns, 4)
        if (qdCount < 1) return null
        var pos = 12 // header is 12 bytes
        val sb = StringBuilder()
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) break
            // Compression pointers do not appear in a question's QNAME; bail if seen.
            if (len and 0xC0 != 0) return null
            pos++
            if (pos + len > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until len) sb.append((dns[pos + i].toInt() and 0xFF).toChar())
            pos += len
        }
        return if (sb.isEmpty()) null else sb.toString().lowercase()
    }

    /**
     * Builds a full IPv4/UDP packet carrying an NXDOMAIN response for a blocked
     * query, addressed back to the original requester. The DNS body echoes the
     * original question with QR=1, RA=1 and RCODE=3 (Name Error) and no answers.
     */
    fun buildBlockedResponse(query: Query): ByteArray {
        val dns = query.dnsPayload
        // DNS response = same header (flags rewritten) + the question section.
        // Locate end of question section by re-walking QNAME + QTYPE + QCLASS.
        val questionEnd = dnsQuestionEnd(dns) ?: (dns.size)
        val body = ByteArray(questionEnd)
        System.arraycopy(dns, 0, body, 0, questionEnd)

        // Flags: QR=1, Opcode=copy, AA=0, TC=0, RD=copy, RA=1, RCODE=3
        val rd = (dns[2].toInt() and 0x01)
        body[2] = (0x80 or (rd shl 0) or ((dns[2].toInt() and 0x78))).toByte() // QR + preserve opcode + RD
        body[3] = (0x80 or 0x03).toByte() // RA=1, RCODE=3 (NXDOMAIN)
        // ANCOUNT / NSCOUNT / ARCOUNT = 0
        writeU16(body, 6, 0)
        writeU16(body, 8, 0)
        writeU16(body, 10, 0)

        return wrapUdpIntoIp(
            srcAddr = query.dstAddr, // responder is the sentinel DNS (original dst)
            dstAddr = query.srcAddr,
            srcPort = query.dstPort,
            dstPort = query.srcPort,
            payload = body
        )
    }

    /**
     * Wraps an upstream resolver's raw DNS response [upstreamDns] into an IPv4/UDP
     * packet addressed back to the original requester.
     */
    fun buildForwardedResponse(query: Query, upstreamDns: ByteArray): ByteArray =
        wrapUdpIntoIp(
            srcAddr = query.dstAddr,
            dstAddr = query.srcAddr,
            srcPort = query.dstPort,
            dstPort = query.srcPort,
            payload = upstreamDns
        )

    private fun dnsQuestionEnd(dns: ByteArray): Int? {
        if (dns.size < 12) return null
        var pos = 12
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) { pos++; break }
            if (len and 0xC0 != 0) return null
            pos += 1 + len
        }
        pos += 4 // QTYPE (2) + QCLASS (2)
        return if (pos <= dns.size) pos else null
    }

    /** Builds an IPv4 header + UDP header + payload with correct checksums. */
    private fun wrapUdpIntoIp(
        srcAddr: ByteArray,
        dstAddr: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val udpLen = udpHeaderLen + payload.size
        val totalLen = ipHeaderLen + udpLen
        val pkt = ByteArray(totalLen)

        // ---- IPv4 header ----
        pkt[0] = 0x45.toByte()          // version 4, IHL 5
        pkt[1] = 0x00                   // DSCP/ECN
        writeU16(pkt, 2, totalLen)      // total length
        writeU16(pkt, 4, 0)             // identification
        writeU16(pkt, 6, 0x4000)        // flags: don't fragment
        pkt[8] = 64                     // TTL
        pkt[9] = PROTO_UDP.toByte()     // protocol
        // checksum (10..11) computed below
        System.arraycopy(srcAddr, 0, pkt, 12, 4)
        System.arraycopy(dstAddr, 0, pkt, 16, 4)
        writeU16(pkt, 10, checksum(pkt, 0, ipHeaderLen))

        // ---- UDP header ----
        val u = ipHeaderLen
        writeU16(pkt, u, srcPort)
        writeU16(pkt, u + 2, dstPort)
        writeU16(pkt, u + 4, udpLen)
        // checksum (u+6..u+7) computed below
        System.arraycopy(payload, 0, pkt, u + 8, payload.size)
        writeU16(pkt, u + 6, udpChecksum(srcAddr, dstAddr, pkt, u, udpLen))

        return pkt
    }

    // ---- checksum helpers ----

    private fun checksum(data: ByteArray, offset: Int, len: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + len
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun udpChecksum(srcAddr: ByteArray, dstAddr: ByteArray, pkt: ByteArray, udpOffset: Int, udpLen: Int): Int {
        var sum = 0L
        // Pseudo-header: src(4) + dst(4) + zero(1) + proto(1) + udpLen(2)
        sum += ((srcAddr[0].toInt() and 0xFF) shl 8) or (srcAddr[1].toInt() and 0xFF)
        sum += ((srcAddr[2].toInt() and 0xFF) shl 8) or (srcAddr[3].toInt() and 0xFF)
        sum += ((dstAddr[0].toInt() and 0xFF) shl 8) or (dstAddr[1].toInt() and 0xFF)
        sum += ((dstAddr[2].toInt() and 0xFF) shl 8) or (dstAddr[3].toInt() and 0xFF)
        sum += PROTO_UDP.toLong()
        sum += udpLen.toLong()

        var i = udpOffset
        val end = udpOffset + udpLen
        while (i + 1 < end) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (pkt[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val cs = (sum.inv() and 0xFFFF).toInt()
        // A computed 0 must be transmitted as 0xFFFF for UDP.
        return if (cs == 0) 0xFFFF else cs
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun writeU16(b: ByteArray, off: Int, value: Int) {
        b[off] = ((value ushr 8) and 0xFF).toByte()
        b[off + 1] = (value and 0xFF).toByte()
    }

    /** Convenience for tests: parse a query straight from a ByteBuffer's array. */
    fun parseQuery(buffer: ByteBuffer, length: Int): Query? =
        parseQuery(buffer.array(), length)
}
