// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/net/ProxyConfig.kt
//
// Sprint 14 — VPN session/MTU/IP config holder.

package com.opene2ee.opene2ee.vpn.net

import android.content.Context
import androidx.annotation.Keep

@Keep
object ProxyConfig {
    @Keep
    val Instance: ProxyConfig = this

    @Keep
    fun getDefaultLocalIp(): IPAddress = IPAddress("10.0.0.2", 32)

    @Keep
    fun onVpnStart(context: Context) {
        // No-op for Sprint 14. Reserved for future VPN status listeners.
    }

    @Keep
    fun onVpnEnd(context: Context) {
        // No-op for Sprint 14.
    }

    @Keep
    class IPAddress(@Keep val address: String, @Keep val prefixLength: Int) {
        @Keep
        constructor(ipAddressString: String) : this(
            ipAddressString.substringBefore("/"),
            ipAddressString.substringAfter("/", "32").toInt()
        )

        override fun toString(): String = "$address/$prefixLength"
    }
}
