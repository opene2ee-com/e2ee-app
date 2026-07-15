// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/util/CommonMethods.kt
//
// Sprint 14 — Byte yardımcıları + IP/TCP/UDP checksum.
// Referans: huolizhuminh/NetWorkPacketCapture CommonMethods.java.

package com.opene2ee.opene2ee.vpn.util

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.tcpip.IPHeader
import com.opene2ee.opene2ee.vpn.tcpip.TCPHeader
import com.opene2ee.opene2ee.vpn.tcpip.UDPHeader

@Keep
object CommonMethods {

    @Keep
    @JvmStatic
    fun readShort(data: ByteArray, offset: Int): Short {
        return (((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)).toShort()
    }

    @Keep
    @JvmStatic
    fun writeShort(data: ByteArray, offset: Int, value: Short) {
        data[offset] = ((value.toInt() shr 8) and 0xFF).toByte()
        data[offset + 1] = (value.toInt() and 0xFF).toByte()
    }

    @Keep
    @JvmStatic
    fun readInt(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    @Keep
    @JvmStatic
    fun writeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 24) and 0xFF).toByte()
        data[offset + 1] = ((value shr 16) and 0xFF).toByte()
        data[offset + 2] = ((value shr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }

    @Keep
    @JvmStatic
    fun ipStringToInt(ip: String): Int {
        val parts = ip.split(".")
        return (parts[0].toInt() shl 24) or
                (parts[1].toInt() shl 16) or
                (parts[2].toInt() shl 8) or
                parts[3].toInt()
    }

    @Keep
    @JvmStatic
    fun ipIntToString(ip: Int): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }

    @Keep
    @JvmStatic
    fun checksum(sum: Long, buf: ByteArray, offset: Int, len: Int): Short {
        var s = sum + getSum(buf, offset, len)
        while ((s shr 16) > 0) {
            s = (s and 0xFFFF) + (s shr 16)
        }
        return (s.inv() and 0xFFFF).toShort()
    }

    @Keep
    @JvmStatic
    fun getSum(buf: ByteArray, offset: Int, len: Int): Long {
        var sum = 0L
        var off = offset
        var l = len
        while (l > 1) {
            sum += readShort(buf, off).toInt() and 0xFFFF
            off += 2
            l -= 2
        }
        if (l > 0) {
            sum += (buf[off].toInt() and 0xFF) shl 8
        }
        return sum
    }

    @Keep
    @JvmStatic
    fun ComputeIPChecksum(ipHeader: IPHeader) {
        val oldCrc = CommonMethods.readShort(ipHeader.mData, ipHeader.mOffset + IPHeader.OFFSET_CRC)
        CommonMethods.writeShort(ipHeader.mData, ipHeader.mOffset + IPHeader.OFFSET_CRC, 0)
        val newCrc = checksum(0, ipHeader.mData, ipHeader.mOffset, ipHeader.getHeaderLength())
        CommonMethods.writeShort(ipHeader.mData, ipHeader.mOffset + IPHeader.OFFSET_CRC, newCrc)
    }

    @Keep
    @JvmStatic
    fun ComputeTCPChecksum(ipHeader: IPHeader, tcpHeader: TCPHeader) {
        ComputeIPChecksum(ipHeader)
        val ipDataLen = ipHeader.getDataLength()
        if (ipDataLen < 0) return
        // Pseudo-header sum: src+dst IP (8) + protocol (1) + TCP length (2)
        var sum = getSum(ipHeader.mData, ipHeader.mOffset + IPHeader.OFFSET_SRC_IP, 8)
        sum += ipHeader.getProtocol().toInt() and 0xFF
        sum += ipDataLen
        val oldCrc = CommonMethods.readShort(tcpHeader.mData, tcpHeader.mOffset + TCPHeader.OFFSET_CRC)
        CommonMethods.writeShort(tcpHeader.mData, tcpHeader.mOffset + TCPHeader.OFFSET_CRC, 0)
        val newCrc = checksum(sum, tcpHeader.mData, tcpHeader.mOffset, ipDataLen)
        CommonMethods.writeShort(tcpHeader.mData, tcpHeader.mOffset + TCPHeader.OFFSET_CRC, newCrc)
    }

    @Keep
    @JvmStatic
    fun ComputeUDPChecksum(ipHeader: IPHeader, udpHeader: UDPHeader) {
        ComputeIPChecksum(ipHeader)
        val ipDataLen = ipHeader.getDataLength()
        if (ipDataLen < 0) return
        var sum = getSum(ipHeader.mData, ipHeader.mOffset + IPHeader.OFFSET_SRC_IP, 8)
        sum += ipHeader.getProtocol().toInt() and 0xFF
        sum += ipDataLen
        val oldCrc = CommonMethods.readShort(udpHeader.mData, udpHeader.mOffset + UDPHeader.OFFSET_CRC)
        CommonMethods.writeShort(udpHeader.mData, udpHeader.mOffset + UDPHeader.OFFSET_CRC, 0)
        val newCrc = checksum(sum, udpHeader.mData, udpHeader.mOffset, ipDataLen)
        CommonMethods.writeShort(udpHeader.mData, udpHeader.mOffset + UDPHeader.OFFSET_CRC, newCrc)
    }
}
