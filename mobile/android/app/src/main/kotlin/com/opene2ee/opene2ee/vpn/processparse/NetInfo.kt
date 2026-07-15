// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/processparse/NetInfo.kt
//
// Sprint 14 — /proc/net/(tcp|tcp6|udp|udp6) entry holder.

package com.opene2ee.opene2ee.vpn.processparse

import androidx.annotation.Keep

@Keep
class NetInfo {
    @Keep var sourPort: Int = 0   // local port (kaynak)
    @Keep var port: Int = 0       // remote port
    @Keep var ip: Long = 0
    @Keep var address: String = ""
    @Keep var uid: Int = 0
    @Keep var type: Int = 0       // TYPE_TCP=0, TYPE_TCP6=1, TYPE_UDP=2, TYPE_UDP6=3
}
