package com.opene2ee.opene2ee.vpn.config

import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep

@Keep
enum class VpnStatus {
    DEAD, STARTING, ACTIVE, DYING;

    companion object {
        @Volatile
        private var current: VpnStatus = DEAD
        private val listeners: MutableSet<(VpnStatus) -> Unit> = hashSetOf()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun current(): VpnStatus = current

        fun addListener(listener: (VpnStatus) -> Unit) {
            mainHandler.post { listeners.add(listener) }
        }

        fun removeListener(listener: (VpnStatus) -> Unit) {
            mainHandler.post { listeners.remove(listener) }
        }

        fun notify(newStatus: VpnStatus) {
            mainHandler.post {
                if (newStatus == current) return@post
                val old = current
                current = newStatus
                listeners.toList().forEach { it(newStatus) }
                // listeners removeAll on terminal status
                if (newStatus == DEAD || newStatus == DYING) {
                    listeners.clear()
                }
            }
        }
    }
}
