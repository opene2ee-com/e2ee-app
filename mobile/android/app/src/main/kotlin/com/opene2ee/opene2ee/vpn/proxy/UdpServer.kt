// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/UdpServer.kt
//
// Sprint 14 — UDP relay (NIO Selector-based).
// Referans: huolizhuminh/NetWorkPacketCapture UDPServer.java + Sprint 13.0-fix key.attach fix.

package com.opene2ee.opene2ee.vpn.proxy

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.OpenE2eeVpnService
import com.opene2ee.opene2ee.vpn.Packet
import com.opene2ee.opene2ee.vpn.net.MyLRUCache
import com.opene2ee.opene2ee.vpn.net.VPNConstants
import com.opene2ee.opene2ee.vpn.nat.NatSession
import com.opene2ee.opene2ee.vpn.nat.NatSessionManager
import com.opene2ee.opene2ee.vpn.util.VPNLog
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue

@Keep
class UdpServer(
    private val vpnService: OpenE2eeVpnService,
    private val responseQueue: ConcurrentLinkedQueue<Packet>
) {

    @Keep
    companion object {
        private const val TAG = "UdpServer"
        private const val MAX_UDP_TUNNELS = 64
    }

    @Keep
    @Volatile
    private var isRunning: Boolean = false

    @Keep
    @Volatile
    private var selector: Selector? = null

    @Keep
    private val tunnelCache = MyLRUCache<Int, UdpTunnel>(MAX_UDP_TUNNELS)

    @Keep
    fun start() {
        isRunning = true
        selector = Selector.open()
        val thread = Thread({ runLoop() }, "UdpServer-Selector").apply {
            isDaemon = true
            start()
        }
        VPNLog.d(TAG, "UdpServer started")
    }

    /**
     * OpenE2eeVpnService.onUdpPacketReceived'dan çağrılır.
     * İlk paket: yeni tunnel oluştur. Sonraki paketler: mevcut tunnel'a yaz.
     */
    @Keep
    fun processUdpPacket(packet: Packet, portKey: Int) {
        val tunnel = tunnelCache[portKey]
        if (tunnel == null) {
            val session: NatSession = NatSessionManager.getSession(portKey) ?: return
            val newTunnel = initConnection(session, portKey, packet)
            if (newTunnel != null) {
                tunnelCache[portKey] = newTunnel
                selector?.wakeup()
            }
        } else {
            tunnel.sendPacket(packet)
        }
    }

    /**
     * Yeni UDP akışı başlat.
     *
     * **KURAL 4 (KESİNLİKLE):** `channel.register(...)` sonrası
     * `key.attach(tunnel)` MUTLAKA çağrılır. Yoksa selector runLoop
     * `key.attachment() as? UdpTunnel` null alır, `receivePackets()`
     * hiç çağrılmaz, DNS 15s timeout oluşur.
     */
    @Keep
    private fun initConnection(
        session: NatSession,
        portKey: Int,
        firstPacket: Packet
    ): UdpTunnel? {
        try {
            val channel = DatagramChannel.open()
            channel.configureBlocking(false)

            // protect() — TUN bypass
            if (!vpnService.protect(channel.socket())) {
                VPNLog.e(TAG, "protect() returned false for UDP channel")
                channel.close()
                return null
            }
            VPNLog.d(TAG, "protect() returned true for UDP channel, portKey=$portKey")

            val remoteAddress = InetAddress.getByAddress(
                byteArrayOf(
                    ((session.remoteIp shr 24) and 0xFF).toByte(),
                    ((session.remoteIp shr 16) and 0xFF).toByte(),
                    ((session.remoteIp shr 8) and 0xFF).toByte(),
                    (session.remoteIp and 0xFF).toByte()
                )
            )
            channel.connect(InetSocketAddress(remoteAddress, session.remotePort))

            // ───── KURAL 4: tunnel ÖNCE oluştur, sonra register + attach ─────
            val tunnel = UdpTunnel(
                channel, portKey, responseQueue,
                session.remoteIp, session.remotePort
            )
            val key: SelectionKey = channel.register(selector, SelectionKey.OP_READ)
            key.attach(tunnel)  // ← ASLA ATLA

            tunnel.sendPacket(firstPacket)  // ilk paketi hemen yaz
            return tunnel
        } catch (e: Exception) {
            VPNLog.e(TAG, "initConnection failed: ${e.message}", e)
            return null
        }
    }

    @Keep
    private fun runLoop() {
        val sel = selector ?: return
        while (isRunning) {
            try {
                val n = sel.select(VPNConstants.SELECTOR_TIMEOUT_MS)
                if (n > 0) {
                    val keys = sel.selectedKeys()
                    val iter = keys.iterator()
                    while (iter.hasNext()) {
                        val key = iter.next()
                        if (key.isReadable) {
                            // ───── KURAL 4: key.attachment() ASLA null olmamalı ─────
                            val tunnel = key.attachment() as? UdpTunnel
                            tunnel?.receivePackets()
                        }
                        iter.remove()
                    }
                }
                // Idle session cleanup
                NatSessionManager.clearExpiredSessions()
            } catch (e: Exception) {
                VPNLog.e(TAG, "selector loop exception: ${e.message}", e)
            }
        }
    }

    @Keep
    fun closeAllUdpConn() {
        isRunning = false
        selector?.wakeup()
        try { selector?.close() } catch (_: Exception) {}
        for (portKey in tunnelCache.keys.toList()) {
            tunnelCache[portKey]?.dispose()
        }
        tunnelCache.clear()
        VPNLog.d(TAG, "UdpServer closed all UDP conns")
    }
}
