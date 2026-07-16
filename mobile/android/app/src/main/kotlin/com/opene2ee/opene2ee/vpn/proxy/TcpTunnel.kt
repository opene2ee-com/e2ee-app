// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/TcpTunnel.kt
//
// Sprint 14 — TCP tunnel (clientSocket ↔ remoteSocket bidirectional pipe).
// Referans: huolizhuminh/NetWorkPacketCapture TcpTunnel.java (NIO) → Sprint 14 raw Socket+Thread.

package com.opene2ee.opene2ee.vpn.proxy

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.nat.NatSessionManager
import com.opene2ee.opene2ee.vpn.util.VPNLog
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Keep
class TcpTunnel(
    private val clientSocket: Socket,
    private val remoteSocket: Socket,
    private val portKey: Int
) : Runnable {
    @Keep
    companion object {
        private const val TAG = "TcpTunnel"
    }

    @Keep
    private val stopped = AtomicBoolean(false)

    @Keep
    override fun run() {
        try {
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()
            val remoteIn = remoteSocket.getInputStream()
            val remoteOut = remoteSocket.getOutputStream()

            // 2 thread: client→remote ve remote→client eşzamanlı
            val forwardExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "TcpTunnel-Forward-$portKey").apply { isDaemon = true }
            }
            val reverseExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "TcpTunnel-Reverse-$portKey").apply { isDaemon = true }
            }

            // Client → Remote (app bize yazıyor, biz remote'a yazıyoruz)
            forwardExecutor.execute {
                try {
                    val buf = ByteArray(8192)
                    while (!stopped.get()) {
                        val n = clientIn.read(buf)
                        if (n <= 0) break
                        remoteOut.write(buf, 0, n)
                        remoteOut.flush()
                    }
                } catch (e: Exception) {
                    VPNLog.d(TAG, "forward read/write exception: ${e.message}")
                } finally {
                    dispose()
                }
            }

            // Remote → Client (remote bize yazıyor, biz app'e yazıyoruz)
            reverseExecutor.execute {
                try {
                    val buf = ByteArray(8192)
                    while (!stopped.get()) {
                        val n = remoteIn.read(buf)
                        if (n <= 0) break
                        clientOut.write(buf, 0, n)
                        clientOut.flush()
                    }
                } catch (e: Exception) {
                    VPNLog.d(TAG, "reverse read/write exception: ${e.message}")
                } finally {
                    dispose()
                }
            }
        } catch (e: Exception) {
            VPNLog.e(TAG, "TcpTunnel run exception: ${e.message}", e)
            dispose()
        }
    }

    @Keep
    fun dispose() {
        if (stopped.compareAndSet(false, true)) {
            try { clientSocket.close() } catch (_: Exception) {}
            try { remoteSocket.close() } catch (_: Exception) {}
            NatSessionManager.removeSession(portKey)
            VPNLog.d(TAG, "TcpTunnel disposed: portKey=$portKey")
        }
    }
}
