package com.opene2ee.opene2ee.vpn.tcpip

import com.opene2ee.opene2ee.vpn.net.IPHeader
import com.opene2ee.opene2ee.vpn.net.Port
import com.opene2ee.opene2ee.vpn.util.calculateSum
import com.opene2ee.opene2ee.vpn.util.readByte
import com.opene2ee.opene2ee.vpn.util.readInt
import com.opene2ee.opene2ee.vpn.util.readShort
import com.opene2ee.opene2ee.vpn.util.writeByte
import com.opene2ee.opene2ee.vpn.util.writeShort
import kotlin.experimental.and

class TcpHeader(
    private val ipHeader: IPHeader,
    private val packet: ByteArray,
    private val offset: Int
) {
    companion object {
        private const val OFFSET_SOURCE_PORT = 0
        private const val OFFSET_DESTINATION_PORT = 2
        private const val OFFSET_SEQUENCE_NUMBER = 4
        private const val OFFSET_ACKNOWLEDGMENT_NUMBER = 8
        private const val OFFSET_OFFSET = 12
        private const val OFFSET_FLAG = 13
        private const val OFFSET_WINDOW = 14
        private const val OFFSET_CHECK_SUM = 16
        private const val MASK_FIN: Byte = 0b00000001
        private const val MASK_SYN: Byte = 0b00000010
        private const val MASK_RST: Byte = 0b00000100
        private const val MASK_PSH: Byte = 0b00001000
        private const val MASK_ACK: Byte = 0b00010000
    }

    var sourcePort: Port
        get() = Port(packet.readShort(offset + OFFSET_SOURCE_PORT))
        set(value) = packet.writeShort(value.port, offset + OFFSET_SOURCE_PORT)

    var destinationPort: Port
        get() = Port(packet.readShort(offset + OFFSET_DESTINATION_PORT))
        set(value) = packet.writeShort(value.port, offset + OFFSET_DESTINATION_PORT)

    val sequenceNumber: Int get() = packet.readInt(offset + OFFSET_SEQUENCE_NUMBER)
    val acknowledgmentNumber: Int get() = packet.readInt(offset + OFFSET_ACKNOWLEDGMENT_NUMBER)

    val headerLength: Int
        get() = packet.readByte(offset + OFFSET_OFFSET).toInt() and 0xFF ushr 4 shl 2

    val dataLength: Int get() = ipHeader.dataLength - headerLength

    var flag: Byte
        get() = packet.readByte(offset + OFFSET_FLAG)
        set(value) = packet.writeByte(value, offset + OFFSET_FLAG)

    val fin: Boolean get() = flag and MASK_FIN == MASK_FIN
    val syn: Boolean get() = flag and MASK_SYN == MASK_SYN
    val rst: Boolean get() = flag and MASK_RST == MASK_RST
    val psh: Boolean get() = flag and MASK_PSH == MASK_PSH
    val ack: Boolean get() = flag and MASK_ACK == MASK_ACK

    var window: Int
        get() = packet.readShort(OFFSET_WINDOW).toInt() and 0xFFFF
        set(value) = packet.writeShort((value and 0xFFFF).toShort(), offset + OFFSET_CHECK_SUM)

    var checkSum: Short
        get() = packet.readShort(offset + OFFSET_CHECK_SUM)
        private set(value) = packet.writeShort(value, offset + OFFSET_CHECK_SUM)

    fun notifyCheckSum() {
        checkSum = 0.toShort()
        checkSum = calculateChecksum()
    }

    private fun calculateChecksum(): Short {
        val totalLength = ipHeader.dataLength
        var sum: Int = ipHeader.addressSum
        sum += ipHeader.dataProtocol.toInt() and 0xF
        sum += totalLength
        sum += packet.calculateSum(offset, totalLength)
        while ((sum shr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toShort()
    }
}
