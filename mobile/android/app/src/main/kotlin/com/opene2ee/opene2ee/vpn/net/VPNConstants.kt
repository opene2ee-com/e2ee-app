// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/net/VPNConstants.kt
//
// Sprint 14 — VpnService temel sabit değerler.
// Referans: huolizhuminh/NetWorkPacketCapture VPNConstants.java + Sprint 13.0-fix MTU/timeout dersleri.

package com.opene2ee.opene2ee.vpn.net

import androidx.annotation.Keep

@Keep
object VPNConstants {
    /** TUN IP (loopback). */
    const val TUN_ADDRESS = "10.0.0.2"
    const val TUN_PREFIX = 32

    /** Capture everything route. */
    const val VPN_ROUTE = "0.0.0.0"
    const val VPN_ROUTE_PREFIX = 0

    /**
     * MTU. **KESİNLİKLE 1400** — KURAL 1.
     * 15000 → Android 14 ConnectivityService "Unexpected mtu value: 15000, tun0" → VPN teardown.
     * 4G GTP trailer = 78 byte, Wi-Fi MTU = 1500. 1400 = WireGuard default, safe margin.
     */
    const val VPN_MTU = 1400

    /** DNS sunucuları. Cloudflare primary (1.1.1.1 hızlı + privacy), Google fallback. */
    const val PRIMARY_DNS = "1.1.1.1"
    const val SECONDARY_DNS = "8.8.8.8"

    /** TUN session name (system VPN UI'da görünür). */
    const val TUN_SESSION_NAME = "OpenE2eeTunnel"

    /** NAT session üst sınırı. */
    const val MAX_SESSION_COUNT = 64

    /** Idle session timeout (milisaniye — referans repo uyumlu). */
    const val SESSION_TIME_OUT_MS: Long = 60_000L  // 60 saniye

    /** Paket okuma/yazma buffer boyutu. 32767 = max IP packet + margin. */
    const val PACKET_SIZE = 32767

    /** Foreground notification. */
    const val NOTIFICATION_CHANNEL_ID = "opene2ee.vpn.tunnel"
    const val NOTIFICATION_ID = 0x5650_4E4E  // 'VPNN'

    /** Selector poll interval. */
    const val SELECTOR_TIMEOUT_MS = 50L
    const val READ_LOOP_SLEEP_MS = 10L

    /** SharedPreferences. */
    const val VPN_SP_NAME = "vpn_config"
    const val DEFAULT_PACKAGE_ID = "default_package_id"
}
