package com.opene2ee.opene2ee.vpn.util

import com.opene2ee.opene2ee.vpn.net.IntIPv6

internal val Int.convertIPv4ToString: String
    get() = String.format(
        "%s.%s.%s.%s",
        this shr 24 and 0xFF, this shr 16 and 0xFF, this shr 8 and 0xFF, this and 0xFF
    )

internal val String.convertIPv4ToInt: Int
    get() = split(".").let { n ->
        (n[0].toInt() and 0xFF shl 24) or
        (n[1].toInt() and 0xFF shl 16) or
        (n[2].toInt() and 0xFF shl 8) or
        n[3].toInt() and 0xFF
    }

internal val IntIPv6.convertIPv6ToString: String
    get() = String.format(
        "%s:%s:%s:%s:%s:%s:%s:%s",
        (this.high64 shr 48 and 0xFFFF).toString(16),
        (this.high64 shr 32 and 0xFFFF).toString(16),
        (this.high64 shr 16 and 0xFFFF).toString(16),
        (this.high64 and 0xFFFF).toString(16),
        (this.low64 shr 48 and 0xFFFF).toString(16),
        (this.low64 shr 32 and 0xFFFF).toString(16),
        (this.low64 shr 16 and 0xFFFF).toString(16),
        (this.low64 and 0xFFFF).toString(16)
    )

internal val String.convertIPv6ToInt: IntIPv6
    get() = split(":").let { n ->
        IntIPv6(
            (n[0].toLong(16) and 0xFFFF shl 48) or
            (n[1].toLong(16) and 0xFFFF shl 32) or
            (n[2].toLong(16) and 0xFFFF shl 16) or
            (n[3].toLong(16) and 0xFFFF),
            (n[4].toLong(16) and 0xFFFF shl 48) or
            (n[5].toLong(16) and 0xFFFF shl 32) or
            (n[6].toLong(16) and 0xFFFF shl 16) or
            (n[7].toLong(16) and 0xFFFF)
        )
    }

internal val Short.convertPortToInt: Int get() = this.toInt() and 0xFFFF
internal val Short.convertPortToString: String get() = (this.toInt() and 0xFFFF).toString()
