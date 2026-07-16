// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/MainActivity.kt
//
// Sprint 14 — VpnService MethodChannel + permission handshake.

package com.opene2ee.opene2ee

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import com.opene2ee.opene2ee.vpn.OpenE2eeVpnService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val VPN_REQUEST_CODE = 0x7B_50_4E
        private const val PERMISSIONS_CHANNEL = "opene2ee/vpn_permissions"
    }

    private var permissionsChannel: MethodChannel? = null
    private var vpnChannel: MethodChannel? = null
    private var pendingVpnResult: MethodChannel.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Log.d(TAG, "configureFlutterEngine: ENTER")

        vpnChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            OpenE2eeVpnService.METHOD_CHANNEL
        ).apply {
            setMethodCallHandler { call, result -> vpnDispatch(call, result) }
        }

        permissionsChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            PERMISSIONS_CHANNEL
        ).apply {
            setMethodCallHandler(::onPermissionsCall)
        }
        Log.d(TAG, "configureFlutterEngine: DONE")
    }

    private fun vpnDispatch(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "start" -> {
                val svc = OpenE2eeVpnService.activeInstance
                if (svc == null) {
                    val intent = Intent(this, OpenE2eeVpnService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    result.success(mapOf("state" to "STARTING"))
                } else {
                    result.success(mapOf("state" to "ACTIVE"))
                }
            }
            "stop" -> {
                val svc = OpenE2eeVpnService.activeInstance
                if (svc != null) {
                    try { svc.stopVpn() } catch (e: Throwable) {
                        Log.w(TAG, "svc.stopVpn() failed: ${e.message}")
                    }
                }
                try { stopService(Intent(this, OpenE2eeVpnService::class.java)) }
                catch (e: Throwable) { Log.w(TAG, "stopService failed: ${e.message}") }
                result.success(mapOf("state" to "STOPPED"))
            }
            "status" -> {
                val state = if (OpenE2eeVpnService.activeInstance != null) "ACTIVE" else "IDLE"
                result.success(mapOf("state" to state))
            }
            else -> result.notImplemented()
        }
    }

    private fun onPermissionsCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "requestVpnPermission" -> requestVpnPermission(result)
            "isVpnPrepared" -> result.success(isVpnPrepared())
            else -> result.notImplemented()
        }
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
        val pending = pendingVpnResult ?: return
        pendingVpnResult = null
        when (resultCode) {
            Activity.RESULT_OK -> pending.success(true)
            Activity.RESULT_CANCELED -> pending.success(false)
            else -> pending.error("vpn_prepare_unknown_result", "resultCode=$resultCode", null)
        }
    }

    override fun onDestroy() {
        permissionsChannel?.setMethodCallHandler(null)
        permissionsChannel = null
        vpnChannel?.setMethodCallHandler(null)
        vpnChannel = null
        super.onDestroy()
    }
}
