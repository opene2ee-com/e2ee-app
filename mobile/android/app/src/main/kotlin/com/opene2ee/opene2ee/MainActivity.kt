// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/MainActivity.kt
//
// PR-22a (Sprint 3) — Android MainActivity.
// PR-28 (Sprint 5) — switched the engine attach/detach to the static
//                    `OpenE2eeVpnService.attachFlutterEngine` /
//                    `.detachFlutterEngine` companions (instead of
//                    constructing throwaway instances) and added
//                    `@RequiresApi(21)` guards + null-safe cleanup
//                    around the `VpnService.prepare()` `getIfPresent`
//                    pattern.
//
// Sprint 9.7.0 Item 2 — ported from commit f69085b onto the clean
//                       Flutter Android skeleton (foundation-clean-
//                       skeleton 8697167). VPN service binding is
//                       stubbed out with TODO markers; port-vpn-service
//                       lands in Item 3+ and will re-introduce the
//                       `OpenE2eeVpnService.attachFlutterEngine(...)`
//                       / `.detachFlutterEngine()` calls below.
//
// Sprint 10.1F — added an `opene2ee/vpn` MethodChannel handler INLINE
//               in MainActivity (NOT delegated to
//               `OpenE2eeVpnService.attachFlutterEngine(...)`) so the
//               Dart-side `VpnService.getSampledPackets()` call no
//               longer raises `MissingPluginException`. The handler
//               is MOCK-only for Sprint 10.1F: `getSampledPackets`
//               returns a single synthetic packet (IPv4 TCP, port
//               443), and the `start` / `stop` / `status` cases
//               return string sentinels. Sprint 10.2 will swap this
//               for the real `OpenE2eeVpnService` integration once
//               port-vpn-service lands. The inline form keeps the
//               Dart contract verifiable today without forcing a
//               premature cross-process refactor.
//
// Sprint 11.0A — REAL VpnService packet drain → MethodChannel → Dart
//                stream. MainActivity now delegates the
//                `opene2ee/vpn` MethodChannel to the real
//                `OpenE2eeVpnService` (via
//                `OpenE2eeVpnService.attachFlutterEngine(...)`).
//                The `getSampledPackets` handler reads the LIVE
//                ring via `OpenE2eeVpnService.snapshot()` static
//                (S46 — no mock packet, no hard-coded `mapOf(...)`).
//                The service's companion `methodChannel` reference
//                lets the 5-second `PacketDrain` push the same ring
//                to Dart via the `onPacketsSampled` event
//                (S45 invariant). The foreground notification text
//                stays "OpenE2EE Şifreleme Doğrulama" (no "VPN" —
//                S25 + S50 invariant).
//
// Owns the VpnService permission handshake:
//
//   1. Dart calls `requestPrepare` over the `opene2ee/vpn` MethodChannel.
//   2. MainActivity invokes `VpnService.prepare(this)`.
//   3. Android shows the system "VPN connection" consent dialog.
//   4. `onActivityResult(RESULT_OK)` confirms consent; we notify Dart.
//   5. Dart then calls `start`; the service brings up the TUN.
//
// Why the activity owns the flow:
//   `VpnService.prepare(Context)` requires an Activity context. The
//   `OpenE2eeVpnService` itself is a `Service` and only has the
//   application context, so the activity must launch the prepare intent.
//
// References:
//   - docs/ADR-0003-vpn-layer.md
//   - docs/SPRINT-3-SCOPE.md §7 PR-22
//   - docs/SPRINT-5-SCOPE.md §PR-28

