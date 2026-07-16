// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/Packet.kt
//
// Sprint 14 — Paket veri yapısı (IP+TCP/UDP header'lar shared byte[] üzerinde).
// Referans: huolizhuminh/NetWorkPacketCapture Packet.java + IPHeader/TCPHeader/UDPHeader.java.

package com.opene2ee.opene2ee.vpn

import androidx.annotation.Keep

@Keep
class Packet {
    @Keep var mData: ByteArray = ByteArray(0)
    @Keep var mOffset: Int = 0

    @Keep constructor()

    @Keep
    constructor(data: ByteArray, offset: Int) {
        this.mData = data
        this.mOffset = offset
    }
}
