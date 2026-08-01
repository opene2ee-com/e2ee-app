/*
 * MIT License
 *
 * Copyright (c) 2025 opene2ee
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 */

package com.opene2ee.e2ee_ap_v2.vpn.capture

import com.opene2ee.e2ee_ap_v2.vpn.net.IPVersion
import com.opene2ee.e2ee_ap_v2.vpn.net.IpAddress
import com.opene2ee.e2ee_ap_v2.vpn.util.convertIPv4ToString

/**
 * Wire-format sample of a single IP packet that flowed through the
 * wirebare-kernel TUN. This is the Kotlin counterpart to the Dart
 * `SampledPacket` class (`lib/services/packet_parser.dart`).
 *
 * Privacy contract — ADR-0006 invariants:
 *  1. NO raw packet payload is ever retained. The parser walks
 *     only the IP + L4 header bytes and discards the rest.
 *  2. Source / destination IPs are masked at /24 (IPv4) or /48
 *     (IPv6) BEFORE they leave the TUN pipeline.
 *  3. We never capture source MAC, IMEI, MSISDN, location, etc.
 *
 * The `toMap()` shape MUST stay byte-identical to the Dart
 * `SampledPacket.toJson()` keys (see `lib/services/packet_parser.dart`
 * lines 418-430). The `MethodChannel` value is forwarded as a
 * `List<Map<String, Any?>>` and parsed by `SampledPacket.fromJson`
 * on the Flutter side.
 */
data class SampledPacket(

    /** IP version: 4 or 6. */
    val version: Int,

    /** L4 protocol name in lower-case: `tcp` / `udp` / `icmp` / `other`. */
    val protocol: String,

    /** Raw IP-protocol number (6 = TCP, 17 = UDP, 1 = ICMP, …). */
    val protocolNumber: Int,

    /**
     * Total length at capture time. For IPv4 this is the
     * `Total Length` header field; for IPv6 this is the
     * `Payload Length` (header bytes excluded). Field name
     * `packetLength` matches the Dart `SampledPacket.packetLength`
     * and the MethodChannel wire key.
     */
    val packetLength: Int,

    /** Source IP, masked to /24 (IPv4) or /48 (IPv6) per ADR-0006. */
    val srcIpMasked: String,

    /** Destination IP, masked to /24 (IPv4) or /48 (IPv6) per ADR-0006. */
    val dstIpMasked: String,

    /** TCP / UDP source port. `null` for ICMP and OTHER. */
    val srcPort: Int?,

    /** TCP / UDP destination port. `null` for ICMP and OTHER. */
    val dstPort: Int?,

    /**
     * TCP flags byte (SYN=0x02, ACK=0x10, FIN=0x01, RST=0x04, …).
     * `null` for UDP, ICMP, OTHER.
     */
    val tcpFlags: Int?,

    /**
     * Reserved — always `null` in Sprint 22.10. The wire key
     * `tlsClientHelloFingerprint` is preserved for a future
     * sprint when the Dart side will hash a bounded prefix of
     * the TCP payload.
     */
    val tlsClientHelloFingerprint: String? = null
) {

    /**
     * Wire-format map for the `MethodChannel` (`getSampledPackets`
     * returns, `onPacketsSampled` event push). Keys MUST match the
     * Dart `SampledPacket.toJson()` (see `packet_parser.dart`).
     */
    fun toMap(): Map<String, Any?> = buildMap {
        put("version", version)
        put("protocol", protocol)
        put("protocolNumber", protocolNumber)
        put("packetLength", packetLength)
        put("srcIpMasked", srcIpMasked)
        put("dstIpMasked", dstIpMasked)
        if (srcPort != null) put("srcPort", srcPort)
        if (dstPort != null) put("dstPort", dstPort)
        if (tcpFlags != null) put("tcpFlags", tcpFlags)
        if (tlsClientHelloFingerprint != null) {
            put("tlsClientHelloFingerprint", tlsClientHelloFingerprint)
        }
    }

    override fun toString(): String =
        "SampledPacket($srcIpMasked->$dstIpMasked " +
            "$protocol ${srcPort ?: "-"}->${dstPort ?: "-"} " +
            "len=$packetLength)"
}

/**
 * Decode the L4 protocol number into a lower-case protocol name
 * that matches the Dart `Protocol` enum's `name` (see
 * `lib/services/packet_parser.dart` lines 277-288). The Dart
 * `SampledPacket.fromJson` will round-trip the string back into
 * a number via `_protocolNameToNumber` if the `protocolNumber`
 * field is missing.
 */
internal fun protocolName(code: Byte): String = when (code.toInt() and 0xFF) {
    6 -> "tcp"
    17 -> "udp"
    1 -> "icmp"
    else -> "other"
}

/**
 * Mask an [IpAddress] at /24 (IPv4) or /48 (IPv6) per ADR-0006.
 * The returned string is in the same textual format as the
 * `IpAddress.stringIP` (full 4-octet for IPv4, full 8-hextet for
 * IPv6 — no `::` shorthand). Zero-padded for IPv6 hextets to
 * match the Dart `_maskIpv6` output (which uses
 * `toRadixString(16)` without padding, so this can be any hex
 * substring; the consumer never parses these values).
 */
internal fun maskIpAddress(addr: IpAddress): String = when (addr.ipVersion) {
    IPVersion.IPv4 -> {
        val masked = addr.intIPv4 and 0xFFFFFF00.toInt()
        masked.convertIPv4ToString
    }
    IPVersion.IPv6 -> {
        val original = addr.intIPv6
        // /48 mask: keep top 48 bits of the 128-bit address, zero
        // the remaining 80. The high64 holds bits 64..127; we
        // keep its top 48 (mask 0xFFFFFFFFFFFF0000) and zero the
        // bottom 16. The low64 (bits 0..63) is zeroed entirely.
        val maskedHigh64 = original.high64 and 0xFFFFFFFFFFFF0000uL.toLong()
        val maskedLow64 = 0L
        String.format(
            "%s:%s:%s:%s:%s:%s:%s:%s",
            (maskedHigh64 ushr 48 and 0xFFFF).toString(16),
            (maskedHigh64 ushr 32 and 0xFFFF).toString(16),
            (maskedHigh64 ushr 16 and 0xFFFF).toString(16),
            (maskedHigh64 and 0xFFFF).toString(16),
            (maskedLow64 ushr 48 and 0xFFFF).toString(16),
            (maskedLow64 ushr 32 and 0xFFFF).toString(16),
            (maskedLow64 ushr 16 and 0xFFFF).toString(16),
            (maskedLow64 and 0xFFFF).toString(16)
        )
    }
}
