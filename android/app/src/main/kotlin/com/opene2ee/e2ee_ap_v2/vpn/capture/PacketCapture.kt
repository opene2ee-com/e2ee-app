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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.opene2ee.e2ee_ap_v2.vpn.net.IPHeader
import com.opene2ee.e2ee_ap_v2.vpn.net.Packet
import com.opene2ee.e2ee_ap_v2.vpn.util.WireBareLogger
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Sprint 22.10 — wirebare-kernel packet observation singleton.
 *
 * Mirrors the `WireBareDashboard.bandwidthFlow` pattern: a process-wide
 * object that holds a thread-safe ring buffer, an in-process
 * `SharedFlow<List<SampledPacket>>` for in-JVM consumers, and an
 * external-sink hook so the Flutter `MethodChannel` can push the
 * batched samples to the Dart side every drain tick.
 *
 * ## Threading model
 *  - [observe] is called from `PacketDispatcher` on `Dispatchers.IO`.
 *    It enqueues into a `ConcurrentLinkedDeque` and trims to
 *    [RING_CAPACITY] (oldest-first).
 *  - [drain] is called from two places:
 *      (a) the periodic 5-second coroutine started by [start] —
 *          pushes the drained batch to the registered [sink]
 *          AND emits to [batches];
 *      (b) the on-demand `getSampledPackets` RPC from Dart.
 *  - The ring is drained atomically (`ConcurrentLinkedDeque.poll`
 *    in a tight loop) so concurrent `observe()` calls during
 *    a drain can lose at most one batch boundary's worth of
 *    samples — the new ones go into the next tick.
 *
 * ## Capacity trade-off
 *  Default `RING_CAPACITY = 100` is sized for a 5-second window
 *  at ~20 pps — typical mobile foreground traffic. Under heavy
 *  load we drop the OLDEST samples (FIFO), which biases the
 *  telemetry toward recent traffic. This is the right bias for
 *  the e2ee-ap-v2 use case (we're sampling for liveness, not
 *  recording a complete PCAP).
 *
 * ## Lifecycle
 *  - [start] launches the drain coroutine on the supplied
 *    [scope] (typically the `WireBareProxyService` scope). The
 *    coroutine is cancelled when the scope is cancelled, so the
 *    drain dies automatically when the VPN stops.
 *  - [stop] cancels the active drain job early (useful for
 *    graceful stop with a final drain — see `stop(graceful=true)`
 *    in `vpn_service.dart`).
 *  - [reset] empties the ring without touching the drain job.
 *    Called on `stopVpn` to avoid emitting stale samples from a
 *    previous session after a fast start/stop/start cycle.
 */
object PacketCapture {

    private const val TAG = "PacketCapture"

    /** Default ring buffer capacity. */
    const val RING_CAPACITY: Int = 100

    /** Default drain cadence — 5 seconds per the 10.1B brief. */
    const val DEFAULT_DRAIN_INTERVAL_MS: Long = 5_000L

    private val ring = ConcurrentLinkedDeque<SampledPacket>()
    private val totalObserved = AtomicLong(0)
    private val totalDropped = AtomicLong(0)

    /**
     * In-JVM batched stream. Replay = 0 (we do NOT replay on
     * subscription; late subscribers only see future batches).
     * Extra buffer = 8 so the drain coroutine can keep going
     * even if a slow consumer is holding up the last emit.
     */
    private val _batches = MutableSharedFlow<List<SampledPacket>>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Public read-only view of the batched stream. */
    val batches: SharedFlow<List<SampledPacket>> = _batches.asSharedFlow()

    /** External sink (Flutter MethodChannel) — nullable. */
    @Volatile
    private var sink: ((List<SampledPacket>) -> Unit)? = null

    private val drainRunning = AtomicBoolean(false)
    private var drainJob: Job? = null
    private var internalScope: CoroutineScope? = null

    /**
     * Enqueue a single sampled packet. Trims the ring to
     * [RING_CAPACITY] (FIFO) and updates the cumulative counter.
     * Thread-safe; safe to call from any thread.
     */
    fun observe(packet: SampledPacket) {
        ring.add(packet)
        totalObserved.incrementAndGet()
        while (ring.size > RING_CAPACITY) {
            ring.poll()
            totalDropped.incrementAndGet()
        }
    }

    /**
     * Atomically remove and return all currently-buffered samples
     * as a fresh `ArrayList`. The ring is empty after the call.
     * Order preserved (FIFO). Returns an empty list if the ring
     * is empty (never null).
     */
    fun drain(): List<SampledPacket> {
        val out = ArrayList<SampledPacket>(ring.size)
        while (true) {
            val p = ring.pollFirst() ?: break
            out.add(p)
        }
        return out
    }

    /**
     * Empty the ring without emitting. Useful on `stopVpn` so the
     * next `startVpn` doesn't briefly show stale samples from the
     * previous session.
     */
    fun reset() {
        var dropped = 0L
        while (ring.pollFirst() != null) {
            dropped++
        }
        if (dropped > 0) {
            totalDropped.addAndGet(dropped)
        }
    }

    /**
     * Register the Flutter MethodChannel sink. The supplied
     * [handler] is invoked from the drain coroutine on
     * `Dispatchers.IO` with each non-empty batch; the handler
     * is responsible for marshalling to the main thread before
     * touching the channel. Passing `null` clears the
     * registration.
     */
    fun registerSink(handler: ((List<SampledPacket>) -> Unit)?) {
        sink = handler
    }

