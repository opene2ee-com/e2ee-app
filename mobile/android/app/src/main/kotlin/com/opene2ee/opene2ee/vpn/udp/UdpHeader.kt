package com.opene2ee.opene2ee.vpn.udp

import com.opene2ee.opene2ee.vpn.net.IPHeader
import com.opene2ee.opene2ee.vpn.net.Port
import com.opene2ee.opene2ee.vpn.util.calculateSum
import com.opene2ee.opene2ee.vpn.util.readShort
import com.opene2ee.opene2ee.vpn.util.writeShort
import java.nio.ByteBuffer

class UdpHeader(
    val ipHeader: IPHeader,
    val packet: ByteArray,
    private val offset: Int
) {
    companion object {
        const val UDP_HEADER_LENGTH = 8
        private const val OFFSET_SOURCE_PORT = 0
        private const val OFFSET_DESTINATION_PORT = 2
        private const val OFFSET_LENGTH = 4
        private const val OFFSET_CHECK_SUM = 6
    }

    var sourcePort: Port
        get() = Port(packet.readShort(offset + OFFSET_SOURCE_PORT))
        set(value) = packet.writeShort(value.port, offset + OFFSET_SOURCE_PORT)

    var destinationPort: Port
        get() = Port(packet.readShort(offset + OFFSET_DESTINATION_PORT))
        set(value) = packet.writeShort(value.port, offset + OFFSET_DESTINATION_PORT)

    var totalLength: Int
        get() = packet.readShort(offset + OFFSET_LENGTH).toInt()
        set(value) = packet.writeShort(value.toShort(), offset + OFFSET_LENGTH)

    var checkSum: Short
        get() = packet.readShort(offset + OFFSET_CHECK_SUM)
        private set(value) = packet.writeShort(value, offset + OFFSET_CHECK_SUM)

    val data: ByteBuffer
        get() = ByteBuffer.wrap(packet, offset + UDP_HEADER_LENGTH, totalLength - UDP_HEADER_LENGTH)

    fun notifyCheckSum() {
        checkSum = 0.toShort()
        checkSum = calculateChecksum()
    }

    /**
     * Returns a deep copy of this UDP header.
     *
     * Allocates a fresh byte array sized to the IP header + UDP header (28 bytes
     * for IPv4, 48 bytes for IPv6) and re-parses the IP header from it. The
     * original packet byte array is left untouched.
     *
     * This is required because UdpRealTunnel constructs a request/response
     * template by swapping source/destination addresses and ports in place;
     * doing so on the caller's packet would corrupt the original DNS query
     * (see Sprint 18 spec §1 root cause and Sprint 17 verifier diagnosis).
     */
    fun copy(): UdpHeader {
        val newPacket = ByteArray(ipHeader.headerLength + UDP_HEADER_LENGTH) { i ->
            packet[i]
        }.also { arr ->
            // UDP length = header-only (8) for the request/response template
            arr.writeShort(UDP_HEADER_LENGTH.toShort(), offset + OFFSET_LENGTH)
        }
        val newIpHeader = IPHeader.parse(newPacket, newPacket.size, 0)
            ?: error("UdpHeader.copy: failed to re-parse IP header")
        newIpHeader.totalLength = newIpHeader.headerLength + UDP_HEADER_LENGTH
        return UdpHeader(newIpHeader, newPacket, offset)
    }

    private fun calculateChecksum(): Short {
        val dataLength = ipHeader.dataLength
        var sum: Int = ipHeader.addressSum
        sum += ipHeader.dataProtocol.toInt() and 0xF
        sum += dataLength
        sum += packet.calculateSum(offset, dataLength)
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toShort()
    }
}
