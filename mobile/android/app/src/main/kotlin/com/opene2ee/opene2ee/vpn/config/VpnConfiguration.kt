package com.opene2ee.opene2ee.vpn.config

import androidx.annotation.Keep

@Keep
class VpnConfiguration private constructor() {

    var mtu: Int = 1400  // KURAL 1

    var ipv4Address: String = "10.1.10.1"  // KURAL 2
    var ipv4PrefixLength: Int = 32

    var enableIPv6: Boolean = false
    var ipv6Address: String = "a:0:0:1:a:0:0:1"
    var ipv6PrefixLength: Int = 128

    private val _routes: MutableSet<Pair<String, Int>> = hashSetOf()
    val routes: Set<Pair<String, Int>> get() = _routes

    private val _dnsServers: MutableSet<String> = hashSetOf()
    val dnsServers: Set<String> get() = _dnsServers

    fun addRoute(route: Pair<String, Int>) { _routes.add(route) }
    fun addDnsServer(dns: String) { _dnsServers.add(dns) }

    fun routesSnapshot(): Set<Pair<String, Int>> = _routes.toSet()
    fun dnsServersSnapshot(): Set<String> = _dnsServers.toSet()

    companion object {
        @JvmStatic
        fun default(): VpnConfiguration = VpnConfiguration().apply {
            // Capture everything (Sprint 14'teki 0.0.0.0/0 IPv4 default'un korunmuş hali)
            addRoute("0.0.0.0" to 0)
            // KURAL 7: Google primary, Cloudflare fallback (wirebare default sırası)
            addDnsServer("8.8.8.8")
            addDnsServer("1.1.1.1")
        }
    }
}
