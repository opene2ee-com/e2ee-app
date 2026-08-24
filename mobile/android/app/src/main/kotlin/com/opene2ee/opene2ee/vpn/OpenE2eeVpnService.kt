package com.opene2ee.opene2ee.vpn

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.config.VpnConfiguration
import com.opene2ee.opene2ee.vpn.config.VpnStatus
import com.opene2ee.opene2ee.vpn.net.IPHeader
import com.opene2ee.opene2ee.vpn.net.IPVersion
import com.opene2ee.opene2ee.vpn.net.Protocol
import com.opene2ee.opene2ee.vpn.tcpip.TcpHeader
import com.opene2ee.opene2ee.vpn.udp.UdpHeader
import com.opene2ee.opene2ee.vpn.util.convertPortToInt
import com.opene2ee.opene2ee.vpn.util.VPNLogger
import com.opene2ee.opene2ee.vpn.util.closeSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@Keep
class OpenE2eeVpnService : VpnService(), CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO) {

    companion object {
        const val TAG = "OpenE2eeVpnService"
        const val ACTION_START = "com.opene2ee.opene2ee.vpn.action.START"
        const val ACTION_STOP = "com.opene2ee.opene2ee.vpn.action.STOP"
        const val NOTIFICATION_CHANNEL_ID = "opene2ee.vpn.tunnel"
        const val NOTIFICATION_ID = 0x5650_4E4E  // 'VPNN'
        private const val SAMPLING_CAP_PACKETS = 10

        @Volatile
        private var currentInstance: OpenE2eeVpnService? = null

        val isRunning: Boolean
            get() = currentInstance != null

        @Keep
        @JvmStatic
        fun prepareSelf(context: android.content.Context): Intent? {
            return VpnService.prepare(context)
        }

        /**
         * Drains the privacy-safe packet metadata collected by the live TUN
         * dispatcher. The Flutter MethodChannel must never receive packet
         * payloads or a reference to the service's mutable buffer.
         */
        fun drainSampledPackets(): List<Map<String, Any>> =
            currentInstance?.drainSampledPacketMetadata() ?: emptyList()
    }

    @Volatile
    private var proxyDescriptor: ParcelFileDescriptor? = null
    @Volatile
    private var proxyLauncher: ProxyLauncher? = null
    private val vpnStarting = AtomicBoolean(false)
    private val sampledPacketMetadata = ArrayDeque<Map<String, Any>>()

    /**
     * Records only IP/transport header metadata from the ephemeral packet buffer.
     * This is deliberately bounded to the ADR's ten-packet sampling cap and never
     * retains payload bytes.
     */
    internal fun recordSampledPacket(ipHeader: IPHeader, packet: ByteArray, length: Int) {
        val protocolNumber = ipHeader.dataProtocol.toInt() and 0xFF
        val protocol = Protocol.parse(ipHeader.dataProtocol)
        if (protocol != Protocol.TCP && protocol != Protocol.UDP) return

        val metadata = try {
            linkedMapOf<String, Any>(
                "version" to ipHeader.ipVersion.versionName,
                "protocol" to protocol.name.lowercase(),
                "protocolNumber" to protocolNumber,
                "packetLength" to minOf(ipHeader.totalLength, length),
                "srcIpMasked" to maskIpAddress(ipHeader.sourceAddress.stringIP, ipHeader.ipVersion),
                "dstIpMasked" to maskIpAddress(ipHeader.destinationAddress.stringIP, ipHeader.ipVersion),
            ).apply {
                when (protocol) {
                    Protocol.TCP -> {
                        if (length < ipHeader.headerLength + 20) return
                        val tcpHeader = TcpHeader(ipHeader, packet, ipHeader.headerLength)
                        put("srcPort", tcpHeader.sourcePort.port.convertPortToInt)
                        put("dstPort", tcpHeader.destinationPort.port.convertPortToInt)
                        put("tcpFlags", tcpHeader.flag.toInt() and 0xFF)
                    }
                    Protocol.UDP -> {
                        if (length < ipHeader.headerLength + UdpHeader.UDP_HEADER_LENGTH) return
                        val udpHeader = UdpHeader(ipHeader, packet, ipHeader.headerLength)
                        put("srcPort", udpHeader.sourcePort.port.convertPortToInt)
                        put("dstPort", udpHeader.destinationPort.port.convertPortToInt)
                    }
                }
            }
        } catch (e: Exception) {
            VPNLogger.w(TAG, "skipping malformed $protocol packet metadata: ${e.message}")
            return
        }

        synchronized(sampledPacketMetadata) {
            if (sampledPacketMetadata.size == SAMPLING_CAP_PACKETS) {
                sampledPacketMetadata.removeFirst()
            }
            sampledPacketMetadata.addLast(metadata)
        }
    }

