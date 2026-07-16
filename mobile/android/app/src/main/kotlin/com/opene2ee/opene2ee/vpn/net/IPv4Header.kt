package com.opene2ee.opene2ee.vpn.net

import com.opene2ee.opene2ee.vpn.util.calculateSum
import com.opene2ee.opene2ee.vpn.util.readByte
import com.opene2ee.opene2ee.vpn.util.readInt
import com.opene2ee.opene2ee.vpn.util.readShort
import com.opene2ee.opene2ee.vpn.util.writeByte
import com.opene2ee.opene2ee.vpn.util.writeInt
import com.opene2ee.opene2ee.vpn.util.writeShort

/**
 * IPv4 header — 20 bytes minimum.
 *
 *    0               1               2               3
 *    0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7 0 1 2 3 4 5 6 7
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |Version|  IHL  |Type of Service|          Total Length         |  4
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |         Identification        |Flags|      Fragment Offset    |  8
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |  Time to Live |    Protocol   |         Header Checksum       | 12
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |                         Source Address                        | 16
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |                      Destination Address                      | 20
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class IPv4Header(
    private val packet: ByteArray,
    private val offset: Int = 0
) : IPHeader {

    companion object {
        const val MIN_IPV4_LENGTH = 20
        private const val OFFSET_TOTAL_LENGTH = 2
        private const val OFFSET_PROTOCOL = 9
        private const val OFFSET_CHECK_SUM = 10
        private const val OFFSET_SOURCE_ADDRESS = 12
        private const val OFFSET_DESTINATION_ADDRESS = 16
    }

    override val ipVersion: IPVersion = IPVersion.IPv4

    override var dataProtocol: Byte
        get() = packet[offset + OFFSET_PROTOCOL]
        set(value) = packet.writeByte(value, offset + OFFSET_PROTOCOL)

    override val headerLength: Int
        get() = packet.readByte(offset).toInt() and 0xF shl 2

    override val dataLength: Int
        get() = totalLength - headerLength

    override var totalLength: Int
        get() = packet.readShort(offset + OFFSET_TOTAL_LENGTH).toInt() and 0xFFFF
        set(value) = packet.writeShort(value.toShort(), offset + OFFSET_TOTAL_LENGTH)

    override var sourceAddress: IpAddress
        get() = IpAddress(packet.readInt(offset + OFFSET_SOURCE_ADDRESS))
        set(value) = packet.writeInt(value.intIPv4, offset + OFFSET_SOURCE_ADDRESS)

    override var destinationAddress: IpAddress
        get() = IpAddress(packet.readInt(offset + OFFSET_DESTINATION_ADDRESS))
        set(value) = packet.writeInt(value.intIPv4, offset + OFFSET_DESTINATION_ADDRESS)

    override val addressSum: Int
        get() = packet.calculateSum(offset + OFFSET_SOURCE_ADDRESS, 8)

    override fun notifyCheckSum() {
        checkSum = 0.toShort()
        checkSum = calculateChecksum()
    }

    var checkSum: Short
        get() = packet.readShort(offset + OFFSET_CHECK_SUM)
        private set(value) = packet.writeShort(value, offset + OFFSET_CHECK_SUM)

    private fun calculateChecksum(): Short {
        var sum = packet.calculateSum(offset, headerLength)
        while ((sum shr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toShort()
    }
}
