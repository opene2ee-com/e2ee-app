package com.opene2ee.opene2ee.vpn

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import com.opene2ee.opene2ee.vpn.config.VpnConfiguration
import com.opene2ee.opene2ee.vpn.config.VpnStatus
import com.opene2ee.opene2ee.vpn.util.VPNLogger
import com.opene2ee.opene2ee.vpn.util.closeSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@Keep
class OpenE2eeVpnService : VpnService(), CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO) {

    companion object {
        const val TAG = "OpenE2eeVpnService"
        const val ACTION_START = "com.opene2ee.opene2ee.vpn.action.START"
        const val ACTION_STOP = "com.opene2ee.opene2ee.vpn.action.STOP"
        const val NOTIFICATION_CHANNEL_ID = "opene2ee.vpn.tunnel"
        const val NOTIFICATION_ID = 0x5650_4E4E  // 'VPNN'

        @Volatile
        private var currentInstance: OpenE2eeVpnService? = null

        val isRunning: Boolean
            get() = currentInstance != null

        @Keep
        @JvmStatic
        fun prepareSelf(context: android.content.Context): Intent? {
            return VpnService.prepare(context)
        }
    }

    private var proxyDescriptor: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        VPNLogger.i(TAG, "onCreate")
    }

    final override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VPNLogger.i(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
            else -> {
                VPNLogger.w(TAG, "unknown action ${intent?.action}")
                return START_NOT_STICKY
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun startVpn() {
        VPNLogger.i(TAG, "startVpn")
        startForegroundCompat()
        val configuration = VpnConfiguration.default()
        launch(Dispatchers.IO) {
            try {
                val fd = ProxyLauncher(this@OpenE2eeVpnService).launch(configuration)
                proxyDescriptor = fd
                VpnStatus.notify(VpnStatus.ACTIVE)
            } catch (e: Exception) {
                VPNLogger.e(TAG, "vpn launch failed", e)
                VpnStatus.notify(VpnStatus.DEAD)
                stopSelf()
            }
        }
    }

    private fun stopVpn() {
        VPNLogger.i(TAG, "stopVpn")
        launch(Dispatchers.IO) {
            proxyDescriptor?.closeSafely()
            proxyDescriptor = null
            VpnStatus.notify(VpnStatus.DYING)
        }
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        VPNLogger.i(TAG, "onDestroy")
        currentInstance = null
        cancel()
        VpnStatus.notify(VpnStatus.DEAD)
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        androidx.core.app.ServiceCompat.stopForeground(
            this,
            androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE
        )
    }

    private fun buildNotification(): Notification {
        val manager = androidx.core.app.NotificationManagerCompat.from(this)
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                androidx.core.app.NotificationChannelCompat.Builder(
                    NOTIFICATION_CHANNEL_ID,
                    androidx.core.app.NotificationManagerCompat.IMPORTANCE_DEFAULT
                ).setName("OpenE2EE Şifreleme Doğrulama").build()
            )
        }
        return androidx.core.app.NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OpenE2EE Şifreleme Doğrulama")
            .setContentText("Şifreleme bütünlüğü doğrulama oturumu aktif")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }
}
