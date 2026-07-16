package com.opene2ee.opene2ee.vpn.tcpip

import com.opene2ee.opene2ee.vpn.OpenE2eeVpnService
import com.opene2ee.opene2ee.vpn.config.VpnConfiguration
import com.opene2ee.opene2ee.vpn.net.Port
import com.opene2ee.opene2ee.vpn.net.TcpSessionStore
import com.opene2ee.opene2ee.vpn.nio.NioCallback
import com.opene2ee.opene2ee.vpn.nio.SocketNioTunnel
import com.opene2ee.opene2ee.vpn.proxy.NioProxyServer
import com.opene2ee.opene2ee.vpn.util.VPNLogger
import com.opene2ee.opene2ee.vpn.util.closeSafely
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

/**
 * TCP proxy server — listens on a random local port. When the
 * TcpPacketInterceptor rewrites the app's outgoing packet's
 * destination port to this server's port, the OS routes it
 * to this ServerSocketChannel (because the packet's dest IP
 * was rewritten to the TUN address 10.1.10.1). The accept()
 * gives us a SocketChannel talking back to the TUN; we then
 * open a real SocketChannel to the original destination and
 * bridge bytes in both directions.
 */
internal class TcpProxyServer(
    private val sessionStore: TcpSessionStore,
    private val configuration: VpnConfiguration,
    private val proxyService: OpenE2eeVpnService
) : NioProxyServer(), NioCallback {

    private val tag = "TcpProxyServer"

    val proxyServerPort: Port

    override val selector: Selector = Selector.open()

    private val proxyServerSocketChannel = ServerSocketChannel.open().apply {
        configureBlocking(false)
        socket().bind(InetSocketAddress(0))
        // KURAL 6: register sonrası attach ZORUNLU
        register(selector, SelectionKey.OP_ACCEPT, this@TcpProxyServer)
        proxyServerPort = Port(socket().localPort.toShort())
    }

    override fun onAccept() {
        val proxySocketChannel = proxyServerSocketChannel.accept()
        val proxySocket = proxySocketChannel.socket()
        // KURAL 5: sourcePort = proxySocket.port (NOT localPort)
        val sourcePort = Port(proxySocket.port.toShort())
        val session = sessionStore.query(sourcePort) ?: return

        VPNLogger.d(tag, "create tunnel for session $session")

        val proxyTunnel = TcpProxyTunnel(proxySocketChannel, selector, proxyServerPort, session, configuration)
        val realTunnel = TcpRealTunnel(SocketChannel.open(), selector, session, configuration, proxyService)
        proxyTunnel.attachRealChannel(realTunnel)
        realTunnel.attachProxyTunnel(proxyTunnel)
        realTunnel.connectRemoteServer()
    }

    override fun onConnected() {}
    override fun onRead() {}
    override fun onWrite(): Int = -1
    override fun onException(t: Throwable) {}

    override fun release() {
        proxyServerSocketChannel.closeSafely()
    }
}
