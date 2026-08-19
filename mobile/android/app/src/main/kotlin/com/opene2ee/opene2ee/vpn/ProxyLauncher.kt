package com.opene2ee.opene2ee.vpn

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.opene2ee.opene2ee.vpn.config.VpnConfiguration
import com.opene2ee.opene2ee.vpn.net.IPHeader
import com.opene2ee.opene2ee.vpn.net.Protocol
import com.opene2ee.opene2ee.vpn.tcpip.ProtectedSocketTcpConnector
import com.opene2ee.opene2ee.vpn.tcpip.TcpForwarder
import com.opene2ee.opene2ee.vpn.udp.ProtectedDatagramUdpConnector
import com.opene2ee.opene2ee.vpn.udp.UdpForwarder
import com.opene2ee.opene2ee.vpn.util.VPNLogger
import com.opene2ee.opene2ee.vpn.util.closeSafely
import kotlinx.coroutines.CoroutineScope
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VpnService.Builder.establish() → returns ParcelFileDescriptor, then
 * spawns the PacketDispatcher on a single dedicated thread (NOT
 * inline in startVpn) to keep TUN read loop off the main thread.
 */
internal class ProxyLauncher(
    private val proxyService: OpenE2eeVpnService
) : CoroutineScope by proxyService {

    private val tag = "ProxyLauncher"
    @Volatile
    private var dispatcher: PacketDispatcher? = null
    @Volatile
    private var executor: ExecutorService? = null

    fun launch(configuration: VpnConfiguration): ParcelFileDescriptor? {
        val connectivityManager = proxyService.getSystemService(ConnectivityManager::class.java)
        val underlyingNetwork = connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            } == true
        } ?: throw IllegalStateException("no non-VPN network is available")
        val builder = proxyService.Builder().apply {
            setMtu(configuration.mtu)  // KURAL 1
                .addAddress(configuration.ipv4Address, configuration.ipv4PrefixLength)  // KURAL 2
                .allowFamily(OsConstants.AF_INET)
                .setBlocking(true)
                .setUnderlyingNetworks(arrayOf(underlyingNetwork))

            if (configuration.enableIPv6) {
                addAddress(configuration.ipv6Address, configuration.ipv6PrefixLength)
                    .allowFamily(OsConstants.AF_INET6)
            }

            for (route in configuration.routes) {
                addRoute(route.first, route.second)
            }

            for (dns in configuration.dnsServers) {
                addDnsServer(dns)  // KURAL 7: 8.8.8.8 primary, 1.1.1.1 fallback
            }

            // KURAL 3: addAllowedApplication/addDisallowedApplication KULLANMA
            // (Sprint 12.0F+9 SecurityException dersi; tüm trafik default)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setMetered(false)
            }
        }

        val proxyDescriptor = builder.establish() ?: return null

        // Spawn PacketDispatcher on a dedicated IO thread (NOT in startVpn's coroutine)
        val packetDispatcher = PacketDispatcher(
            configuration,
            proxyDescriptor,
            proxyService,
            underlyingNetwork,
        )
        val dispatcherExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "vpn-tun-dispatcher").apply { isDaemon = true }
        }
        dispatcher = packetDispatcher
        executor = dispatcherExecutor
        dispatcherExecutor.submit {
            packetDispatcher.dispatch()
        }

        return proxyDescriptor
    }

    fun close() {
        val activeDispatcher = dispatcher
        val activeExecutor = executor
        dispatcher = null
        executor = null
        activeDispatcher?.close()
        activeExecutor?.shutdownNow()
        if (activeExecutor != null && !activeExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
            VPNLogger.w(tag, "dispatcher executor did not terminate within 2 seconds")
        }
    }
}

internal class PacketDispatcher(
    private val configuration: VpnConfiguration,
    private val proxyDescriptor: ParcelFileDescriptor,
    private val proxyService: OpenE2eeVpnService,
    underlyingNetwork: android.net.Network,
) {
    private val tag = "PacketDispatcher"
    private val closed = AtomicBoolean(false)

    private val inputStream = FileInputStream(proxyDescriptor.fileDescriptor)
    private val outputStream = FileOutputStream(proxyDescriptor.fileDescriptor)
    private val tunWriteLock = Any()
    private val tunWriter = object : OutputStream() {
        override fun write(value: Int) = synchronized(tunWriteLock) { outputStream.write(value) }
        override fun write(buffer: ByteArray, offset: Int, length: Int) =
            synchronized(tunWriteLock) { outputStream.write(buffer, offset, length) }
    }
    private val tcpConnector = ProtectedSocketTcpConnector(proxyService, underlyingNetwork)
    private val tcpForwarder = TcpForwarder(
        connector = tcpConnector,
        packetSink = { packet -> tunWriter.write(packet) },
        initialSequence = SecureRandom()::nextInt,
        onEvent = { event -> VPNLogger.i("TcpForwarder", event) },
    )
    private val udpConnector = ProtectedDatagramUdpConnector(proxyService, underlyingNetwork)
    private val udpForwarder = UdpForwarder(
        connector = udpConnector,
        packetSink = { packet -> tunWriter.write(packet) },
        onEvent = { event -> VPNLogger.i("UdpForwarder", event) },
    )

    fun dispatch() {
        try {
            val buffer = ByteArray(configuration.mtu)
            while (!closed.get() && !Thread.currentThread().isInterrupted) {
                val length = try {
                    inputStream.read(buffer)
                } catch (e: InterruptedIOException) {
                    break
                } catch (e: Exception) {
                    if (!closed.get()) VPNLogger.e(tag, "tun read failed", e)
                    break
                }
                if (length <= 0) continue

                // Keep each packet ephemeral while forwarding; the service stores
                // sampled header metadata only, never the packet payload.
                val packetBytes = buffer.copyOf(length)
                try {
                    val ipHeader = IPHeader.parse(packetBytes, length, 0) ?: continue
                    val protocol = Protocol.parse(ipHeader.dataProtocol)
                    proxyService.recordSampledPacket(ipHeader, packetBytes, length)
                    when (protocol) {
                        Protocol.TCP -> tcpForwarder.accept(packetBytes)
                        Protocol.UDP -> udpForwarder.accept(packetBytes)
                        else -> VPNLogger.d(tag, "dropping unsupported protocol=${ipHeader.dataProtocol}")
                    }
                } catch (e: Exception) {
                    VPNLogger.e(tag, "packet dispatch failed", e)
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) VPNLogger.e(tag, "dispatcher fatal", e)
        } finally {
            close()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        tcpForwarder.close()
        tcpConnector.close()
        udpForwarder.close()
        udpConnector.close()
        inputStream.closeSafely()
        outputStream.closeSafely()
        proxyDescriptor.closeSafely()
    }
}