    private fun drainSampledPacketMetadata(): List<Map<String, Any>> =
        synchronized(sampledPacketMetadata) {
            sampledPacketMetadata.toList().also { sampledPacketMetadata.clear() }
        }

    private fun maskIpAddress(address: String, version: IPVersion): String = when (version) {
        IPVersion.IPv4 -> address.substringBeforeLast('.', missingDelimiterValue = "0.0.0") + ".0"
        IPVersion.IPv6 -> address.split(':').take(4).joinToString(":") + "::"
    }

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        VPNLogger.i(TAG, "onCreate")
    }

    final override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VPNLogger.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
            else -> {
                VPNLogger.w(TAG, "unknown action ${intent?.action}")
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startVpn() {
        if (proxyDescriptor != null || !vpnStarting.compareAndSet(false, true)) {
            VPNLogger.i(TAG, "startVpn ignored: tunnel already active or starting")
            return
        }
        VPNLogger.i(TAG, "startVpn")
        startForegroundCompat()
        val configuration = VpnConfiguration.default()
        launch(Dispatchers.IO) {
            val launcher = ProxyLauncher(this@OpenE2eeVpnService)
            proxyLauncher = launcher
            try {
                val fd = launcher.launch(configuration)
                proxyDescriptor = fd
                VpnStatus.notify(VpnStatus.ACTIVE)
            } catch (e: Exception) {
                launcher.close()
                if (proxyLauncher === launcher) proxyLauncher = null
                VPNLogger.e(TAG, "vpn launch failed", e)
                VpnStatus.notify(VpnStatus.DEAD)
                stopSelf()
            } finally {
                vpnStarting.set(false)
            }
        }
    }

    private fun stopVpn() {
        VPNLogger.i(TAG, "stopVpn")
        if (proxyDescriptor != null || vpnStarting.get()) {
            VpnStatus.notify(VpnStatus.DYING)
        }
        closeTunnel()
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        VPNLogger.i(TAG, "onDestroy")
        closeTunnel()
        synchronized(sampledPacketMetadata) { sampledPacketMetadata.clear() }
        currentInstance = null
        cancel()
        VpnStatus.notify(VpnStatus.DEAD)
        super.onDestroy()
    }

    private fun closeTunnel() {
        val launcher = proxyLauncher
        proxyLauncher = null
        val descriptor = proxyDescriptor
        proxyDescriptor = null
        vpnStarting.set(false)
        launcher?.close()
        descriptor?.closeSafely()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        androidx.core.app.ServiceCompat.stopForeground(
            this,
            androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE
        )
    }

    private fun buildNotification(): Notification {
        val manager = androidx.core.app.NotificationManagerCompat.from(this)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                androidx.core.app.NotificationChannelCompat.Builder(
                    NOTIFICATION_CHANNEL_ID,
                    androidx.core.app.NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName("OpenE2EE Şifreleme Doğrulama").build()
            )
        }
        return androidx.core.app.NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OpenE2EE Şifreleme Doğrulama")
            .setContentText("Şifreleme bütünlüğü doğrulama oturumu aktif")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

}
