// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/nat/NatSessionManager.kt
//
// Sprint 14 — NAT session tracking (portKey → NatSession).
// Referans: huolizhuminh/NetWorkPacketCapture NatSessionManager.java.

package com.opene2ee.opene2ee.vpn.nat

import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.net.VPNConstants
import com.opene2ee.opene2ee.vpn.util.VPNLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Keep
object NatSessionManager {
    @Keep
    private const val TAG = "NatSessionManager"

    /** portKey (Int) → NatSession. Thread-safe. */
    @Keep
    private val sessions = ConcurrentHashMap<Int, NatSession>()

    @Keep
    private val sweeper = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NatSession-Sweeper").apply { isDaemon = true }
    }

    init {
        sweeper.scheduleWithFixedDelay(
            ::clearExpiredSessions,
            60_000, 60_000, TimeUnit.MILLISECONDS
        )
    }

    @Keep
    fun getSession(portKey: Int): NatSession? = sessions[portKey]

    /**
     * Yeni session oluştur veya mevcut session'ı güncelle.
     *
     * **KRİTİK:** `localPort = portKey` set et (KURAL 5). Bu olmadan
     * PortHostService.refreshSessionInfo `getUid(session.localPort)`
     * -1 döndürür.
     */
    @Keep
    fun createSession(
        portKey: Int,
        remoteIp: Int,
        remotePort: Int,
        type: Int
    ): NatSession {
        val session = NatSession().apply {
            this.remoteIp = remoteIp
            this.remotePort = remotePort
            this.localPort = portKey          // ← KURAL 5: ASLA atla
            this.type = type
            this.lastRefreshTime = System.currentTimeMillis()
        }
        sessions[portKey] = session
        VPNLog.d(TAG, "createSession: portKey=$portKey, remote=${ipToString(remoteIp)}:${remotePort}, type=$type")
        return session
    }

    @Keep
    fun removeSession(portKey: Int) {
        sessions.remove(portKey)
    }

    @Keep
    fun clearAllSession() {
        sessions.clear()
        VPNLog.d(TAG, "clearAllSession")
    }

    @Keep
    fun size(): Int = sessions.size

    @Keep
    fun snapshot(): Collection<NatSession> = sessions.values

    /**
     * 60 sn'den eski session'ları temizle.
     * Reference repo'daki clearExpiredSessions ile birebir aynı.
     */
    @Keep
    fun clearExpiredSessions() {
        val now = System.currentTimeMillis()
        val it = sessions.entries.iterator()
        var removed = 0
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.lastRefreshTime > VPNConstants.SESSION_TIME_OUT_MS) {
                it.remove()
                removed++
            }
        }
        if (removed > 0) {
            VPNLog.d(TAG, "clearExpiredSessions: removed=$removed, remaining=${sessions.size}")
        }
    }

    /** Helper — debug log için IP'yi human-readable string'e çevir. */
    @Keep
    private fun ipToString(ip: Int): String {
        return "${(ip shr 24) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 8) and 0xFF}.${ip and 0xFF}"
    }
}
