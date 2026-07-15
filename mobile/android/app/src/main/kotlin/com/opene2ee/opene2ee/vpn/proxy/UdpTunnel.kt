// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/proxy/UdpTunnel.kt
//
// Sprint 14 — UDP tunnel (DatagramChannel-based NIO).
// Referans: huolizhuminh/NetWorkPacketCapture UDPTunnel.java.

package com.opene2ee.opene2ee.vpn.proxy

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.Packet
import com.opene2ee.opene2ee.vpn.tcpip.UDPHeader
import com.opene2ee.opene2ee.vpn.util.CommonMethods
import com.opene2ee.opene2ee.vpn.util.VPNLog
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentLinkedQueue

@Keep
class UdpTunnel(
    private val channel: DatagramChannel,
    private val portKey: Int,
    private val responseQueue: ConcurrentLinkedQueue<Packet>,
    private val remoteIp: Int,
    private val remotePort: Int
) {
    @Keep
    companion object {
        private const val TAG = "UdpTunnel"
        private const val HEADER_SIZE = 28  // IP4 (20) + UDP (8)
    }

    @Keep
    @Volatile
    private var disposed = false

    /**
     * İlk paket'i gönder. Paket, IP+UDP header içeriyor (TUN'dan okunmuş).
     * Amacımız: UDP header'ı remote'a göndermek.
     */
    @Keep
    fun sendPacket(packet: Packet) {
        if (disposed) return
        try {
            // IP+UDP header'ı oku, UDP payload'u gönder
            val ipHeader = com.opene2ee.opene2ee.vpn.tcpip.IPHeader(packet.mData, packet.mOffset)
            val udpHeader = UDPHeader(packet.mData, packet.mOffset + ipHeader.getHeaderLength())
            val payloadOffset = packet.mOffset + ipHeader.getHeaderLength() + udpHeader.getHeaderLength()
            val payloadSize = packet.mData.size - payloadOffset
            if (payloadSize <= 0) return

            val buffer = ByteBuffer.wrap(packet.mData, payloadOffset, payloadSize)
            val sent = channel.send(buffer, InetSocketAddress(
                InetAddress.getByAddress(byteArrayOf(
                    ((remoteIp shr 24) and 0xFF).toByte(),
                    ((remoteIp shr 16) and 0xFF).toByte(),
                    ((remoteIp shr 8) and 0xFF).toByte(),
                    (remoteIp and 0xFF).toByte()
                )),
                remotePort
            ))
            VPNLog.d(TAG, "sendPacket: portKey=$portKey, sent=$sent")
        } catch (e: Exception) {
            VPNLog.e(TAG, "sendPacket failed: ${e.message}", e)
        }
    }

    /**
     * Selector thread'inden çağrılır. Remote'tan gelen UDP response'u al,
     * IP+UDP header oluştur, responseQueue'ya ekle (OpenE2eeVpnService TUN'a yazacak).
     *
     * **KRİTİK — KURAL 4:** Bu method key.attachment() olarak selector
     * tarafından çağrılır. Eğer initConnection'da `key.attach(this)`
     * yapılmadıysa bu method hiç çağrılmaz, DNS timeout oluşur.
     */
    @Keep
    fun receivePackets() {
        if (disposed) return
        val receiveBuffer = ByteBuffer.allocate(2048)
        try {
            val remoteAddr = channel.receive(receiveBuffer)
            if (remoteAddr == null) return
            receiveBuffer.flip()
            val payloadSize = receiveBuffer.remaining()
            if (payloadSize <= 0) return

            // IP+UDP header oluştur ve response packet olarak queue'ya ekle
            val packetData = ByteArray(HEADER_SIZE + payloadSize)
            val ipHeader = com.opene2ee.opene2ee.vpn.tcpip.IPHeader(packetData, 0)
            val udpHeader = UDPHeader(packetData, 20)

            ipHeader.setHeaderLength(20)
            ipHeader.setTotalLength(HEADER_SIZE + payloadSize)
            ipHeader.setProtocol(com.opene2ee.opene2ee.vpn.tcpip.IPHeader.UDP)
            // Src = local proxy (sentinel), Dst = client
            // (OpenE2eeVpnService.onUdpPacketReceived zaten reverse swap yapar,
            //  burada sadece TUN'a yazılacak formata getiriyoruz)
            ipHeader.setSourceIP(0)  // placeholder, OpenE2eeVpnService reverse yapacak
            ipHeader.setDestinationIP(0)
            udpHeader.setSourcePort(remotePort)
            udpHeader.setDestinationPort(portKey)

            // Payload'u kopyala
            System.arraycopy(receiveBuffer.array(), receiveBuffer.arrayOffset(), packetData, HEADER_SIZE, payloadSize)

            // Checksum (UDP checksum is optional in IPv4, ama Android bazı cihazlarda drop edebilir)
            CommonMethods.ComputeUDPChecksum(ipHeader, udpHeader)

            responseQueue.offer(Packet(packetData, 0))
            VPNLog.d(TAG, "receivePackets: portKey=$portKey, bytes=$payloadSize")
        } catch (e: Exception) {
            VPNLog.e(TAG, "receivePackets failed: ${e.message}", e)
        }
    }

    @Keep
    fun dispose() {
        if (!disposed) {
            disposed = true
            try { channel.close() } catch (_: Exception) {}
        }
    }
}