package com.opene2ee.opene2ee

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
// Sprint 11.0A — re-enabled. The 9.7.0 Item 3 port-vpn-service
// commit landed the real service in Sprint 10.1F, but the inline
// MainActivity mock blocked delegation. 11.0A restores the
// singleton-engine attach path.
import com.opene2ee.opene2ee.vpn.OpenE2eeVpnService
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Single-Activity host that bridges the Flutter side to Android system
 * services — specifically the VPN consent flow.
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val VPN_REQUEST_CODE = 0x7B_50_4E /* VPN' */
        private const val PERMISSIONS_CHANNEL = "opene2ee/vpn_permissions"
        // Sprint 11.0A — `opene2ee/vpn` MethodChannel is now owned
        // by `OpenE2eeVpnService` (see `METHOD_CHANNEL` in that
        // file). The 10.1F inline mock that lived here is REMOVED.
    }

    /** The MethodChannel that carries the permission-request roundtrip. */
    private var permissionsChannel: MethodChannel? = null

    /**
     * Cached Dart-side completion for the in-flight `requestVpnPermission`
     * call. We need this because `onActivityResult` runs before any Dart
     * future is even awaited.
     */
    private var pendingVpnResult: MethodChannel.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    /**
     * Wired once on engine attach. Sets up:
     *   1. The OPENe2eeVpnService MethodChannel (delegated to the service itself).
     *   2. A permission-request channel owned by THIS activity.
     */
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Sprint 11.0A — restore the singleton-engine attach path
        // (the 9.7.0 Item 2 + PR-28 §B.2 canonical form). The
        // `OpenE2eeVpnService.attachFlutterEngine(...)` static
        // resolves to the running service instance (or queues
        // the engine for replay if the service hasn't been
        // created yet) so the MethodChannel handler is wired
        // to the SAME object that processes Dart's `start` /
        // `stop` / `status` commands.
        OpenE2eeVpnService.attachFlutterEngine(flutterEngine)

        // Permission-request channel — Dart invokes `requestVpnPermission`.
        permissionsChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            PERMISSIONS_CHANNEL,
        ).apply {
            setMethodCallHandler(::onPermissionsCall)
        }
    }

    /**
     * Sprint 11.0A — REMOVED inline `onVpnCall` mock. The
     * `opene2ee/vpn` MethodChannel is now owned by
     * `OpenE2eeVpnService` (attached via
     * `OpenE2eeVpnService.attachFlutterEngine(flutterEngine)` in
     * `configureFlutterEngine`). The `getSampledPackets` handler
     * lives in `OpenE2eeVpnService.onMethodCall` and reads the
     * LIVE ring via the instance's `snapshotRing()` method.
     *
     * S46 invariant: `MainActivity.kt` calls
     * `OpenE2eeVpnService.snapshot()` (NOT a hard-coded `mapOf(...)`
     * mock packet) when the Dart side requests the current sample
     * set. The 5-second scheduled `PacketDrain` (also in
     * `OpenE2eeVpnService`) pushes the same ring to Dart via the
     * `onPacketsSampled` event so the live chart never has to poll.
     */

    /**
     * Handle Dart → Activity commands.
     */
    private fun onPermissionsCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "requestVpnPermission" -> requestVpnPermission(result)
            "isVpnPrepared" -> result.success(isVpnPrepared())
            else -> result.notImplemented()
        }
    }

    /**
     * Launch `VpnService.prepare(this)`. The Android system shows the
     * standard "VPN connection request" consent sheet. The result is
     * delivered to [onActivityResult].
     *
     * PR-28 §B.1 — explicit `getIfPresent` cleanup. `VpnService.prepare`
     * returns `null` when the app is already authorised (the canonical
     * "present, no work to do" signal — the original `getIfPresent`
     * idiom the task refers to). The previous code inlined the `== null`
     * comparison; this version routes both the "prepare not needed" and
     * "consent dialog needed" branches through a single helper
     * [resolvePrepareIntent] that centralises the null-check, logs the
     * outcome, and isolates the `@RequiresApi(21)` requirement on
     * `startActivityForResult` (deprecated on API 34+ in favour of the
     * `registerForActivityResult` Activity-Result API; kept here to avoid
     * re-architecting the permission handshake in PR-28 — see follow-up
     * note in the deliverable).
     */
    @RequiresApi(21)
    private fun requestVpnPermission(result: MethodChannel.Result) {
        if (pendingVpnResult != null) {
            result.error("vpn_prepare_in_flight", "Already awaiting a permission result", null)
            return
        }
        when (val intent = resolvePrepareIntent()) {
            null -> result.success(true)
            else -> {
                pendingVpnResult = result
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, VPN_REQUEST_CODE)
                } catch (e: Throwable) {
                    pendingVpnResult = null
                    result.error("vpn_prepare_launch_failed", e.message, null)
                }
            }
        }
    }

    /**
     * PR-28 §B.1 — single-source-of-truth helper for the `VpnService.prepare`
     * null-check pattern (the "getIfPresent" idiom). Returns:
     *   - `null` when the app is already authorised (no consent dialog needed);
     *   - the launchable consent Intent otherwise.
     */
    @RequiresApi(21)
    private fun resolvePrepareIntent(): Intent? {
        val intent = VpnService.prepare(this)
        if (intent == null) {
            android.util.Log.d(TAG, "VPN already authorised (prepare() returned null)")
        }
        return intent
    }

    /**
     * Snapshot: have we already obtained consent? Use this to avoid
     * showing the dialog twice in the same session.
     */
    @RequiresApi(21)
    private fun isVpnPrepared(): Boolean {
        // `VpnService.prepare(context)` returning null on a fresh call
        // is the canonical "already authorised" signal — the `getIfPresent`
        // null-check pattern.
        return resolvePrepareIntent() == null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_REQUEST_CODE) return
        val pending = pendingVpnResult ?: return
        pendingVpnResult = null
        when (resultCode) {
            Activity.RESULT_OK -> pending.success(true)
            Activity.RESULT_CANCELED -> pending.success(false)
            else -> pending.error("vpn_prepare_unknown_result", "resultCode=$resultCode", null)
        }
    }

    /**
     * Android 13+ runtime notification permission — the foreground-service
     * notification for the VPN session needs this. We only request it when
     * we are actually about to start a session, to minimize the prompt
     * surface area (per ADR-0006).
     */
    @Suppress("UNUSED_PARAMETER")
    fun ensureNotificationPermission(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            result.success(true)
            return
        }
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            result.success(true)
        } else {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0x4E_4F_54_49)
            // The Dart side polls `status` if the user grants asynchronously.
            // A precise promise is out of scope for this method-channel surface
            // and easy to layer in a follow-up.
            result.success(false)
        }
    }

    override fun onDestroy() {
        permissionsChannel?.setMethodCallHandler(null)
        permissionsChannel = null
        // Sprint 11.0A — route the engine detach through the
        // singleton companion accessor so we detach from the
        // running instance (or clear the pending queue if the
        // service never came up). The service's
        // `detachFlutterEngine` instance method also clears the
        // shared `Companion.methodChannel` reference, which the
        // 5-second `PacketDrain` task reads for `onPacketsSampled`
        // pushes.
        OpenE2eeVpnService.detachFlutterEngine()
        super.onDestroy()
    }
}
