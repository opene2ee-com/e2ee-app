// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/processparse/PortHostService.kt
//
// Sprint 14 — UID lookup background service.
// Referans: huolizhuminh/NetWorkPacketCapture PortHostService.java + PR #33 fix.

package com.opene2ee.opene2ee.vpn.processparse

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.nat.NatSession
import com.opene2ee.opene2ee.vpn.nat.NatSessionManager
import com.opene2ee.opene2ee.vpn.util.VPNLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Keep
class PortHostService : Service() {
    @Keep
    companion object {
        private const val TAG = "PortHostService"
        @Keep
        @Volatile
        private var instance: PortHostService? = null

        @Keep
        @JvmStatic
        fun getInstance(): PortHostService? = instance

        @Keep
        @JvmStatic
        fun startParse(context: Context) {
            val intent = Intent(context, PortHostService::class.java)
            context.startService(intent)
        }

        @Keep
        @JvmStatic
        fun stopParse(context: Context) {
            val intent = Intent(context, PortHostService::class.java)
            context.stopService(intent)
        }
    }

    @Keep
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "PortHostService-Refresh").apply { isDaemon = true }
    }

    @Keep
    private var isRefresh = false

    @Keep
    private val refreshRunnable = Runnable { refreshSessionInfo() }

    override fun onCreate() {
        super.onCreate()
        NetFileManager.getInstance().init(applicationContext)
        instance = this
        // Her 5 saniyede bir refresh (NAT session UID lookup)
        executor.scheduleWithFixedDelay(refreshRunnable, 5, 5, TimeUnit.SECONDS)
        VPNLog.d(TAG, "PortHostService started")
    }

    override fun onDestroy() {
        executor.shutdown()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Tüm session'ların UID'sini /proc/net'ten resolve et.
     *
     * **KRİTİK — KURAL 5:** `session.localPort` kullan (remotePort DEĞİL).
     * /proc/net/tcp "local_address:port" kolonunda port var, remote kolonunda değil.
     */
    @Keep
    fun refreshSessionInfo() {
        if (isRefresh) return
        val allSessions: List<NatSession> = NatSessionManager.snapshot().toList()
        if (allSessions.isEmpty()) return

        isRefresh = true
        try {
            NetFileManager.getInstance().refresh()
            for (session in allSessions) {
                // ───── KURAL 5: localPort kullan ─────
                val uid = NetFileManager.getInstance().getUid(session.localPort)
                if (uid != null && session.uid == -1) {
                    session.uid = uid
                    VPNLog.d(TAG, "UID lookup: localPort=${session.localPort}, uid=$uid")
                }
            }
        } catch (e: Exception) {
            VPNLog.e(TAG, "refreshSessionInfo failed: ${e.message}")
        } finally {
            isRefresh = false
        }
    }
}
