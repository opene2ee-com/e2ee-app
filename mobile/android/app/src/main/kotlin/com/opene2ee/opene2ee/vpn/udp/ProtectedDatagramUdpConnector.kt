package com.opene2ee.opene2ee.vpn.udp

import android.net.Network
import android.net.VpnService
import com.opene2ee.opene2ee.vpn.util.VPNLogger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Opens per-flow datagram sockets outside the VPN routing loop. */
internal class ProtectedDatagramUdpConnector(
    private val vpnService: VpnService,
    private val underlyingNetwork: Network,
    private val executor: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "vpn-udp-remote").apply { isDaemon = true }
    },
) : UdpConnector, AutoCloseable {
    private val sockets = ConcurrentHashMap.newKeySet<DatagramSocket>()

    override fun connect(
        destination: UdpDestination,
        listener: UdpConnectionListener,
    ): UdpConnection {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        underlyingNetwork.bindSocket(socket)
        if (!vpnService.protect(socket)) {
            socket.close()
            throw IllegalStateException("cannot protect udp socket")
        }
        socket.connect(InetSocketAddress(destination.address, destination.port))
        sockets += socket
        val connection = SocketConnection(socket)
        executor.execute {
            val buffer = ByteArray(MAX_UDP_PAYLOAD)
            try {
                while (!socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (packet.length > 0) listener.onData(packet.data.copyOf(packet.length))
                }
            } catch (cause: Throwable) {
                if (!socket.isClosed) {
                    VPNLogger.w(
                        TAG,
                        "remote udp port=${destination.port} failed: ${cause.javaClass.simpleName}: ${cause.message}",
                    )
                    listener.onFailure(cause)
                }
            } finally {
                connection.close()
            }
        }
        VPNLogger.i(TAG, "udp connected remotePort=${destination.port}")
        return connection
    }

    override fun close() {
        sockets.toList().forEach { it.close() }
        sockets.clear()
        executor.shutdownNow()
    }

    private inner class SocketConnection(private val socket: DatagramSocket) : UdpConnection {
        override fun send(payload: ByteArray) {
            socket.send(DatagramPacket(payload, payload.size))
        }

        override fun close() {
            sockets.remove(socket)
            socket.close()
        }
    }

    private companion object {
        const val TAG = "ProtectedUdpConnector"
        const val MAX_UDP_PAYLOAD = 65_507
    }
}