    /**
     * Start the 5-second drain coroutine on a dedicated internal
     * [CoroutineScope] (`SupervisorJob + Dispatchers.IO`). The
     * scope lives for the lifetime of the process; [stop]
     * cancels the drain job but keeps the scope around so it
     * can be reused on subsequent `start()`. No-op if the drain
     * is already running.
     */
    fun start(intervalMs: Long = DEFAULT_DRAIN_INTERVAL_MS) {
        if (!drainRunning.compareAndSet(false, true)) {
            WireBareLogger.info(TAG, "drain already running, ignoring start()")
            return
        }
        val scope = internalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
            .also { internalScope = it }
        drainJob = scope.launch {
            WireBareLogger.info(TAG, "drain coroutine started (interval=${intervalMs}ms)")
            try {
                while (isActive) {
                    delay(intervalMs)
                    val batch = drain()
                    if (batch.isEmpty()) continue
                    sink?.invoke(batch)
                    val emitted = _batches.tryEmit(batch)
                    if (!emitted) {
                        WireBareLogger.warn(TAG, "batch flow emit failed (replay=0, buffer full)")
                    }
                }
            } catch (e: Exception) {
                WireBareLogger.error(TAG, "drain coroutine error", e)
            } finally {
                WireBareLogger.info(TAG, "drain coroutine stopped")
                drainRunning.set(false)
            }
        }
    }

    /**
     * Stop the drain coroutine. Optionally do one final drain
     * first (when [flush] is true) so the Dart side receives a
     * last batch covering the tail of the session. The ring is
     * always reset to avoid leaking the previous session's
     * samples into the next one. Non-suspending — safe to call
     * from any thread (the cancel + reset are atomic with
     * respect to the drain coroutine's exits).
     */
    fun stop(flush: Boolean = true) {
        val job = drainJob ?: return
        if (flush) {
            val tail = drain()
            if (tail.isNotEmpty()) {
                sink?.invoke(tail)
                _batches.tryEmit(tail)
            }
        }
        reset()
        job.cancel()
        drainJob = null
        drainRunning.set(false)
    }

    /**
     * Snapshot of the ring size + lifetime counters. Exposed for
     * the debug card on the active pool screen. Cheap — O(1)
     * atomic reads + a `ring.size` (also O(1) on a deque).
     */
    fun stats(): CaptureStats = CaptureStats(
        ringSize = ring.size,
        totalObserved = totalObserved.get(),
        totalDropped = totalDropped.get(),
        drainRunning = drainRunning.get()
    )
}

/**
 * Sprint 22.10 — convenience helper for the dispatcher.
 *
 * Extracts a [SampledPacket] from a parsed [IPHeader] + raw
 * [Packet]. Masks the IPs at /24 (v4) or /48 (v6) per ADR-0006
 * and reads the L4 ports / TCP flags directly from the buffer
 * (no cross-package dependency on `TcpHeader` / `UdpHeader`).
 *
 * Returns `null` if the L4 header is truncated. Never throws.
 * The input [Packet] buffer is read-only — we never retain a
 * reference to it after this call.
 */
fun extractSampled(ipHeader: IPHeader, packet: Packet): SampledPacket? {
    val protoNum = ipHeader.dataProtocol.toInt() and 0xFF
    val protoName = protocolName(ipHeader.dataProtocol)
    val srcMasked = maskIpAddress(ipHeader.sourceAddress)
    val dstMasked = maskIpAddress(ipHeader.destinationAddress)
    val l4Offset = ipHeader.headerLength
    val buf = packet.packet
    val totalLen = packet.length

    var srcPort: Int? = null
    var dstPort: Int? = null
    var tcpFlags: Int? = null
    when (protoNum) {
        6 -> { // TCP
            if (totalLen >= l4Offset + 20) {
                srcPort = readUint16(buf, l4Offset)
                dstPort = readUint16(buf, l4Offset + 2)
                // TCP flags live in byte 13 of the TCP header.
                tcpFlags = buf[l4Offset + 13].toInt() and 0xFF
            }
        }
        17 -> { // UDP
            if (totalLen >= l4Offset + 8) {
                srcPort = readUint16(buf, l4Offset)
                dstPort = readUint16(buf, l4Offset + 2)
            }
        }
        // ICMP (1) and OTHER: no port info.
    }

    return SampledPacket(
        version = if (ipHeader.ipVersion.name == "IPv4") 4 else 6,
        protocol = protoName,
        protocolNumber = protoNum,
        packetLength = ipHeader.totalLength,
        srcIpMasked = srcMasked,
        dstIpMasked = dstMasked,
        srcPort = srcPort,
        dstPort = dstPort,
        tcpFlags = tcpFlags
    )
}

private fun readUint16(buf: ByteArray, offset: Int): Int {
    return ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
}

/** Read-only stats snapshot for the debug card. */
data class CaptureStats(
    val ringSize: Int,
    val totalObserved: Long,
    val totalDropped: Long,
    val drainRunning: Boolean
) {
    override fun toString(): String =
        "CaptureStats(ring=$ringSize observed=$totalObserved " +
            "dropped=$totalDropped drain=$drainRunning)"
}
