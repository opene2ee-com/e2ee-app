// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/TcpProxyServer.kt
//
// Sprint 14 — Local TCP proxy (loopback ephemeral port).
// Referans: huolizhuminh/NetWorkPacketCapture TcpProxyServer.java + Sprint 13.0-fix portKey/port bug fix.

package com.opene2ee.opene2ee.vpn.proxy

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.OpenE2eeVpnService
import com.opene2ee.opene2ee.vpn.nat.NatSession
import com.opene2ee.opene2ee.vpn.nat.NatSessionManager
import com.opene2ee.opene2ee.vpn.util.VPNLog
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors

@Keep
class TcpProxyServer(private val initialPort: Int) {

    @Keep
    companion object {
        private const val TAG = "TcpProxyServer"
    }

    @Keep
    @Volatile
    var isStopped: Boolean = false
        private set

    @Keep
    @Volatile
    var port: Int = 0
        private set

    @Keep
    private var serverSocket: ServerSocket? = null

    @Keep
    private val workerPool = Executors.newCachedThreadPool { r ->
        Thread(r, "TcpTunnel-Worker-${System.nanoTime()}").apply { isDaemon = true }
    }

    @Keep
    fun start() {
        serverSocket = ServerSocket()
        // KURAL 7 (Sprint 15): bind wildcard 0.0.0.0, ASLA InetAddress.getLoopbackAddress()
        // TUN'dan gelen rewritten packet dst=10.0.0.2:PROXY_PORT; 127.0.0.1 listener'da
        // kernel SYN'i teslim edemez (RST exchange = "to net=23, from net=16" logu).
        // 0.0.0.0 = tüm local IP'ler (127.0.0.1, 10.0.0.2) için kabul eder.
        // Referans: huolizhuminh/NetWorkPacketCapture TcpProxyServer.java:46 (wildcard bind).
        serverSocket!!.bind(InetSocketAddress(initialPort))  // 0.0.0.0 wildcard
        this.port = serverSocket!!.localPort
        VPNLog.d(TAG, "TcpProxyServer started, listening on 0.0.0.0:${this.port}")

        val acceptThread = Thread({
            try {
                while (!isStopped) {
                    val clientSocket: Socket = try {
                        serverSocket!!.accept()
                    } catch (e: SocketException) {
                        if (isStopped) break else throw e
                    }
                    handleNewClient(clientSocket)
                }
            } catch (e: Exception) {
                VPNLog.e(TAG, "accept loop exception: ${e.message}", e)
            }
        }, "TcpProxyServer-Accept").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Yeni accept edilen client'ı handle et.
     *
     * **KURAL 2 + 3** (KESİNLİKLE uyulacak):
     * - `readFirstPacket` / `parseFirstPacket` KULLANMA (kernel strip eder)
     * - `portKey = clientSocket.port` (remote/peer port = app's source port)
     *   ASLA `clientSocket.localPort` (proxy's ephemeral port, NAT key DEĞİL)
     */
    @Keep
    private fun handleNewClient(clientSocket: Socket) {
        val vpnService = OpenE2eeVpnService.activeInstance
        if (vpnService == null) {
            VPNLog.e(TAG, "OpenE2eeVpnService.activeInstance == null")
            try { clientSocket.close() } catch (_: Exception) {}
            return
        }

        // protect() — VPN TUN'u bypass, gerçek NIC kullan
        if (!vpnService.protect(clientSocket)) {
            VPNLog.e(TAG, "protect() returned false for client socket")
            try { clientSocket.close() } catch (_: Exception) {}
            return
        }
        VPNLog.d(TAG, "protect() returned true for client socket")

        // ───── KURAL 3: portKey = clientSocket.port ─────
        // java.net.Socket.getPort() = the remote port the socket is connected to.
        // Loopback'te proxy'nin "remote"'ı = app. Bu app'in source port'u.
        val portKey = clientSocket.port
        val session: NatSession? = NatSessionManager.getSession(portKey)
        if (session == null) {
            VPNLog.w(TAG, "No session for portKey=$portKey (VpnService did not register this TCP flow)")
            try { clientSocket.close() } catch (_: Exception) {}
            return
        }
        val remoteIp = session.remoteIp
        val remotePort = session.remotePort
        VPNLog.d(TAG, "handleNewClient: portKey=$portKey -> ${ipToString(remoteIp)}:$remotePort")

        // Remote'a bağlan (yine protect gerekli)
        val remoteSocket = Socket()
        if (!vpnService.protect(remoteSocket)) {
            VPNLog.e(TAG, "protect() returned false for remote socket")
            try { clientSocket.close() } catch (_: Exception) {}
            try { remoteSocket.close() } catch (_: Exception) {}
            return
        }
        try {
            val remoteAddr = InetAddress.getByAddress(
                byteArrayOf(
                    ((remoteIp shr 24) and 0xFF).toByte(),
                    ((remoteIp shr 16) and 0xFF).toByte(),
                    ((remoteIp shr 8) and 0xFF).toByte(),
                    (remoteIp and 0xFF).toByte()
                )
            )
            remoteSocket.connect(InetSocketAddress(remoteAddr, remotePort), 10_000)
            VPNLog.d(TAG, "connect() returned for ${ipToString(remoteIp)}:$remotePort")
        } catch (e: IOException) {
            VPNLog.e(TAG, "connect to ${ipToString(remoteIp)}:$remotePort failed: ${e.message}")
            try { clientSocket.close() } catch (_: Exception) {}
            try { remoteSocket.close() } catch (_: Exception) {}
            return
        }

        val tunnel = TcpTunnel(clientSocket, remoteSocket, portKey)
        workerPool.execute(tunnel)
    }

    @Keep
    fun stop() {
        isStopped = true
        try { serverSocket?.close() } catch (_: Exception) {}
        workerPool.shutdownNow()
        VPNLog.d(TAG, "TcpProxyServer stopped")
    }

    @Keep
    private fun ipToString(ip: Int): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }
}
