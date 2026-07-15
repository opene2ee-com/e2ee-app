// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/util/DebugLog.kt
//
// Sprint 14 — Formatlı debug log.
// Referans: huolizhuminh/NetWorkPacketCapture DebugLog.java.

package com.opene2ee.opene2ee.vpn.util

import androidx.annotation.Keep

@Keep
object DebugLog {
    @Keep
    private const val DEFAULT_TAG = "OpenE2eeVpn"

    @Keep
    @JvmStatic
    fun i(format: String, vararg args: Any?) {
        if (VPNLog.isMakeDebugLog) {
            android.util.Log.i(DEFAULT_TAG, formatString(format, args))
        }
    }

    @Keep
    @JvmStatic
    fun d(format: String, vararg args: Any?) {
        if (VPNLog.isMakeDebugLog) {
            android.util.Log.d(DEFAULT_TAG, formatString(format, args))
        }
    }

    @Keep
    @JvmStatic
    fun w(format: String, vararg args: Any?) {
        if (VPNLog.isMakeDebugLog) {
            android.util.Log.w(DEFAULT_TAG, formatString(format, args))
        }
    }

    @Keep
    @JvmStatic
    fun e(format: String, vararg args: Any?) {
        if (VPNLog.isMakeDebugLog) {
            android.util.Log.e(DEFAULT_TAG, formatString(format, args))
        }
    }

    @Keep
    private fun formatString(format: String, args: Array<out Any?>): String {
        return if (args.isEmpty()) format else String.format(format, *args)
    }
}
