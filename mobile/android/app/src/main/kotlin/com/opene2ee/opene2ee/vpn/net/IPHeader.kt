package com.opene2ee.opene2ee.vpn.net

import com.opene2ee.opene2ee.vpn.util.VPNLogger
import com.opene2ee.opene2ee.vpn.util.readByte

interface IPHeader {
    val ipVersion: IPVersion
    val dataProtocol: Byte
    val headerLength: Int
    val dataLength: Int
    var totalLength: Int
    var sourceAddress: IpAddress
    var destinationAddress: IpAddress
    val addressSum: Int
    fun notifyCheckSum()

    companion object {
        private const val TAG = "IPHeader"
        private const val VERSION_4 = 0b0100
        private const val VERSION_6 = 0b0110

        fun parse(packet: ByteArray, length: Int, offset: Int): IPHeader? {
            val ipVersion = packet.readByte(offset).toInt() ushr 4
            return when (ipVersion) {
                VERSION_4 -> {
                    if (length < IPv4Header.MIN_IPV4_LENGTH) {
                        VPNLogger.w(TAG, "IPv4 len($length) < min(${IPv4Header.MIN_IPV4_LENGTH})")
                        null
                    } else IPv4Header(packet, 0)
                }
                VERSION_6 -> {
                    if (length < IPv6Header.IPV6_STANDARD_LENGTH) {
                        VPNLogger.w(TAG, "IPv6 len($length) < min(${IPv6Header.IPV6_STANDARD_LENGTH})")
                        null
                    } else IPv6Header(packet, 0)
                }
                else -> {
                    VPNLogger.w(TAG, "unknown IP version 0b${ipVersion.toString(2)}")
                    null
                }
            }
        }
    }
}
