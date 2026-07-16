package com.opene2ee.opene2ee.vpn

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.opene2ee.opene2ee.vpn.config.VpnConfiguration
import com.opene2ee.opene2ee.vpn.net.IPHeader
import com.opene2ee.opene2ee.vpn.net.Packet
import com.opene2ee.opene2ee.vpn.net.Protocol
import com.opene2ee.opene2ee.vpn.tcpip.TcpPacketInterceptor
import com.opene2ee.opene2ee.vpn.udp.UdpPacketInterceptor
import com.opene2ee.opene2ee.vpn.util.VPNLogger
import com.opene2ee.opene2ee.vpn.util.closeSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.util.concurrent.Executors

/**
 * VpnService.Builder.establish() → returns ParcelFileDescriptor, then
 * spawns the PacketDispatcher on a single dedicated thread (NOT
 * inline in startVpn) to keep TUN read loop off the main thread.
 */
internal class ProxyLauncher(
    private val proxyService: OpenE2eeVpnService
) : CoroutineScope by proxyService {

    private val tag = "ProxyLauncher"

    fun launch(configuration: VpnConfiguration): ParcelFileDescriptor? {
        val builder = proxyService.Builder().apply {
            setMtu(configuration.mtu)  // KURAL 1
                .addAddress(configuration.ipv4Address, configuration.ipv4PrefixLength)  // KURAL 2
                .allowFamily(OsConstants.AF_INET)
                .setBlocking(true)

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
        val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "vpn-tun-dispatcher").apply { isDaemon = true }
        }
        executor.submit {
            PacketDispatcher(configuration, proxyDescriptor, proxyService).dispatch()
        }

        return proxyDescriptor
    }
}

internal class PacketDispatcher(
    private val configuration: VpnConfiguration,
    private val proxyDescriptor: ParcelFileDescriptor,
    private val proxyService: OpenE2eeVpnService
) {
    private val tag = "PacketDispatcher"

    private val inputStream = FileInputStream(proxyDescriptor.fileDescriptor)
    private val outputStream = FileOutputStream(proxyDescriptor.fileDescriptor)

    private val interceptors = hashMapOf<Protocol, PacketInterceptor>(
        Protocol.TCP to TcpPacketInterceptor(configuration, proxyService),
        Protocol.UDP to UdpPacketInterceptor(configuration, proxyService)
    )

    fun dispatch() {
        try {
            val buffer = ByteArray(configuration.mtu)
            while (!Thread.currentThread().isInterrupted) {
                val length = try {
                    inputStream.read(buffer)
                } catch (e: InterruptedIOException) {
                    break
                } catch (e: Exception) {
                    VPNLogger.e(tag, "tun read failed", e)
                    break
                }
                if (length <= 0) continue

                val packet = Packet(buffer.copyOf(length), length)
                val ipHeader = IPHeader.parse(packet.packet, packet.length, 0) ?: continue
                val protocol = Protocol.parse(ipHeader.dataProtocol)
                val interceptor = interceptors[protocol] ?: continue

                try {
                    interceptor.intercept(ipHeader, packet, outputStream)
                } catch (e: Exception) {
                    VPNLogger.e(tag, "interceptor error", e)
                }
            }
        } catch (e: Exception) {
            VPNLogger.e(tag, "dispatcher fatal", e)
        } finally {
            inputStream.closeSafely()
            outputStream.closeSafely()
            proxyDescriptor.closeSafely()
        }
    }
}
