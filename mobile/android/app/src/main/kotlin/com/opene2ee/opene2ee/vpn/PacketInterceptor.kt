package com.opene2ee.opene2ee.vpn

import com.opene2ee.opene2ee.vpn.net.IPHeader
import com.opene2ee.opene2ee.vpn.net.Packet
import java.io.OutputStream

interface PacketInterceptor {
    fun intercept(ipHeader: IPHeader, packet: Packet, outputStream: OutputStream)
}
