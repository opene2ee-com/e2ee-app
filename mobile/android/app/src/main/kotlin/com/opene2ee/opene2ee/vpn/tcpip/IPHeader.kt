// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/tcpip/IPHeader.kt
//
// Sprint 14 — IPv4 header parser/mutator.
// Referans: huolizhuminh/NetWorkPacketCapture IPHeader.java (byte[] tabanlı).

package com.opene2ee.opene2ee.vpn.tcpip

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.util.CommonMethods

@Keep
class IPHeader {
    @Keep
    companion object {
        const val TCP: Byte = 6
        const val UDP: Byte = 17
        const val ICMP: Byte = 1

        const val OFFSET_VER_IHL = 0      // version(4) + IHL(4)
        const val OFFSET_TOS = 1
        const val OFFSET_TLEN = 2         // total length (16)
        const val OFFSET_IDENT = 4
        const val OFFSET_FLAGS_FO = 6
        const val OFFSET_TTL = 8
        const val OFFSET_PROTO = 9        // protocol
        const val OFFSET_CRC = 10
        const val OFFSET_SRC_IP = 12      // 4 byte
        const val OFFSET_DST_IP = 16      // 4 byte
        const val OFFSET_OP_PAD = 20      // options
    }

    @Keep var mData: ByteArray = ByteArray(0)
    @Keep var mOffset: Int = 0

    @Keep constructor()

    @Keep
    constructor(data: ByteArray, offset: Int) {
        mData = data
        mOffset = offset
    }

    /** IHL * 4 = header length in bytes. */
    @Keep
    fun getHeaderLength(): Int {
        return (mData[mOffset + OFFSET_VER_IHL].toInt() and 0x0F) * 4
    }

    @Keep
    fun setHeaderLength(value: Int) {
        mData[mOffset + OFFSET_VER_IHL] = ((4 shl 4) or (value / 4)).toByte()
    }

    @Keep
    fun getTotalLength(): Int {
        return CommonMethods.readShort(mData, mOffset + OFFSET_TLEN).toInt() and 0xFFFF
    }

    @Keep
    fun setTotalLength(value: Int) {
        CommonMethods.writeShort(mData, mOffset + OFFSET_TLEN, value.toShort())
    }

    @Keep
    fun getProtocol(): Byte {
        return mData[mOffset + OFFSET_PROTO]
    }

    @Keep
    fun setProtocol(value: Byte) {
        mData[mOffset + OFFSET_PROTO] = value
    }

    /** Total length - header length = payload (TCP/UDP packet) length. */
    @Keep
    fun getDataLength(): Int {
        return getTotalLength() - getHeaderLength()
    }

    @Keep
    fun getSourceIP(): Int {
        return CommonMethods.readInt(mData, mOffset + OFFSET_SRC_IP)
    }

    @Keep
    fun setSourceIP(value: Int) {
        CommonMethods.writeInt(mData, mOffset + OFFSET_SRC_IP, value)
    }

    @Keep
    fun getDestinationIP(): Int {
        return CommonMethods.readInt(mData, mOffset + OFFSET_DST_IP)
    }

    @Keep
    fun setDestinationIP(value: Int) {
        CommonMethods.writeInt(mData, mOffset + OFFSET_DST_IP, value)
    }

    override fun toString(): String {
        return "IPHeader{src=${ipToString(getSourceIP())}, dst=${ipToString(getDestinationIP())}, proto=${getProtocol()}, hlen=${getHeaderLength()}}"
    }

    @Keep
    private fun ipToString(ip: Int): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }
}
