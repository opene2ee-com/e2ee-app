package com.opene2ee.opene2ee.vpn.udp

import android.net.VpnService
import com.opene2ee.opene2ee.vpn.config.VpnConfiguration
import com.opene2ee.opene2ee.vpn.net.IPHeader
import com.opene2ee.opene2ee.vpn.net.UdpSession
import com.opene2ee.opene2ee.vpn.nio.DatagramSocketNioTunnel
import com.opene2ee.opene2ee.vpn.util.VPNLogger
import com.opene2ee.opene2ee.vpn.util.closeSafely
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.Selector

internal class UdpRealTunnel(
    override val channel: DatagramChannel,
    override val selector: Selector,
    private val outputStream: OutputStream,
    private val session: UdpSession,
    udpHeader: UdpHeader,
    private val configuration: VpnConfiguration,
    private val vpnService: VpnService
) : DatagramSocketNioTunnel() {

    private val tag = "UdpRealTunnel"
    private val header: UdpHeader = udpHeader.copy().also {
        val localAddress = it.ipHeader.sourceAddress
        val localPort = it.sourcePort
        val remoteAddress = it.ipHeader.destinationAddress
        val remotePort = it.destinationPort
        it.ipHeader.sourceAddress = remoteAddress
        it.sourcePort = remotePort
        it.ipHeader.destinationAddress = localAddress
        it.destinationPort = localPort
    }

    fun connectRemoteServer(address: String, port: Int) {
        if (!vpnService.protect(channel.socket())) {
            throw IllegalArgumentException("cannot protect udp socket")
        }
        channel.configureBlocking(false)
        try {
            channel.connect(InetSocketAddress(address, port))
        } catch (e: Exception) {
            VPNLogger.e(tag, "udp connect $address:$port failed", e)
            onException(e)
            return
        }
        prepareRead()
    }

    override fun onRead() {
        val buffer = ByteBuffer.allocate(configuration.mtu)
        val length = read(buffer)
        if (length < 0) {
            closeSafely()
            return
        }
        outputStream.write(createUdpMessage(buffer))
    }

    private fun createUdpMessage(buffer: ByteBuffer): ByteArray {
        val arrayLength = header.ipHeader.headerLength + 8 + buffer.remaining()
        val packet = ByteArray(arrayLength) { i ->
            if (i < header.ipHeader.headerLength + 8) {
                header.packet[i]
            } else {
                buffer[i - header.ipHeader.headerLength - 8]
            }
        }
        val ipHeader = IPHeader.parse(packet, arrayLength, 0)!!
        val udpHeader = UdpHeader(ipHeader, packet, ipHeader.headerLength)
        ipHeader.totalLength = arrayLength
        udpHeader.totalLength = arrayLength - ipHeader.headerLength
        ipHeader.notifyCheckSum()
        udpHeader.notifyCheckSum()
        return packet
    }

    override fun onException(t: Throwable) { close() }
    override fun close() { super.close() }
}
