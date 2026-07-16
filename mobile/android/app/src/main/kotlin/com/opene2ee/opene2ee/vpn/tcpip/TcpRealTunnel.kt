package com.opene2ee.opene2ee.vpn.tcpip

import com.opene2ee.opene2ee.vpn.OpenE2eeVpnService
import com.opene2ee.opene2ee.vpn.config.VpnConfiguration
import com.opene2ee.opene2ee.vpn.net.TcpSession
import com.opene2ee.opene2ee.vpn.nio.SocketNioTunnel
import com.opene2ee.opene2ee.vpn.util.VPNLogger
import com.opene2ee.opene2ee.vpn.util.closeSafely
import com.opene2ee.opene2ee.vpn.util.convertPortToInt
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

internal class TcpRealTunnel(
    override val channel: SocketChannel,
    override val selector: Selector,
    private val session: TcpSession,
    private val configuration: VpnConfiguration,
    private val vpnService: OpenE2eeVpnService
) : SocketNioTunnel() {

    private val tag = "TcpRealTunnel"
    private lateinit var proxyTunnel: TcpProxyTunnel

    fun attachProxyTunnel(t: TcpProxyTunnel) { proxyTunnel = t }

    fun connectRemoteServer() {
        if (!vpnService.protect(channel.socket())) {
            throw IllegalArgumentException("cannot protect tcp socket")
        }
        channel.configureBlocking(false)
        try {
            channel.connect(
                InetSocketAddress(
                    session.destinationAddress.stringIP,
                    session.destinationPort.port.convertPortToInt
                )
            )
        } catch (e: Exception) {
            VPNLogger.e(tag, "connect remote ${session.destinationAddress}:${session.destinationPort} failed", e)
            onException(e)
            return
        }
        selector.wakeup()
        channel.register(selector, java.nio.channels.SelectionKey.OP_CONNECT, this)
    }

    override fun onConnected() {
        VPNLogger.d(tag, "connected to ${session.destinationAddress}:${session.destinationPort}")
        prepareRead()
    }

    override fun onRead() {
        if (isClosed) return
        val buffer = ByteBuffer.allocate(configuration.mtu)
        val length = read(buffer)
        if (length < 0) {
            closeSafely()
            return
        }
        VPNLogger.d(tag, "real ${session.destinationPort} > proxy ${session.sourcePort} $length bytes")
        proxyTunnel.write(buffer)
    }

    override fun onException(t: Throwable) {
        closeSafely(this, proxyTunnel)
    }
}
