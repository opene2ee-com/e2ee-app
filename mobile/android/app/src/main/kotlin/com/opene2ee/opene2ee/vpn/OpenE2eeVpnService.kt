// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/OpenE2eeVpnService.kt
//
// Sprint 14 — Ana VpnService entry point.
// Referans: huolizhuminh/NetWorkPacketCapture FirewallVpnService.java + Sprint 13.0 tüm dersleri.

package com.opene2ee.opene2ee.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.opene2ee.opene2ee.MainActivity
import com.opene2ee.opene2ee.R
import com.opene2ee.opene2ee.vpn.nat.NatSession
import com.opene2ee.opene2ee.vpn.nat.NatSessionManager
import com.opene2ee.opene2ee.vpn.net.ProxyConfig
import com.opene2ee.opene2ee.vpn.net.VPNConstants
import com.opene2ee.opene2ee.vpn.processparse.PortHostService
import com.opene2ee.opene2ee.vpn.proxy.TcpProxyServer
import com.opene2ee.opene2ee.vpn.proxy.UdpServer
import com.opene2ee.opene2ee.vpn.tcpip.IPHeader
import com.opene2ee.opene2ee.vpn.tcpip.TCPHeader
import com.opene2ee.opene2ee.vpn.tcpip.UDPHeader
import com.opene2ee.opene2ee.vpn.util.CommonMethods
import com.opene2ee.opene2ee.vpn.util.DebugLog
import com.opene2ee.opene2ee.vpn.util.ThreadProxy
import com.opene2ee.opene2ee.vpn.util.VPNLog
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentLinkedQueue

@Keep
class OpenE2eeVpnService : VpnService(), Runnable {

    @Keep
    companion object {
        private const val TAG = "OpenE2eeVpnService"

        @Keep
        @Volatile
        var activeInstance: OpenE2eeVpnService? = null
            internal set

        const val METHOD_CHANNEL = "opene2ee/vpn"
    }

    // ───── State (instance-level, companion object'te minimum) ─────
    @Keep
    @Volatile
    private var isRunning: Boolean = false

    @Keep
    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null

    @Keep
    @Volatile
    private var vpnOutputStream: FileOutputStream? = null

    @Keep
    @Volatile
    private var vpnInputStream: FileInputStream? = null

    @Keep
    @Volatile
    private var localIpInt: Int = 0

    @Keep
    private var vpnThread: Thread? = null

    @Keep
    private val packetBuffer: ByteArray = ByteArray(VPNConstants.PACKET_SIZE)
    @Keep
    private val ipHeader: IPHeader = IPHeader(packetBuffer, 0)
    @Keep
    private val tcpHeader: TCPHeader = TCPHeader(packetBuffer, 20)
    @Keep
    private val udpHeader: UDPHeader = UDPHeader(packetBuffer, 20)

    @Keep
    private val udpQueue: ConcurrentLinkedQueue<Packet> = ConcurrentLinkedQueue()

    @Keep
    private var tcpProxyServer: TcpProxyServer? = null
    @Keep
    private var udpServer: UdpServer? = null

    // ═══ Lifecycle ═══

    @Keep
    override fun onCreate() {
        super.onCreate()
        DebugLog.i("OpenE2eeVpnService onCreate, id=${hashCode()}")

        ensureNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, VPNConstants.NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(VPNConstants.NOTIFICATION_ID, notification)
        }

