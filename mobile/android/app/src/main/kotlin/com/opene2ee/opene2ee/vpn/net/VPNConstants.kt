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
     * MTU. **KESİNLİKLE 1400** - KURAL 1.
     * 15000 → Android 14 ConnectivityService "Unexpected mtu value: 15000, tun0" → VPN teardown.
     * 4G GTP trailer = 78 byte, Wi-Fi MTU = 1500. 1400 = WireGuard default, safe margin.
     * (Sprint 15.1: referans huolizhuminh/NetWorkPacketCapture 2560 kullanıyor — biz 1400'te
     * tutmaya devam ediyoruz çünkü IPv6 fragmentation riski daha düşük, Owner'ın 4G
     * 1400 byte data MTU ile stabil. Sprint 16+ gerekiyorsa test edilebilir.)
     */
    const val VPN_MTU = 1400

    /**
     * DNS sunucuları. Sprint 15.1 — referans huolizhuminh/NetWorkPacketCapture sırasıyla:
     *   1. 8.8.8.8 (Google primary — en yaygın, Türkiye dahil tüm ISP'lerde çalışır)
     *   2. 114.114.114.114 (Çin — Asya fallback, hızlı)
     *   3. 8.8.4.4 (Google secondary)
     *   4. 208.67.222.222 (OpenDNS — batı fallback)
     *
     * Sprint 14'te PRIMARY=1.1.1.1 (Cloudflare) kullanılmıştı. logcat150'de OpenE2ee app
     * (uid 10645) `api-test.opene2ee.com` DNS'i 5-20 saniye timeout alıyordu
     * (ConnectivityService: returnCode 7 / 255). Turk Telekom 4G baz istasyonlarında
     * 1.1.1.1:53 UDP paketleri drop ediliyor olabilir.
     *
     * NOT: Android VpnService.addDnsServer() OS system DNS resolver'ı override eder
     * (Android 10+), ama bazı native binary'ler (msys, PlayCommon) custom DoH/DoT
     * resolver kullanıyor ve bu listeden bağımsız çalışıyor. Bu beklenen davranış.
     */
    const val PRIMARY_DNS = "8.8.8.8"
    const val SECONDARY_DNS = "114.114.114.114"
    const val TERTIARY_DNS = "8.8.4.4"
    const val QUATERNARY_DNS = "208.67.222.222"

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
