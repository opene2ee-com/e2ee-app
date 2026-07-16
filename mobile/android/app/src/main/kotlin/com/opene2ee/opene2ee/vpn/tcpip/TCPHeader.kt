// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/tcpip/TCPHeader.kt
//
// Sprint 14 — TCP header parser/mutator.
// Referans: huolizhuminh/NetWorkPacketCapture TCPHeader.java.

package com.opene2ee.opene2ee.vpn.tcpip

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.util.CommonMethods

@Keep
class TCPHeader {
    @Keep
    companion object {
        const val OFFSET_SRC_PORT = 0
        const val OFFSET_DST_PORT = 2
        const val OFFSET_SEQ = 4
        const val OFFSET_ACK = 8
        const val OFFSET_LEN_RES = 12      // 4-bit length + 4-bit reserved
        const val OFFSET_FLAG = 13
        const val OFFSET_WIN = 14
        const val OFFSET_CRC = 16
        const val OFFSET_URP = 18

        const val FIN: Int = 1
        const val SYN: Int = 2
        const val RST: Int = 4
        const val PSH: Int = 8
        const val ACK: Int = 16
        const val URG: Int = 32
    }

    @Keep var mData: ByteArray = ByteArray(0)
    @Keep var mOffset: Int = 0

    @Keep constructor()

    @Keep
    constructor(data: ByteArray, offset: Int) {
        mData = data
        mOffset = offset
    }

    @Keep
    fun getHeaderLength(): Int {
        val lenres = mData[mOffset + OFFSET_LEN_RES].toInt() and 0xFF
        return (lenres shr 4) * 4
    }

    @Keep
    fun getSourcePort(): Int {
        return CommonMethods.readShort(mData, mOffset + OFFSET_SRC_PORT).toInt() and 0xFFFF
    }

    @Keep
    fun setSourcePort(value: Int) {
        CommonMethods.writeShort(mData, mOffset + OFFSET_SRC_PORT, (value and 0xFFFF).toShort())
    }

    @Keep
    fun getDestinationPort(): Int {
        return CommonMethods.readShort(mData, mOffset + OFFSET_DST_PORT).toInt() and 0xFFFF
    }

    @Keep
    fun setDestinationPort(value: Int) {
        CommonMethods.writeShort(mData, mOffset + OFFSET_DST_PORT, (value and 0xFFFF).toShort())
    }

    @Keep
    fun getFlag(): Byte {
        return mData[mOffset + OFFSET_FLAG]
    }

    override fun toString(): String {
        val f = getFlag().toInt() and 0xFF
        return "TCPHeader{sp=${getSourcePort()}, dp=${getDestinationPort()}, flag=${
            (if (f and SYN != 0) "SYN" else "") +
            (if (f and ACK != 0) "ACK" else "") +
            (if (f and PSH != 0) "PSH" else "") +
            (if (f and FIN != 0) "FIN" else "") +
            (if (f and RST != 0) "RST" else "")
        }}"
    }
}
