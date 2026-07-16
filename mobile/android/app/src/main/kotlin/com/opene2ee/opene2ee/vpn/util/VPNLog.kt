// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/util/VPNLog.kt
//
// Sprint 14 — R8-safe log wrapper. R8 release build'de Log.d/v strip
// edebilir, ama wrapper'lar çağrıldığı için sınıf adları keep edilir.
// Referans: huolizhuminh/NetWorkPacketCapture VPNLog.java.

package com.opene2ee.opene2ee.vpn.util

import android.util.Log
import androidx.annotation.Keep

@Keep
object VPNLog {
    @Keep
    @JvmStatic
    var isMakeDebugLog: Boolean = true

    @Keep
    @JvmStatic
    fun d(tag: String, message: String) {
        if (isMakeDebugLog) Log.d(tag, message)
    }

    @Keep
    @JvmStatic
    fun i(tag: String, message: String) {
        if (isMakeDebugLog) Log.i(tag, message)
    }

    @Keep
    @JvmStatic
    fun w(tag: String, message: String) {
        if (isMakeDebugLog) Log.w(tag, message)
    }

    @Keep
    @JvmStatic
    fun w(tag: String, message: String, e: Throwable) {
        if (isMakeDebugLog) Log.w(tag, message, e)
    }

    @Keep
    @JvmStatic
    fun e(tag: String, message: String) {
        if (isMakeDebugLog) Log.e(tag, message)
    }

    @Keep
    @JvmStatic
    fun e(tag: String, message: String, t: Throwable) {
        if (isMakeDebugLog) Log.e(tag, message, t)
    }
}
