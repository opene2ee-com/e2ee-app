package com.opene2ee.opene2ee.vpn.tcpip

import com.opene2ee.opene2ee.vpn.config.VpnConfiguration
import com.opene2ee.opene2ee.vpn.net.Port
import com.opene2ee.opene2ee.vpn.net.TcpSession
import com.opene2ee.opene2ee.vpn.nio.SocketNioTunnel
import com.opene2ee.opene2ee.vpn.util.VPNLogger
import com.opene2ee.opene2ee.vpn.util.closeSafely
import java.nio.ByteBuffer
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

internal class TcpProxyTunnel(
    override val channel: SocketChannel,
    override val selector: Selector,
    val port: Port,
    private val session: TcpSession,
    private val configuration: VpnConfiguration
) : SocketNioTunnel() {

    private val tag = "TcpProxyTunnel"
    private lateinit var realTunnel: TcpRealTunnel

    fun attachRealChannel(real: TcpRealTunnel) { realTunnel = real }

    override fun onConnected() { prepareRead() }

    override fun onRead() {
        if (isClosed) return
        val buffer = ByteBuffer.allocate(configuration.mtu)
        val length = read(buffer)
        if (length < 0 || realTunnel.isClosed) {
            closeSafely()
            return
        }
        VPNLogger.d(tag, "proxy $port > real ${session.destinationPort} $length bytes")
        realTunnel.write(buffer)
    }

    override fun onException(t: Throwable) {
        closeSafely(this, realTunnel)
    }
}
