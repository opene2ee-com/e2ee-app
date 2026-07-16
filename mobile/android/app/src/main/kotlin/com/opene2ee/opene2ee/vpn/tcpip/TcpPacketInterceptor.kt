package com.opene2ee.opene2ee.vpn.tcpip

import com.opene2ee.opene2ee.vpn.OpenE2eeVpnService
import com.opene2ee.opene2ee.vpn.PacketInterceptor
import com.opene2ee.opene2ee.vpn.config.VpnConfiguration
import com.opene2ee.opene2ee.vpn.net.IPHeader
import com.opene2ee.opene2ee.vpn.net.IPVersion
import com.opene2ee.opene2ee.vpn.net.IpAddress
import com.opene2ee.opene2ee.vpn.net.Packet
import com.opene2ee.opene2ee.vpn.net.Port
import com.opene2ee.opene2ee.vpn.net.TcpSessionStore
import com.opene2ee.opene2ee.vpn.util.VPNLogger
import com.opene2ee.opene2ee.vpn.util.convertPortToInt
import java.io.OutputStream

/**
 * Intercepts IP packets whose protocol = TCP, NAT-rewrites
 * src/dst IP+port, then writes the rewritten packet to the
 * TUN output stream. The TcpProxyServer (running on an NIO
 * Selector) accepts the rewritten packet and bridges to the
 * remote server via TcpRealTunnel.
 */
internal class TcpPacketInterceptor(
    private val configuration: VpnConfiguration,
    private val proxyService: OpenE2eeVpnService
) : PacketInterceptor {

    private val tag = "TcpPacketInterceptor"
    private val sessionStore: TcpSessionStore = TcpSessionStore()

    private val tunIPv4Address = IpAddress(configuration.ipv4Address, IPVersion.IPv4)

    internal val ports = hashSetOf<Port>()
    private val servers = mutableListOf<TcpProxyServer>().also { list ->
        // Sprint 16: tek TcpProxyServer yeterli (Sprint 14'te 4 concurrent TCP server vardı, gereksiz).
        val server = TcpProxyServer(sessionStore, configuration, proxyService)
        server.dispatch()
        ports.add(server.proxyServerPort)
        list.add(server)
    }

    override fun intercept(ipHeader: IPHeader, packet: Packet, outputStream: OutputStream) {
        if (ipHeader.ipVersion == IPVersion.IPv6 && !configuration.enableIPv6) {
            VPNLogger.e(tag, "IPv6 disabled")
            return
        }
        val tcpHeader = TcpHeader(ipHeader, packet.packet, ipHeader.headerLength)
        val sourcePort = tcpHeader.sourcePort
        val sourceAddress = ipHeader.sourceAddress
        val destinationAddress = ipHeader.destinationAddress
        val destinationPort = tcpHeader.destinationPort

        if (!ports.contains(sourcePort)) {
            // İstek paketi: app → remote server (rewrite ediyoruz)
            sessionStore.insert(sourceAddress, sourcePort, destinationAddress, destinationPort)
            val proxyServerPort = servers[0].proxyServerPort
            ipHeader.sourceAddress = destinationAddress
            ipHeader.destinationAddress = tunIPv4Address
            tcpHeader.destinationPort = proxyServerPort
        } else {
            // Yanıt paketi: proxy server → app (rewrite ediyoruz)
            val session = sessionStore.query(destinationPort) ?: return
            ipHeader.sourceAddress = destinationAddress
            tcpHeader.sourcePort = session.destinationPort
            ipHeader.destinationAddress = tunIPv4Address
        }

        ipHeader.notifyCheckSum()
        tcpHeader.notifyCheckSum()
        outputStream.write(packet.packet, 0, packet.length)
    }
}
