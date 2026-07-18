package com.opene2ee.e2ee_ap_v2

import android.content.Intent
import android.net.VpnService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.opene2ee.e2ee_ap_v2/vpn"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startVpn" -> {
                        // VpnService.prepare handles the consent dialog the first time
                        val prepareIntent = VpnService.prepare(this)
                        if (prepareIntent != null) {
                            startActivityForResult(prepareIntent, REQUEST_CODE_VPN_PREPARE)
                        }
                        val startIntent = Intent("com.opene2ee.e2ee_ap_v2.vpn.core.action.Start")
                            .setPackage(packageName)
                        startService(startIntent)
                        result.success("started")
                    }
                    "stopVpn" -> {
                        val stopIntent = Intent("com.opene2ee.e2ee_ap_v2.vpn.core.action.Stop")
                            .setPackage(packageName)
                        startService(stopIntent)
                        result.success("stopped")
                    }
                    else -> result.notImplemented()
                }
            }
    }

    companion object {
        private const val REQUEST_CODE_VPN_PREPARE = 100
    }
}
