package com.opene2ee.e2ee_ap_v2

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import com.opene2ee.e2ee_ap_v2.vpn.capture.PacketCapture
import com.opene2ee.e2ee_ap_v2.vpn.capture.SampledPacket
import com.opene2ee.e2ee_ap_v2.vpn.common.WireBare
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "MainActivity"

        /** MethodChannel name — must match `AppConfig.vpnChannelName`. */
        private const val CHANNEL = "com.opene2ee.e2ee_ap_v2/vpn"

        /**
         * Sprint 22.10+ — the `onPacketsSampled` event the
         * Kotlin side `invokeMethod`s to push a batch of
         * sampled packets to Dart. The Dart side wires this
         * in `VpnService._installChannelHandler`.
         */
        private const val EVENT_PACKETS_SAMPLED = "onPacketsSampled"

        private const val REQUEST_CODE_VPN_PREPARE = 100

        /**
         * Public DNS used when the system DNS is hijacked by
         * the VPN. 8.8.8.8 (Google) + 1.1.1.1 (Cloudflare) is
         * a robust, low-latency pair that works on every
         * carrier we tested. Sprint 22.12 may swap to a
         * hardcoded corporate resolver once the BFF exposes
         * one.
         */
        private val DEFAULT_DNS = arrayOf("8.8.8.8", "1.1.1.1")

        /**
         * `0.0.0.0/0` route → all IPv4 traffic flows through
         * the TUN. Without this the TUN is up but the OS
         * routes nothing into it (Sprint 22 default behaviour
         * — `WireBareConfiguration.routes` is empty until the
         * caller populates it).
         */
        private val DEFAULT_ROUTES = arrayOf(Pair("0.0.0.0", 0))
    }

    private var channel: MethodChannel? = null

    /**
     * Main-thread handler used to marshal `onPacketsSampled`
     * pushes from the drain coroutine (which runs on
     * `Dispatchers.IO`) to the Flutter platform channel
     * (which must be invoked on the main thread).
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val ch = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        channel = ch
        ch.setMethodCallHandler { call, result ->
            when (call.method) {
                "startVpn" -> handleStartVpn(result)
                "stopVpn" -> handleStopVpn(call, result)
                "status" -> handleStatus(result)
                "getSampledPackets" -> handleGetSampledPackets(result)
                "resetPacketCapture" -> handleResetPacketCapture(result)
                "captureStats" -> handleCaptureStats(result)
                else -> result.notImplemented()
            }
        }
        // Register the MethodChannel sink so the
        // 5-second drain coroutine in `PacketCapture` pushes
        // each batch to Dart. The drain stays dormant until
        // the first `startVpn` call.
        PacketCapture.registerSink { batch -> pushPacketsToDart(batch) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_VPN_PREPARE) {
            if (resultCode == Activity.RESULT_OK) {
                // User granted VPN permission — start VPN now.
                // The actual `startVpn` Dart call already
                // returned `consent_required`; this re-entry
                // closes the loop.
                launchProxy()
            }
            // RESULT_CANCELED: user declined, do nothing.
        }
    }

    override fun onDestroy() {
        // Detach the sink so a destroyed activity doesn't
        // keep a reference around — prevents leaking the
        // MethodChannel into a stale MainActivity.
        PacketCapture.registerSink(null)
        channel = null
        super.onDestroy()
    }

    // ─── startVpn / stopVpn ─────────────────────────────────────

    private fun handleStartVpn(result: MethodChannel.Result) {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, REQUEST_CODE_VPN_PREPARE)
            result.success("consent_required")
        } else {
            // Consent already granted, start VPN now.
            launchProxy()
            result.success("started")
        }
    }

    private fun handleStopVpn(
        call: io.flutter.plugin.common.MethodCall,
        result: MethodChannel.Result
    ) {
        val graceful = (call.argument<Boolean>("graceful") ?: true)
        // Flush the ring before tearing down the proxy so the
        // Dart side receives the tail of the session. Then
        // reset so the next `startVpn` does not see stale
        // samples.
        PacketCapture.stop(flush = graceful)
        WireBare.stopProxy()
        result.success("stopped")
    }

    private fun launchProxy() {
        // Reset the capture ring so a previous session's
        // samples don't leak into the new one. Idempotent.
        PacketCapture.reset()
        WireBare.startProxy {
            // Sprint 22.12 — wirebare config that actually
            // routes traffic. The default config (no
            // addRoutes / addDnsServers) leaves the TUN up
            // but routes nothing into it, so the dispatch
            // loop is idle. Sprint 22.10 reads packets from
            // that idle loop and reports zero — fixing the
            // config here is what makes the counter tick.
            addRoutes(*DEFAULT_ROUTES)
            addDnsServers(*DEFAULT_DNS)
        }
        // Start (or re-start) the 5-second drain coroutine.
        // Idempotent — no-op if already running.
        PacketCapture.start()
    }

    // ─── status / getSampledPackets / reset / stats ──────────────

    private fun handleStatus(result: MethodChannel.Result) {
        // Surface the wirebare `ProxyStatus` enum name. The
        // Dart-side `VpnService.status()` maps it to
        // `VpnLifecycleState` for the UI.
        result.success(WireBare.proxyStatus.name)
    }

    private fun handleGetSampledPackets(result: MethodChannel.Result) {
        // On-demand drain — returns the buffered samples
        // and empties the ring. Used by the Dart-side
        // 5-second poll in `pool_provider.dart`.
        val batch: List<SampledPacket> = PacketCapture.drain()
        result.success(batch.map { it.toMap() })
    }

    private fun handleResetPacketCapture(result: MethodChannel.Result) {
        PacketCapture.reset()
        result.success(null)
    }

    private fun handleCaptureStats(result: MethodChannel.Result) {
        val s = PacketCapture.stats()
        result.success(mapOf(
            "ringSize" to s.ringSize,
            "totalObserved" to s.totalObserved,
            "totalDropped" to s.totalDropped,
            "drainRunning" to s.drainRunning
        ))
    }

    // ─── event push ──────────────────────────────────────────────

    /**
     * Sink invoked by `PacketCapture.start()`'s drain
     * coroutine on `Dispatchers.IO`. Marshals to the main
     * thread before calling `MethodChannel.invokeMethod`
     * (the Flutter platform channel requires main-thread
     * access).
     */
    private fun pushPacketsToDart(batch: List<SampledPacket>) {
        if (batch.isEmpty()) return
        val payload: List<Map<String, Any?>> = batch.map { it.toMap() }
        mainHandler.post {
            // Re-read `channel` defensively — it may have
            // been nulled by `onDestroy` between the drain
            // tick and the main-thread post.
            val ch = channel ?: return@post
            ch.invokeMethod(EVENT_PACKETS_SAMPLED, payload)
        }
    }
}
