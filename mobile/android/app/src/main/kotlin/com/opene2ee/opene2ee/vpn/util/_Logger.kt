package com.opene2ee.opene2ee.vpn.util

import android.util.Log

object VPNLogger {

    @Volatile var level: Level = Level.INFO
    private var proxy: Proxy = DefaultProxy

    fun v(tag: String, msg: String, cause: Throwable? = null) { if (level.value <= Level.VERBOSE.value) proxy.v(tag, msg, cause) }
    fun d(tag: String, msg: String, cause: Throwable? = null) { if (level.value <= Level.DEBUG.value) proxy.d(tag, msg, cause) }
    fun i(tag: String, msg: String, cause: Throwable? = null) { if (level.value <= Level.INFO.value) proxy.i(tag, msg, cause) }
    fun w(tag: String, msg: String, cause: Throwable? = null) { if (level.value <= Level.WARN.value) proxy.w(tag, msg, cause) }
    fun e(tag: String, msg: String, cause: Throwable? = null) { if (level.value <= Level.ERROR.value) proxy.e(tag, msg, cause) }

    enum class Level(val value: Int) {
        VERBOSE(1), DEBUG(2), INFO(3), WARN(4), ERROR(5)
    }

    interface Proxy {
        fun v(tag: String, msg: String, cause: Throwable?)
        fun d(tag: String, msg: String, cause: Throwable?)
        fun i(tag: String, msg: String, cause: Throwable?)
        fun w(tag: String, msg: String, cause: Throwable?)
        fun e(tag: String, msg: String, cause: Throwable?)
    }

    object DefaultProxy : Proxy {
        override fun v(tag: String, msg: String, cause: Throwable?) { Log.v(tag, msg, cause) }
        override fun d(tag: String, msg: String, cause: Throwable?) { Log.d(tag, msg, cause) }
        override fun i(tag: String, msg: String, cause: Throwable?) { Log.i(tag, msg, cause) }
        override fun w(tag: String, msg: String, cause: Throwable?) { Log.w(tag, msg, cause) }
        override fun e(tag: String, msg: String, cause: Throwable?) { Log.e(tag, msg, cause) }
    }
}
