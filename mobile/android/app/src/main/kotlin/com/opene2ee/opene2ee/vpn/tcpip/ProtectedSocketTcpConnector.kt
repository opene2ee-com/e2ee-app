package com.opene2ee.opene2ee.vpn.tcpip

import android.net.Network
import android.net.VpnService
import com.opene2ee.opene2ee.vpn.util.VPNLogger
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Opens the remote half of a TUN TCP flow outside the VPN routing loop. */
internal class ProtectedSocketTcpConnector(
    private val vpnService: VpnService,
    private val underlyingNetwork: Network,
    private val executor: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "vpn-tcp-remote").apply { isDaemon = true }
    },
) : TcpConnector, AutoCloseable {
    override fun connect(destination: TcpDestination, listener: TcpConnectionListener): TcpConnection {
        // Create the socket from the physical Network so its netId is present
        // from birth; it remains unconnected for the protect → connect boundary.
        val socket = underlyingNetwork.socketFactory.createSocket()
        if (!vpnService.protect(socket)) {
            socket.close()
            throw IllegalStateException("cannot protect tcp socket")
        }

        val connection = SocketConnection(socket)
        executor.execute {
            try {
                socket.connect(InetSocketAddress(destination.address, destination.port), CONNECT_TIMEOUT_MS)
                connection.connected()
                VPNLogger.i(TAG, "tcp connected remotePort=${destination.port}")
                listener.onConnected()
                val input = socket.getInputStream()
                val buffer = ByteArray(MAX_TCP_PAYLOAD)
                while (!socket.isClosed) {
                    val length = input.read(buffer)
                    if (length < 0) break
                    if (length > 0) listener.onData(buffer.copyOf(length))
                }
                listener.onClosed()
            } catch (cause: Throwable) {
                VPNLogger.w(
                    TAG,
                    "remote tcp port=${destination.port} failed: ${cause.javaClass.simpleName}: ${cause.message}",
                )
                listener.onFailure(cause)
            } finally {
                connection.close()
            }
        }
        VPNLogger.i(TAG, "tcp connect scheduled remotePort=${destination.port}")
        return connection
    }

    override fun close() {
        executor.shutdownNow()
    }

    private class SocketConnection(private val socket: Socket) : TcpConnection {
        private val lock = Any()
        private val pending = ArrayDeque<ByteArray>()
        private var output: OutputStream? = null
        private var pendingBytes = 0

        fun connected() = synchronized(lock) {
            if (socket.isClosed) return@synchronized
            val target = socket.getOutputStream()
            output = target
            while (pending.isNotEmpty()) {
                val payload = pending.removeFirst()
                pendingBytes -= payload.size
                target.write(payload)
            }
            target.flush()
        }

        override fun write(payload: ByteArray) = synchronized(lock) {
            if (socket.isClosed) return@synchronized
            val target = output
            if (target != null) {
                target.write(payload)
                target.flush()
            } else {
                check(pendingBytes + payload.size <= MAX_PENDING_BYTES) {
                    "tcp connect queue exceeded $MAX_PENDING_BYTES bytes"
                }
                pending.addLast(payload)
                pendingBytes += payload.size
            }
        }

        override fun shutdownOutput() = synchronized(lock) {
            if (!socket.isClosed && socket.isConnected && !socket.isOutputShutdown) socket.shutdownOutput()
        }

        override fun close() = synchronized(lock) {
            pending.clear()
            pendingBytes = 0
            output = null
            socket.close()
        }
    }

    private companion object {
        const val TAG = "ProtectedTcpConnector"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val MAX_TCP_PAYLOAD = 1_360
        const val MAX_PENDING_BYTES = 256 * 1024
    }
}
