// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/MainActivity.kt
//
// Sprint 16 — VpnService MethodChannel + permission handshake.
// wirebare-tarzı handleVpnCommand (Action START/STOP) + preserved
// permissionsChannel (opene2ee/vpn_permissions) for dart-side
// requestVpnPermission backward compat.

package com.opene2ee.opene2ee

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import com.opene2ee.opene2ee.vpn.OpenE2eeVpnService
import com.opene2ee.opene2ee.vpn.config.VpnStatus
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "MainActivity"
        // Spec §11.2 — new wirebare-tarzı start flow uses 1001.
        // The legacy requestVpnPermission path on the permissions
        // channel also uses this value; onActivityResult dispatches
        // by pendingVpnResult state to keep both paths independent.
        private const val VPN_REQUEST_CODE = 1001
        private const val NOTIFICATION_REQUEST_CODE = 1002
        private const val PERMISSIONS_CHANNEL = "opene2ee/vpn_permissions"
        private const val VPN_CHANNEL = "opene2ee/vpn"
    }

    private var permissionsChannel: MethodChannel? = null
    private var vpnChannel: MethodChannel? = null
    private var vpnFlutterEngine: FlutterEngine? = null
    private var pendingVpnResult: MethodChannel.Result? = null
    private var pendingNotificationResult: MethodChannel.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Log.d(TAG, "configureFlutterEngine: ENTER")
        vpnFlutterEngine = flutterEngine

        vpnChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            VPN_CHANNEL
        ).apply {
            setMethodCallHandler { call, result -> handleVpnCommand(call, result) }
        }

        permissionsChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            PERMISSIONS_CHANNEL
        ).apply {
            setMethodCallHandler(::onPermissionsCall)
        }
        Log.d(TAG, "configureFlutterEngine: DONE")
    }

    /**
     * Sprint 16 — wirebare-tarzı VpnService MethodChannel handler.
     * Maps Dart `opene2ee/vpn` "start" / "stop" / "status" calls to
     * ACTION_START/STOP Intents on OpenE2eeVpnService. The "start"
     * method handles the VpnService.prepare() permission dialog
     * inline; on RESULT_OK it issues an ACTION_START foreground
     * service intent.
     */
    private fun handleVpnCommand(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "start" -> {
                val prepareIntent = VpnService.prepare(this)
                if (prepareIntent != null) {
                    try {
                        @Suppress("DEPRECATION")
                        startActivityForResult(prepareIntent, VPN_REQUEST_CODE)
                        result.success(mapOf("status" to "permission_needed"))
                    } catch (e: Throwable) {
                        Log.w(TAG, "startActivityForResult failed: ${e.message}")
                        result.error("vpn_prepare_launch_failed", e.message, null)
                    }
                } else {
                    startVpnService()
                    result.success(mapOf("status" to "starting"))
                }
            }
            "stop" -> {
                val stopIntent = Intent(this, OpenE2eeVpnService::class.java).apply {
                    action = OpenE2eeVpnService.ACTION_STOP
                }
                try {
                    startService(stopIntent)
                } catch (e: Throwable) {
                    Log.w(TAG, "startService(STOP) failed: ${e.message}")
                }
                result.success(mapOf("status" to "stopping"))
            }
            "status" -> {
                result.success(mapOf("status" to VpnStatus.current().name))
            }
            "getSampledPackets" -> {
                result.success(OpenE2eeVpnService.drainSampledPackets())
            }
            else -> result.notImplemented()
        }
    }

    private fun startVpnService() {
        val startIntent = Intent(this, OpenE2eeVpnService::class.java).apply {
            action = OpenE2eeVpnService.ACTION_START
        }
        androidx.core.content.ContextCompat.startForegroundService(this, startIntent)
    }

    /**
     * Dart side preserved requestVpnPermission (opene2ee/vpn_permissions)
     * — used by VpnService.requestAndStart(). Returns true/false to
     * the pending MethodChannel.Result rather than driving the
     * ACTION_START intent itself, so the dart flow stays in charge
     * of when to invoke the main channel's "start".
     */
    private fun onPermissionsCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "requestVpnPermission" -> requestVpnPermission(result)
            "isVpnPrepared" -> result.success(isVpnPrepared())
            "ensureNotificationPermission" -> ensureNotificationPermission(result)
            else -> result.notImplemented()
        }
    }

    private fun ensureNotificationPermission(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            result.success(true)
            return
        }
        if (pendingNotificationResult != null) {
            result.error("notification_permission_in_flight", "Already awaiting notification permission", null)
            return
        }
        pendingNotificationResult = result
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST_CODE)
    }

    @RequiresApi(21)
    private fun requestVpnPermission(result: MethodChannel.Result) {
        if (pendingVpnResult != null) {
            result.error("vpn_prepare_in_flight", "Already awaiting", null)
            return
        }
        when (val intent = VpnService.prepare(this)) {
            null -> result.success(true)
            else -> {
                pendingVpnResult = result
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, VPN_REQUEST_CODE)
                } catch (e: Throwable) {
                    pendingVpnResult = null
                    result.error("vpn_prepare_launch_failed", e.message, null)
                }
            }
        }
    }

    @RequiresApi(21)
    private fun isVpnPrepared(): Boolean = VpnService.prepare(this) == null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_REQUEST_CODE) return

        // Spec §11.2 — wirebare-tarzı start flow: when RESULT_OK from
        // the new handleVpnCommand "start" path (no pendingVpnResult
        // because that path didn't stash one), kick off the
        // foreground service and notify Dart via onPermissionGranted.
        if (resultCode == Activity.RESULT_OK && pendingVpnResult == null) {
            startVpnService()
            vpnFlutterEngine?.let { engine ->
                MethodChannel(engine.dartExecutor.binaryMessenger, VPN_CHANNEL)
                    .invokeMethod("onPermissionGranted", null)
            }
            return
        }

        // Backward-compat path: dart-side requestVpnPermission on the
        // permissions channel stashed a pending result, complete it now.
        val pending = pendingVpnResult ?: return
        pendingVpnResult = null
        when (resultCode) {
            Activity.RESULT_OK -> pending.success(true)
            Activity.RESULT_CANCELED -> pending.success(false)
            else -> pending.error("vpn_prepare_unknown_result", "resultCode=$resultCode", null)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NOTIFICATION_REQUEST_CODE) return
        val pending = pendingNotificationResult ?: return
        pendingNotificationResult = null
        pending.success(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
    }

    override fun onDestroy() {
        permissionsChannel?.setMethodCallHandler(null)
        permissionsChannel = null
        pendingNotificationResult = null
        vpnChannel?.setMethodCallHandler(null)
        vpnChannel = null
        vpnFlutterEngine = null
        super.onDestroy()
    }
}
