package com.opene2ee.opene2ee.vpn.udp

import com.opene2ee.opene2ee.vpn.OpenE2eeVpnService
import com.opene2ee.opene2ee.vpn.PacketInterceptor
import com.opene2ee.opene2ee.vpn.config.VpnConfiguration
import com.opene2ee.opene2ee.vpn.net.IPHeader
import com.opene2ee.opene2ee.vpn.net.Packet
import com.opene2ee.opene2ee.vpn.net.UdpSessionStore
import com.opene2ee.opene2ee.vpn.util.VPNLogger
import java.io.OutputStream

internal class UdpPacketInterceptor(
    configuration: VpnConfiguration,
    proxyService: OpenE2eeVpnService
) : PacketInterceptor {

    private val tag = "UdpPacketInterceptor"
    private val sessionStore: UdpSessionStore = UdpSessionStore()
    private val proxyServer = UdpProxyServer(sessionStore, configuration, proxyService).apply { dispatch() }

    override fun intercept(ipHeader: IPHeader, packet: Packet, outputStream: OutputStream) {
        val udpHeader = UdpHeader(ipHeader, packet.packet, ipHeader.headerLength)
        val sourceAddress = ipHeader.sourceAddress
        val sourcePort = udpHeader.sourcePort
        val destinationAddress = ipHeader.destinationAddress
        val destinationPort = udpHeader.destinationPort

        val session = sessionStore.insert(sourceAddress, sourcePort, destinationAddress, destinationPort)
        VPNLogger.d(tag, "udp session start $session")
        proxyServer.proxy(udpHeader, outputStream)
    }
}