        isRunning = true
        vpnThread = Thread(this, "VPNServiceThread").also { it.start() }
    }

    @Keep
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLog.i("OpenE2eeVpnService onStartCommand, action=${intent?.action}")
        return START_STICKY
    }

    @Keep
    override fun onDestroy() {
        DebugLog.i("OpenE2eeVpnService onDestroy")
        isRunning = false
        vpnThread?.interrupt()
        dispose()
        super.onDestroy()
    }

    @Keep
    override fun onRevoke() {
        DebugLog.w("OpenE2eeVpnService onRevoke")
        stopSelf()
    }

    /**
     * Sprint 13.0-fix: External synchronous stop entry point.
     * MainActivity "stop" branch önce svc.stopVpn() çağırır, sonra stopService(intent).
     */
    @Keep
    fun stopVpn() {
        DebugLog.i("OpenE2eeVpnService stopVpn called")
        isRunning = false  // Sprint 14.1 fix: stop the worker thread's while(isRunning) loop
        try { dispose() } catch (e: Exception) { DebugLog.e("stopVpn.dispose failed: $e") }
        try { stopSelf() } catch (e: Exception) { DebugLog.e("stopVpn.stopSelf failed: $e") }
    }

    // ═══ Main run loop ═══

    @Keep
    override fun run() {
        try {
            DebugLog.i("OpenE2eeVpnService work thread running")
            activeInstance = this
            waitUntilPrepared()

            udpQueue.clear()
            tcpProxyServer = TcpProxyServer(0).also { it.start() }
            udpServer = UdpServer(this, udpQueue).also { it.start() }
            NatSessionManager.clearAllSession()

            // PR #33 fix: PortHostService henüz instance yoksa startParse
            if (PortHostService.getInstance() == null) {
                PortHostService.startParse(applicationContext)
            }
            DebugLog.i("PortHostService started")

            ProxyConfig.Instance.onVpnStart(this)

            while (isRunning) {
                runVpn()
            }
        } catch (e: InterruptedException) {
            DebugLog.e("OpenE2eeVpnService run interrupted: $e")
        } catch (e: Exception) {
            DebugLog.e("OpenE2eeVpnService run exception: $e")
        } finally {
            DebugLog.i("OpenE2eeVpnService terminated")
            ProxyConfig.Instance.onVpnEnd(this)
            dispose()
        }
    }

    // ═══ VPN kurulumu ve okuma ═══

    @Keep
    @Throws(Exception::class)
    private fun runVpn() {
        vpnInterface = establishVpn()
        startStream()
    }

    @Keep
    @Throws(Exception::class)
    private fun startStream() {
        var size = 0
        vpnOutputStream = FileOutputStream(vpnInterface!!.fileDescriptor)
        vpnInputStream = FileInputStream(vpnInterface!!.fileDescriptor)

        while (size != -1 && isRunning) {
            var hasWrite = false
            size = try {
                vpnInputStream!!.read(packetBuffer)
            } catch (e: Exception) {
                DebugLog.e("TUN read failed: $e")
                -1
            }
            if (size > 0) {
                if (tcpProxyServer?.isStopped == true) {
                    try { vpnInputStream!!.close() } catch (_: Exception) {}
                    throw Exception("LocalServer stopped.")
                }
                hasWrite = onIpPacketReceived(ipHeader, size)
            }
            if (!hasWrite) {
                val packet = udpQueue.poll()
                if (packet != null) {
                    val buffer = packet.mData
                    try {
                        vpnOutputStream!!.write(buffer, packet.mOffset, packet.mData.size - packet.mOffset)
                    } catch (e: Exception) {
                        DebugLog.e("TUN write (UDP response) failed: $e")
                    }
                }
            }
            try { Thread.sleep(VPNConstants.READ_LOOP_SLEEP_MS) } catch (_: InterruptedException) {}
        }

        try { vpnInputStream?.close() } catch (_: Exception) {}
        disconnectVpn()
    }

    // ═══ IP paket dispatch ═══

    @Keep
    @Throws(java.io.IOException::class)
    private fun onIpPacketReceived(ipHeader: IPHeader, size: Int): Boolean {
        return when (ipHeader.getProtocol()) {
            IPHeader.TCP -> onTcpPacketReceived(ipHeader, size)
            IPHeader.UDP -> {
                onUdpPacketReceived(ipHeader, size)
                false
            }
            else -> false
        }
    }

    @Keep
    @Throws(java.net.UnknownHostException::class)
    private fun onUdpPacketReceived(ipHeader: IPHeader, size: Int) {
        val portKey = udpHeader.getSourcePort()
        var session = NatSessionManager.getSession(portKey)

        if (session == null ||
            session.remoteIp != ipHeader.getDestinationIP() ||
            session.remotePort != udpHeader.getDestinationPort()
        ) {
            session = NatSessionManager.createSession(
                portKey, ipHeader.getDestinationIP(), udpHeader.getDestinationPort(), NatSession.UDP
            )
            ThreadProxy.execute {
                PortHostService.getInstance()?.refreshSessionInfo()
            }
        }
        session.lastRefreshTime = System.currentTimeMillis()
        session.packetSent++

        // Paketi kopyala ve UdpServer'a gönder
        val data = packetBuffer.copyOf(size)
        val packet = Packet(data, 0)
        udpServer?.processUdpPacket(packet, portKey)
    }

    @Keep
    @Throws(java.io.IOException::class)
    private fun onTcpPacketReceived(ipHeader: IPHeader, size: Int): Boolean {
        var hasWrite = false
        tcpHeader.mOffset = ipHeader.getHeaderLength()

        if (tcpHeader.getSourcePort() == tcpProxyServer?.port) {
            // Reverse: local proxy bize yazıyor (remote → app)
            VPNLog.d(TAG, "process tcp packet from net")
            val session = NatSessionManager.getSession(tcpHeader.getDestinationPort())
            if (session != null) {
                ipHeader.setSourceIP(ipHeader.getDestinationIP())
                tcpHeader.setSourcePort(session.remotePort)
                ipHeader.setDestinationIP(localIpInt)
                CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader)
                try {
                    vpnOutputStream!!.write(packetBuffer, ipHeader.mOffset, size)
                } catch (e: Exception) {
                    DebugLog.e("TUN write (TCP reverse) failed: $e")
                }
            } else {
                DebugLog.i("NoSession: ${ipHeader} ${tcpHeader}")
            }
        } else {
            // Forward: app bize yazıyor (app → remote)
            VPNLog.d(TAG, "process tcp packet to net")
            val portKey = tcpHeader.getSourcePort()
            var session = NatSessionManager.getSession(portKey)

            if (session == null ||
                session.remoteIp != ipHeader.getDestinationIP() ||
                session.remotePort != tcpHeader.getDestinationPort()
            ) {
                session = NatSessionManager.createSession(
                    portKey, ipHeader.getDestinationIP(), tcpHeader.getDestinationPort(), NatSession.TCP
                )
            ThreadProxy.execute {
                    PortHostService.getInstance()?.refreshSessionInfo()
                }
            }
            session.lastRefreshTime = System.currentTimeMillis()
            session.packetSent++

            val tcpDataSize = ipHeader.getDataLength() - tcpHeader.getHeaderLength()
            if (session.packetSent == 2 && tcpDataSize == 0) {
                return false  // TCP handshake 2. ACK no-data atla
            }

            // Paketi local proxy'ye yönlendir
            ipHeader.setSourceIP(ipHeader.getDestinationIP())
            ipHeader.setDestinationIP(localIpInt)
            tcpHeader.setDestinationPort(tcpProxyServer?.port ?: 0)
            CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader)
            try {
                vpnOutputStream!!.write(packetBuffer, ipHeader.mOffset, size)
            } catch (e: Exception) {
                DebugLog.e("TUN write (TCP forward) failed: $e")
            }
            session.bytesSent += tcpDataSize
        }
        hasWrite = true
        return hasWrite
    }

    // ═══ VPN builder ═══

    @Keep
    @Throws(Exception::class)
    private fun establishVpn(): ParcelFileDescriptor {
        val builder = Builder()
        // KURAL 1: MTU = 1400
        builder.setMtu(VPNConstants.VPN_MTU)
        DebugLog.i("setMtu: ${VPNConstants.VPN_MTU}")

        val localIp = ProxyConfig.Instance.getDefaultLocalIp()
        localIpInt = CommonMethods.ipStringToInt(localIp.address)
        builder.addAddress(localIp.address, localIp.prefixLength)
        DebugLog.i("addAddress: ${localIp.address}/${localIp.prefixLength}")

        builder.addRoute(VPNConstants.VPN_ROUTE, VPNConstants.VPN_ROUTE_PREFIX)
        // Sprint 15.1: 4 DNS server — referans huolizhuminh/NetWorkPacketCapture sırasıyla.
        // 1.1.1.1 Türkiye'de (TT 4G) timeout alıyordu, 8.8.8.8 primary oldu.
        builder.addDnsServer(VPNConstants.PRIMARY_DNS)
        builder.addDnsServer(VPNConstants.SECONDARY_DNS)
        builder.addDnsServer(VPNConstants.TERTIARY_DNS)
        builder.addDnsServer(VPNConstants.QUATERNARY_DNS)
        DebugLog.i("addDnsServer done: ${VPNConstants.PRIMARY_DNS}, ${VPNConstants.SECONDARY_DNS}, ${VPNConstants.TERTIARY_DNS}, ${VPNConstants.QUATERNARY_DNS}")

        // KURAL 6: addDisallowedApplication KULLANMA.
        // Varsayılan: tüm trafik (null = no filter). İleride allowlist istense:
        val pkg = getSharedPreferences(VPNConstants.VPN_SP_NAME, Context.MODE_PRIVATE)
            .getString(VPNConstants.DEFAULT_PACKAGE_ID, null)
        try {
            if (pkg != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.addAllowedApplication(pkg)
                builder.addAllowedApplication(packageName)
            }
        } catch (e: Exception) {
            DebugLog.e("addAllowedApplication failed: $e")
        }

        builder.setSession(VPNConstants.TUN_SESSION_NAME)
        val pfd = builder.establish()
        if (pfd == null) throw Exception("VPN establish returned null")
        DebugLog.i("VPN established, pfd=$pfd")
        return pfd
    }

    // ═══ Helpers ═══

    @Keep
    private fun waitUntilPrepared() {
        while (prepare(this) != null) {
            try { Thread.sleep(100) } catch (_: InterruptedException) {}
        }
    }

    @Keep
    @Synchronized
    private fun dispose() {
        isRunning = false  // Sprint 14.1 fix: defense in depth — even if stopVpn forgot, dispose stops the worker
        try {
            disconnectVpn()
            tcpProxyServer?.stop()
            tcpProxyServer = null
            DebugLog.i("TcpProxyServer stopped")
            udpServer?.closeAllUdpConn()
            udpServer = null
            ThreadProxy.execute {
                PortHostService.getInstance()?.refreshSessionInfo()
                PortHostService.stopParse(applicationContext)
            }
            if (activeInstance === this) activeInstance = null
            stopSelf()
        } catch (e: Exception) {
            DebugLog.e("dispose exception: $e")
        }
    }

    @Keep
    private fun disconnectVpn() {
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        vpnOutputStream = null
    }

    // ═══ Foreground notification ═══

    @Keep
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(VPNConstants.NOTIFICATION_CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                VPNConstants.NOTIFICATION_CHANNEL_ID,
                VPNConstants.TUN_SESSION_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN tunnel background relay"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(channel)
        }
    }

    @Keep
    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, VPNConstants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(VPNConstants.TUN_SESSION_NAME)
            .setContentText("VPN tunnel active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
