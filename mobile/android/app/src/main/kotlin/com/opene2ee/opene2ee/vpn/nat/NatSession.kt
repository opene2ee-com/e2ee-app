// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/nat/NatSession.kt
//
// Sprint 14 — NAT session data class.
// Referans: huolizhuminh/NetWorkPacketCapture NatSession.java.

package com.opene2ee.opene2ee.vpn.nat

import androidx.annotation.Keep

@Keep
class NatSession {
    @Keep
    companion object {
        const val TCP = 1
        const val UDP = 2
    }

    /** Remote IP (int — IPv4 4 byte packed). Reference repo ile uyumlu. */
    @Keep var remoteIp: Int = 0

    /** Remote port (Int 0..65535). */
    @Keep var remotePort: Int = 0

    /**
     * Local port (app's source port). **MUTLAKA** createSession'da
     * portKey ile set edilir (KURAL 5 — PortHostService UID lookup buna bağlı).
     */
    @Keep var localPort: Int = 0

    /** TCP veya UDP (NatSession.TCP / NatSession.UDP). */
    @Keep var type: Int = 0

    /** Son paket zamanı (clearExpiredSessions bu + timeout'a bakar). */
    @Keep var lastRefreshTime: Long = 0L

    /** Toplam gönderilen paket sayısı. */
    @Keep var packetSent: Int = 0

    /** Toplam gönderilen byte. */
    @Keep var bytesSent: Long = 0L

    /** HTTP ise true. */
    @Keep var isHttp: Boolean = false

    /** HTTPS ise true. */
    @Keep var isHttpsSession: Boolean = false

    /** Hostname (parse edilmiş — SNI veya Host header). */
    @Keep var remoteHost: String? = null

    /** HTTP method (GET/POST/etc). */
    @Keep var method: String? = null

    /** Full request URL. */
    @Keep var requestUrl: String? = null

    /** URL path. */
    @Keep var pathUrl: String? = null

    /** UID (kullanıcı uid'si — hangi app bu session'ı açtı). */
    @Keep var uid: Int = -1

    override fun toString(): String {
        return "NatSession{type=$type, remote=${remoteIp}:${remotePort}, local=${localPort}, packetSent=$packetSent}"
    }
}
