package com.opene2ee.opene2ee.vpn.udp

import com.opene2ee.opene2ee.vpn.OpenE2eeVpnService
import com.opene2ee.opene2ee.vpn.config.VpnConfiguration
import com.opene2ee.opene2ee.vpn.net.Port
import com.opene2ee.opene2ee.vpn.net.UdpSessionStore
import com.opene2ee.opene2ee.vpn.proxy.NioProxyServer
import com.opene2ee.opene2ee.vpn.util.VPNLogger
import com.opene2ee.opene2ee.vpn.util.closeSafely
import com.opene2ee.opene2ee.vpn.util.convertPortToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.nio.channels.DatagramChannel
import java.nio.channels.Selector

internal class UdpProxyServer(
    private val sessionStore: UdpSessionStore,
    private val configuration: VpnConfiguration,
    private val proxyService: OpenE2eeVpnService
) : NioProxyServer() {

    private val tag = "UdpProxyServer"
    override val selector: Selector = Selector.open()
    private val tunnels = hashMapOf<Port, UdpRealTunnel>()

    fun proxy(udpHeader: UdpHeader, outputStream: OutputStream) {
        launch(Dispatchers.IO) {
            val sourcePort = udpHeader.sourcePort
            try {
                val tunnel = tunnels[sourcePort] ?: createTunnel(udpHeader, outputStream)
                tunnel.write(udpHeader.data)
            } catch (e: Exception) {
                tunnels.remove(sourcePort)?.closeSafely()
                VPNLogger.e(tag, "udp proxy error", e)
            }
        }
    }

    private fun createTunnel(udpHeader: UdpHeader, outputStream: OutputStream): UdpRealTunnel {
        val session = sessionStore.query(udpHeader.sourcePort) ?: error("no udp session")
        VPNLogger.d(tag, "create udp tunnel for $session")
        return UdpRealTunnel(
            DatagramChannel.open(), selector, outputStream, session, udpHeader, configuration, proxyService
        ).also {
            it.connectRemoteServer(
                udpHeader.ipHeader.destinationAddress.stringIP,
                udpHeader.destinationPort.port.convertPortToInt
            )
            tunnels[session.sourcePort] = it
        }
    }

    override fun release() {
        for (t in tunnels.values) t.closeSafely()
        tunnels.clear()
    }
}
