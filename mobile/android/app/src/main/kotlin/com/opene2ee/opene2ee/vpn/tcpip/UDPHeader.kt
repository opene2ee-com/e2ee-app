// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/tcpip/UDPHeader.kt
//
// Sprint 14 — UDP header parser/mutator.
// Referans: huolizhuminh/NetWorkPacketCapture UDPHeader.java.

package com.opene2ee.opene2ee.vpn.tcpip

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.util.CommonMethods

@Keep
class UDPHeader {
    @Keep
    companion object {
        const val OFFSET_SRC_PORT = 0
        const val OFFSET_DST_PORT = 2
        const val OFFSET_TLEN = 4
        const val OFFSET_CRC = 6
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
    fun getHeaderLength(): Int = 8  // UDP header always 8 bytes

    @Keep
    fun getTotalLength(): Int {
        return CommonMethods.readShort(mData, mOffset + OFFSET_TLEN).toInt() and 0xFFFF
    }

    override fun toString(): String {
        return "UDPHeader{sp=${getSourcePort()}, dp=${getDestinationPort()}}"
    }
}
