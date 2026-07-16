package com.opene2ee.opene2ee.vpn.net

import com.opene2ee.opene2ee.vpn.util.calculateSum
import com.opene2ee.opene2ee.vpn.util.readByte
import com.opene2ee.opene2ee.vpn.util.readLong
import com.opene2ee.opene2ee.vpn.util.readShort
import com.opene2ee.opene2ee.vpn.util.writeLong
import com.opene2ee.opene2ee.vpn.util.writeShort

/**
 * IPv6 header — 40 bytes standard, extension headers (if any)
 * advance the nextHeaderOffset until TCP/UDP is found.
 */
class IPv6Header(
    val packet: ByteArray,
    val offset: Int = 0
) : IPHeader {

    companion object {
        const val IPV6_STANDARD_LENGTH = 40
        private const val OFFSET_PAYLOAD_LENGTH = 4
        private const val OFFSET_STANDARD_NEXT_HEADER = 6
        private const val OFFSET_SOURCE_ADDRESS_HIGH_64 = 8
        private const val OFFSET_SOURCE_ADDRESS_LOW_64 = 16
        private const val OFFSET_DESTINATION_ADDRESS_HIGH_64 = 24
        private const val OFFSET_DESTINATION_ADDRESS_LOW_64 = 32
    }

    override val ipVersion: IPVersion = IPVersion.IPv6
    override val dataProtocol: Byte
    override val headerLength: Int

    init {
        var nextHeaderOffset = IPV6_STANDARD_LENGTH
        var nextHeader = standardNextHeader
        while (true) {
            when (Protocol.parse(nextHeader.toByte())) {
                Protocol.TCP, Protocol.UDP -> {
                    dataProtocol = nextHeader.toByte()
                    headerLength = nextHeaderOffset
                    break
                }
                Protocol.NULL -> {
                    val extHeaderLength =
                        packet.readByte(offset + nextHeaderOffset + 1).toInt() and 0xFF
                    nextHeaderOffset += 8 * extHeaderLength + 2
                    nextHeader = try {
                        packet.readByte(nextHeaderOffset).toInt() and 0xFF
                    } catch (_: Exception) {
                        Protocol.END.code.toInt()
                    }
                }
                Protocol.END -> {
                    dataProtocol = Protocol.END.code
                    headerLength = 0
                    break
                }
            }
        }
    }

    override var totalLength: Int = IPV6_STANDARD_LENGTH + payloadLength

    override val dataLength: Int
        get() = totalLength - headerLength

    override var sourceAddress: IpAddress
        get() = IpAddress(IntIPv6(
            packet.readLong(offset + OFFSET_SOURCE_ADDRESS_HIGH_64),
            packet.readLong(offset + OFFSET_SOURCE_ADDRESS_LOW_64)
        ))
        set(value) {
            packet.writeLong(value.intIPv6.high64, offset + OFFSET_SOURCE_ADDRESS_HIGH_64)
            packet.writeLong(value.intIPv6.low64, offset + OFFSET_SOURCE_ADDRESS_LOW_64)
        }

    override var destinationAddress: IpAddress
        get() = IpAddress(IntIPv6(
            packet.readLong(offset + OFFSET_DESTINATION_ADDRESS_HIGH_64),
            packet.readLong(offset + OFFSET_DESTINATION_ADDRESS_LOW_64)
        ))
        set(value) {
            packet.writeLong(value.intIPv6.high64, offset + OFFSET_DESTINATION_ADDRESS_HIGH_64)
            packet.writeLong(value.intIPv6.low64, offset + OFFSET_DESTINATION_ADDRESS_LOW_64)
        }

    override val addressSum: Int
        get() = packet.calculateSum(offset + OFFSET_SOURCE_ADDRESS_HIGH_64, 32)

    override fun notifyCheckSum() {
        // IPv6 has no header checksum
    }

    var payloadLength: Int
        get() = packet.readShort(offset + OFFSET_PAYLOAD_LENGTH).toInt() and 0xFFFF
        set(value) = packet.writeShort(value.toShort(), offset + OFFSET_PAYLOAD_LENGTH)

    val standardNextHeader: Int
        get() = packet.readByte(OFFSET_STANDARD_NEXT_HEADER).toInt() and 0xFF
}
