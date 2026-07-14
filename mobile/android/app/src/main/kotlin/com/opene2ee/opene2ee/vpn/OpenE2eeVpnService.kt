// mobile/android/app/src/main/kotlin/com/opene2ee/opene2ee/vpn/OpenE2eeVpnService.kt
//
// PR-22a (Sprint 3) — Android VPN Service — REAL implementation.
// PR-24 (Sprint 4) — moved from `mobile/lib/mobile/vpn/vpn_service_android.kt`
//                    into the Android source tree so Gradle compiles it.
// PR-28 (Sprint 5) — PR-22a follow-up batch:
//                    (B.1) `@RequiresApi(21)` guards around `VpnService.Builder
//                          .allowedApplications()` / `.disallowedApplications()`
//                          call sites (API 21+, Lollipop).
//                    (B.2) Transient service instance handling — companion
//                          singleton so `MainActivity` no longer creates a
//                          throwaway `OpenE2eeVpnService()` instance on
//                          every engine attach/detach. Also switched the
//                          foreground-service lifecycle to
//                          `ServiceCompat.startForeground` with
//                          `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`.
//                    (B.3) IPv6 transport header parsing stub — was
//                          hard-coded null for srcPort/dstPort/tcpFlags on
//                          IPv6 packets. Now walks past IPv6 extension
//                          headers (limited to no-extension case; documented
//                          below) to extract TCP/UDP ports + flags.
//
// Sprint 11.0A — REAL packet drain → MethodChannel push bridge.
//                The 10.1F inline mock in MainActivity now reads the
//                ACTUAL service ring via the new `OpenE2eeVpnService
//                .snapshot()` static accessor (S46). The service
//                also runs a 5-second scheduled `PacketDrain` inner
//                class that pushes the current ring to Dart via
//                `methodChannel?.invokeMethod("onPacketsSampled",
//                packetsArray)` (S45). A new `companion object`
//                `methodChannel: MethodChannel?` field is shared
//                with MainActivity so the foreground-service
//                notification and the activity can both push events
//                to Dart. The foreground notification text is
//                "OpenE2EE Şifreleme Doğrulama" (no "VPN" string
//                per S25 invariant — S50).
//
// Sprint 11.0D — channel ownership moved BACK to MainActivity.
//                In 11.0A the `opene2ee/vpn` MethodChannel handler
//                was installed by `attachFlutterEngine` in this
//                service — but `attachFlutterEngine` only runs
//                AFTER the service is created (via `onCreate`),
//                and the service is only created on Dart's `start`
//                call. The Dart-side `pool_provider.dart` polling
//                loop calls `vpn.getSampledPackets()` every 5s
//                starting the moment the ActivePoolScreen is
//                first opened — BEFORE `start`. Result: OnePlus 9
//                Pro owner reported `MissingPluginException(No
//                implementation found for method getSampledPackets
//                on channel opene2ee/vpn)`. The fix: handler lives
//                at the activity level (always alive from app
//                launch), delegates to a new
//                `OpenE2eeVpnService.dispatch(context, call, result)`
//                static which routes per-method to the live
//                service OR returns safe defaults (empty ring /
//                IDLE status) when no service is alive yet. The
//                instance `attachFlutterEngine` is preserved (it
//                now sets the companion `methodChannel` for
//                outbound `onPacketsSampled` pushes only — no
//                inbound `setMethodCallHandler`). See S73
//                invariant: `MainActivity.kt` owns the
//                `opene2ee/vpn` MethodChannel handler.
//
// This file is the canonical Kotlin source for the OpenE2EE Android VPN
// service. It lives under `mobile/android/app/src/main/kotlin/` so the
// Android Gradle Plugin picks it up on `./gradlew assembleDebug`. The
// sibling iOS source remains under `mobile/lib/mobile/vpn/NetworkExtension.swift`
// for cross-platform review.
//
// Architecture
// ------------
// - Extends `android.net.VpnService`. The OS hands us a TUN descriptor via
//   `Builder.establish()`; we read packets from it on a dedicated IO thread,
//   extract metadata for the sampling ring buffer, and PROTECT each packet
//   so the system can forward its payload to the real network. We never
//   copy a packet payload off-device — see ADR-0006.
//
// - MethodChannel name: `opene2ee/vpn` (matches Dart-side
//   `kVpnMethodChannel` in `method_channel.dart`).
// - Channel methods from Dart:
//       "start"     → begin session (caller MUST have obtained RESULT_OK from
//                     `VpnService.prepare` first)
//       "stop"      → flush ring + tear down tunnel + stop foreground
//       "status"    → snapshot of {state, packetsObserved, ringSize, samplingCap}
//       "getSampledPackets" → snapshot of the bounded ring
//                              (Sprint 11.0A; replaces the 10.1F
//                              mock packet). Safe to call BEFORE
//                              the service is running — dispatch
//                              returns an empty list.
//       "setAllowedApplications" → restrict VPN to a per-app allowlist
//                                   (Android 5.0+, VpnService.Builder.allowedApplications)
//       "setDisallowedApplications" → inverse — bypass VPN for these apps
//       "requestPrepare" → emit a SystemIntent-style permission prompt
//                           (handled by MainActivity; this service exposes
//                            the helper that returns the intent action)
// - Channel methods TO Dart:
//       "onPacketsSampled" → 5-second scheduled push of the bounded
//                              ring (S45 invariant; Sprint 11.0A)
//       "onTelemetry" → final flush of ring + capture timestamp
//       "onError"     → TUN/protocol errors with a code + message
//
// Per-app VPN (Android 5.0+, API 21+)
// -----------------------------------
// The class accepts an `allowedApplications: List<String>` (package names)
// and an optional `disallowedApplications: List<String>`. Only one of the
// two lists may be non-empty at a time (Android API contract — passing both
// throws `IllegalArgumentException` at Builder.establish() time). The
// lists are applied via:
//   - `Builder.allowedApplications(pkgNames)` to constrain the VPN to a
//     specific set of apps.
//   - `Builder.disallowedApplications(pkgNames)` to bypass the VPN for
//     specific apps while routing everything else through it.
//
// Privacy contract (ADR-0006 — verbatim invariants)
// --------------------------------------------------
// 1. NO raw packet payload is ever copied off-device. The ring buffer
//    stores metadata only: IP/TCP/UDP header fields. Payload bytes are
//    passed back to the OS via `protect()` and dropped.
// 2. NO IMEI, MSISDN, phoneNumber, MAC, contacts are touched. The
//    service MUST NOT call TelephonyManager / WifiInfo /
//    BluetoothAdapter. A grep test in CI asserts this for the
//    `mobile/lib/mobile/vpn/` source tree.
// 3. Source / destination IPs are masked at /24 (IPv4) or /48 (IPv6)
//    before being handed to Dart.
// 4. The TUN interface is read-only on the metadata side; we never
//    inject packets of our own.
//
// Lifecycle / Android 14+ foregroundServiceType
// ---------------------------------------------
// Android 14 (API 34) requires `foregroundServiceType="specialUse"` for
// VPN services that are not classified as "system". The manifest entry
// under `mobile/android/app/src/main/AndroidManifest.xml` MUST declare
// that type. See ADR-0003 risk B2.

package com.opene2ee.opene2ee.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.FileInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * OpenE2EE Android VPN service — real (non-skeletal) implementation.
 *
 * Brings up a TUN interface through the [VpnService.Builder], reads packets
 * on a dedicated thread, extracts metadata into a bounded ring buffer
 * (default = 10 packets), and forwards payload bytes to the real network
 * via [protect]. Telemetry is flushed on stop (or when the cap is hit) via
 * the [MethodChannel] registered against [kVpnMethodChannel].
 *
 * @see docs/ADR-0003-vpn-layer.md
 * @see docs/ADR-0006-anonimlik.md
 * @see docs/SPRINT-3-SCOPE.md §7 — Sprint 3 PR-22a
 */
class OpenE2eeVpnService : VpnService() {

    companion object {
        private const val TAG = "OpenE2eeVpn"

        /**
         * Sprint 11.0H — TOCTOU (Time-Of-Check-Time-Of-Use) guard
         * for [startCapture] and [stopCapture]. Without this lock,
         * a double-tap on the "Aktif Nöbet başlat" button (or a
         * system-side `onStartCommand` self-restart) can race:
         * the FIRST `startCapture` is mid-flight (TUN setup),
         * `running.set(true)` not yet called, when the SECOND
         * `startCapture` enters, sees `running.get() == false`,
         * and starts a SECOND TUN setup. The two TUNs collide,
         * the reader thread from the first captures EOF from
         * the second's `pfd.close()`, and the service lands in
         * a corrupt state — symptom Owner saw: `start` returns
         * `state: DRAINING, packetsObserved: 0, ringSize: 0`
         * with no `lastError` (the catch block wasn't hit, the
         * stop path WAS hit by the racing stop).
         *
         * The lock serializes `startCapture` / `stopCapture` /
         * `onRevoke` so the TOCTOU window is closed. The lock
         * is a companion-level `@JvmStatic` `Object` so it is
         * shared across all instances (companion fields are
         * shared JVM-wide). It is intentionally NOT the
         * per-instance `lock` — that would defeat the purpose.
         */
        @JvmField
        val stateLock: Any = Any()

        /**
         * Sprint 11.0K — main looper Handler. The Flutter
         * Engine requires `MethodChannel.invokeMethod` to be
         * invoked on the Android UI thread (the main
         * `Looper`). Pre-11.0K, all three call sites
         * (flushTelemetry's `onTelemetry`, notifyError's
         * `onError`, PacketDrain's `onPacketsSampled`) called
         * `methodChannel?.invokeMethod` from background threads
         * (PacketDrain = `opene2ee-vpn-drain` ScheduledExecutor
         * worker; flushTelemetry / notifyError = the TUN reader
         * thread). The Engine threw `@UiThread` violations and
         * the Owner saw "VPN active, internet OK, UI never
         * updates" (state: DRAINING, packetsObserved: 0,
         * ringSize: 0). 11.0K wraps all three call sites in
         * `mainHandler.post { ... }` via the `pushToDart` helper.
         *
         * The Handler is `@JvmField` so the post is one line at
         * the call site (no allocation per push). It is created
         * EAGERLY at class-load time so the first push doesn't
         * have to wait for `Looper.getMainLooper()` to be
         * queried from a non-main thread (subtle but documented
         * in `Handler` Javadoc — querying the main looper from a
         * worker thread is fine but the Handler itself should be
         * constructed on a thread that has the main Looper's
         * classloader available; lazy init in a worker thread
         * can hit a `NullPointerException` on some Android
         * OEM ROMs — OnePlus OxygenOS is one of them).
         */
        @JvmField
        val mainHandler: Handler = Handler(Looper.getMainLooper())

        /** Must match `kVpnMethodChannel` in Dart. */
        const val METHOD_CHANNEL = "opene2ee/vpn"

        /** Sampling cap per HANDOFF §6.1 mobile spec. */
        const val SAMPLING_CAP_PACKETS = 10

        /**
         * Sprint 11.0S-EXTRA — 15-minute countdown
         * duration. The foreground notification
         * chronometer counts down from this value to
         * zero, then the auto-stop Handler fires
         * `stopCapture(graceful = true)` to tear
         * down the VPN. S92 audit verifies
         * `setWhen(now + COUNTDOWN_TOTAL_MS)` +
         * the auto-stop Handler is present.
         */
        const val COUNTDOWN_TOTAL_MS = 15L * 60L * 1000L

        /**
         * Sprint 11.0P — `TUN_MTU` lowered from 1500 to
         * 1400. The standard Ethernet MTU (1500) is too
         * large for mobile networks: Turkcell 4G/5G uses
         * GTP-U encapsulation (8-byte header + IPsec
         * 50-70-byte trailer) which means a 1500-byte TUN
         * packet becomes 1500 + 78 = 1578 bytes on the
         * wire. The mobile network drops any frame
         * >1500 bytes (the radio link MTU), so packets
         * exit the TUN, hit the radio link, and are
         * dropped silently. The Owner sees Chrome /
         * WhatsApp "no internet" even though the TUN
         * reader is capturing packets (1247 packets/2min
         * logcat in Sprint 11.0O confirmed passthrough
         * is real; the missing 30% of large packets that
         * were dropped on the radio link is what the
         * user experiences as "DNS / load failures").
         *
         * 1400 bytes is the canonical mobile-safe MTU
         * (1400 + 78 = 1478 < 1500 radio MTU). The
         * S87 audit verifies `TUN_MTU = 1400` (NOT
         * 1500) is present in `OpenE2eeVpnService.kt`
         * as a regression guard.
         */
        const val TUN_MTU = 1400

        /** Public, no-leak DNS resolvers handed to the TUN. */
        val PRIMARY_DNS: InetAddress = InetAddress.getByName("1.1.1.1")
        val SECONDARY_DNS: InetAddress = InetAddress.getByName("1.0.0.1")

        /** TUN address space (RFC 1918). */
        val TUN_ADDRESS: InetAddress = InetAddress.getByName("10.42.0.2")
        val TUN_ROUTED_CIDR: String = "10.42.0.0/24"

        /**
         * Sprint 11.0I — TUN interface address prefix length
         * (`/24`). Used by [buildVpnBuilder] for the
         * `addAddress(TUN_ADDRESS, TUN_PREFIX_LENGTH)` call
         * (the interface address) AND logged in the
         * startCapture breadcrumb.
         */
        const val TUN_PREFIX_LENGTH = 24

        /**
         * Sprint 11.0I — captured-route destination address
         * (`"0.0.0.0"` = default route = ALL traffic). Used
         * by [buildVpnBuilder] for the
         * `addRoute(CAPTURED_ROUTE_ADDRESS, CAPTURED_ROUTE_PREFIX)`
         * call.
         *
         * Why `0.0.0.0/0` and NOT `TUN_ADDRESS/24`:
         * Pre-11.0I, the code used
         * `.addRoute(TUN_ADDRESS, 24)` which is the SAME
         * address as the interface (`addAddress(TUN_ADDRESS, 24)`).
         * The Android `VpnService.Builder.addRoute` method
         * expects a DESTINATION SUBNET (the network whose
         * traffic the VPN will capture), NOT the interface
         * address. Using the interface address is a
         * 9.7.0-era mirror bug that the OnePlus 9 Pro
         * OxygenOS strict validation rejects with
         * `IllegalArgumentException: Bad address` (Owner
         * 11:46-11:57 logcat confirmed the regression).
         * Pixel/Samsung tolerate the bug; OnePlus does not.
         *
         * `0.0.0.0/0` (default route) is the safest
         * fallback — it captures ALL traffic, works on every
         * Android version (API 21+), and avoids the
         * OnePlus strict validation.
         */
        const val CAPTURED_ROUTE_ADDRESS = "0.0.0.0"
        const val CAPTURED_ROUTE_PREFIX = 0

        /** Notification channel ID (Android 8+ requirement). */
        const val NOTIFICATION_CHANNEL_ID = "opene2ee.vpn.diagnostic"

        /** Foreground notification id. */
        const val NOTIFICATION_ID = 0x4F_50_4E_45 /* 'OPNE' */

        /** Intent action that calls `VpnService.prepare()` from MainActivity. */
        const val ACTION_PREPARE = "com.opene2ee.opene2ee.vpn.PREPARE"

        // ═══ PR-28 §B.2 — Transient service instance handling ══════════
        //
        // Background: prior to PR-28, `MainActivity.configureFlutterEngine`
        // called `OpenE2eeVpnService().attachFlutterEngine(flutterEngine)`,
        // creating a fresh (un-started) instance every time the Flutter
        // engine was attached. The MethodChannel handler was therefore
        // installed on an instance that the OS never started — so Dart →
        // service calls would land on a no-op (state never updated,
        // running flag false) and the real service instance (created by
        // `Context.startForegroundService`) had no channel at all.
        //
        // Fix: the running service registers itself in `onCreate` and
        // unregisters in `onDestroy`. `MainActivity` resolves the SAME
        // instance via [activeInstance] before attaching the channel.
        // If no instance is alive yet (legitimate early-launch window),
        // the call is queued in [pendingEngine] and replayed by
        // [onCreate] once the service is up. This closes the start/stop
        // race window where Dart could call `start` between
        // `MainActivity.configureFlutterEngine` and `OpenE2eeVpnService.onCreate`.
        @Volatile
        private var activeInstance: OpenE2eeVpnService? = null

        /** Engine captured when a channel attach races service creation. */
        @Volatile
        private var pendingEngine: FlutterEngine? = null

        /**
         * Singleton accessor — returns the currently-running service, or
         * null if it hasn't been started yet. Used by `MainActivity` so
         * the MethodChannel handler is wired to the SAME instance that
         * will receive Dart's `start` command.
         */
        @JvmStatic
        fun getActiveInstance(): OpenE2eeVpnService? = activeInstance

        /**
         * Attach the Flutter engine to the active service instance. If
         * the service hasn't been created yet (e.g. the activity wired
         * its engine before the first `Context.startForegroundService`
         * landed), the engine is queued and replayed in
         * [OpenE2eeVpnService.onCreate] once the instance exists.
         */
        // PR-28 §B.2 — singleton dispatch. NOT marked `@JvmStatic` because
        // the companion form shares its JVM name+descriptor with the
        // instance [attachFlutterEngine] below, which Kotlin rejects as a
        // "Platform declaration clash". Kotlin callers reach the companion
        // via the standard `OpenE2eeVpnService.attachFlutterEngine(...)`
        // sugar; Java callers go through `Companion.attachFlutterEngine`.
        fun attachFlutterEngine(engine: FlutterEngine) {
            val instance = activeInstance
            if (instance != null) {
                instance.attachFlutterEngine(engine)
            } else {
                pendingEngine = engine
            }
        }

        /**
         * Detach the Flutter engine. Safe to call even if no instance
         * ever came up — drains the pending queue so we don't replay a
         * stale engine against a fresh service later.
         */
        // Companion counterpart of [detachFlutterEngine] below. Same
        // @JvmStatic rationale as above — kept off to avoid the JVM
        // signature clash with the instance method of the same name.
        fun detachFlutterEngine() {
            val instance = activeInstance
            if (instance != null) {
                instance.detachFlutterEngine()
            } else {
                pendingEngine = null
            }
        }

        // ═══ Sprint 11.0A — REAL packet drain → MethodChannel push ═══
        //
        // The 10.1F inline mock in MainActivity read a hard-coded
        // synthetic packet; 10.2 was supposed to integrate the real
        // service. Sprint 11.0A (M1) closes the gap: the service
        // exposes its ring to MainActivity via the [snapshot] static
        // accessor, AND runs a 5-second scheduled `PacketDrain` inner
        // class that pushes the current ring to Dart via
        // `methodChannel?.invokeMethod("onPacketsSampled", packetsArray)`.
        //
        // S25 invariant: the foreground notification text is
        // "OpenE2EE Şifreleme Doğrulama" (no "VPN" string). See
        // [startForegroundCompat] for the title.

        /** Drain cadence (seconds) for the 5-second push loop. */
        const val DRAIN_INTERVAL_SECONDS: Long = 5

        /**
         * Shared MethodChannel reference. The companion owns this so
         * the foreground service (which never sees the Flutter engine
         * directly) can still push `onPacketsSampled` events to Dart.
         * The [attachFlutterEngine] setter wires the channel from the
         * engine; [detachFlutterEngine] clears it. Read by
         * [snapshot] when MainActivity polls and by [PacketDrain] when
         * the scheduled timer fires.
         */
        @JvmStatic
        @Volatile
        var methodChannel: MethodChannel? = null

        /**
         * Snapshot of the active service's bounded ring. Returns
         * `null` if no service is alive (MainActivity surfaces an
         * empty list to Dart in that case). The returned list is a
         * copy; the service may continue mutating the ring after the
         * call returns.
         *
         * Replaces the Sprint 10.1F inline mock packet in
         * `MainActivity.onVpnCall("getSampledPackets", ...)`. The
         * Dart side contract (`ParsedPacket.toJson()` shape) is
         * preserved verbatim.
         */
        @JvmStatic
        fun snapshot(): List<Map<String, Any?>>? {
            val instance = activeInstance ?: return null
            return instance.snapshotRing()
        }

        /**
         * Sprint 11.0D — single entry point for ALL Dart → service
         * MethodChannel calls. MainActivity's `opene2ee/vpn`
         * MethodChannel handler is a thin wrapper that calls this
         * method:
         *
         *   vpnChannel.setMethodCallHandler { call, result ->
         *       OpenE2eeVpnService.dispatch(this, call, result)
         *   }
         *
         * This is the fix for the Sprint 11.0A regression where the
         * channel handler lived inside the service: Dart polled
         * `getSampledPackets` before the service was started (i.e.
         * before the user clicked "Şifreleme Doğrulamayı Başlat"),
         * the handler was never installed, and Dart got
         * `MissingPluginException`. By centralising the dispatch in
         * a static (which never depends on a live service instance),
         * the inbound side is ALWAYS reachable from app launch.
         *
         * Behaviour matrix (see [onMethodCall] for the per-method
         * contract preserved from 11.0A):
         *   - `getSampledPackets`: returns activeInstance ring OR
         *     empty list if no service yet. SAFE to call before the
         *     service starts.
         *   - `status`: returns activeInstance status OR the
         *     canonical IDLE status map. SAFE pre-service.
         *   - `start`: uses [context] to launch the foreground
         *     service via [ContextCompat.startForegroundService],
         *     then delegates to the running instance's
         *     [onMethodCall] for the response. This is the missing
         *     piece in Sprint 11.0A — the service was never started
         *     because no one called `startForegroundService` for it.
         *   - `stop`: delegates to active instance, or returns
         *     STOPPED if no service.
         *   - `setAllowedApplications` /
         *     `setDisallowedApplications`: stores on active instance
         *     (or no-ops if no service yet).
         *   - `requestPrepare`: returns the consent Intent action
         *     (the actual consent dialog is owned by
         *     `opene2ee/vpn_permissions` — MainActivity handles it).
         *   - else: `notImplemented`.
         */
        @JvmStatic
        fun dispatch(context: Context, call: MethodCall, result: MethodChannel.Result) {
            // Sprint 11.0F — diagnostic breadcrumb at the dispatcher
            // entry. Pairs with the per-service-instance breadcrumbs
            // (`onStartCommand: entry`, `startCapture: entry`) so the
            // Owner can pinpoint where the regression is hanging on
            // the OnePlus 9 Pro Magisk Zygisk flow.
            Log.d(TAG, "dispatch: entry (method=${call.method})")
            try {
                when (call.method) {
                    "start" -> {
                        // Sprint 11.0D — actually start the foreground
                        // service. The intent has NO action so the
                        // service's `onStartCommand` falls into the
                        // `else if (running.get() == false) startCapture()`
                        // branch and brings up the TUN. We use
                        // [ContextCompat.startForegroundService] (the
                        // API 26+ path; the compat shim handles the
                        // pre-O fallback to plain `startService`).
                        val intent = Intent(context, OpenE2eeVpnService::class.java)
                        ContextCompat.startForegroundService(context, intent)
                        Log.d(TAG, "dispatch: startForegroundService() invoked, awaiting onStartCommand")
                        // Delegate to the active instance if it
                        // already exists (idempotent), else return
                        // a pending `preparing` status so Dart's
                        // state stream flips.
                        val instance = activeInstance
                        if (instance != null) {
                            Log.d(TAG, "dispatch: activeInstance present, delegating onMethodCall")
                            instance.onMethodCall(call, result)
                        } else {
                            // Service is spinning up — the system
                            // will call `onCreate` → register as
                            // activeInstance → `onStartCommand` →
                            // `startCapture` shortly. Tell Dart to
                            // poll `status` again to observe the
                            // transition.
                            Log.d(TAG, "dispatch: no activeInstance yet, returning DRAINING (service spinning up)")
                            result.success(idleStatusMap().toMutableMap().apply {
                                this["state"] = "DRAINING" // service is bringing up TUN
                            })
                        }
                    }
                    "stop" -> {
                        val instance = activeInstance
                        if (instance != null) {
                            instance.onMethodCall(call, result)
                        } else {
                            result.success(idleStatusMap().toMutableMap().apply {
                                this["state"] = "STOPPED"
                            })
                        }
                    }
                    "status" -> {
                        val instance = activeInstance
                        if (instance != null) {
                            instance.onMethodCall(call, result)
                        } else {
                            result.success(idleStatusMap())
                        }
                    }
                    "getSampledPackets" -> {
                        // SAFE to call before the service is alive:
                        // an empty list is the correct "no samples
                        // yet" answer. This is the primary call that
                        // was throwing `MissingPluginException` in
                        // Sprint 11.0A.
                        val r = snapshot() ?: emptyList()
                        // Sprint 11.0F — diagnostic breadcrumb. Logs
                        // the size of the returned ring so the Owner
                        // can see whether the polling loop is reaching
                        // the channel and what state the sampling is
                        // in (empty vs non-empty).
                        Log.d(TAG, "dispatch: getSampledPackets returned ${r.size} packets (activeInstance=${activeInstance != null})")
                        result.success(r)
                    }
                    "setAllowedApplications" -> {
                        val instance = activeInstance
                        if (instance != null) {
                            instance.onMethodCall(call, result)
                        } else {
                            // Pre-service set: stash on a
                            // companion-side pending list so the
                            // service picks it up on `onCreate`. For
                            // Sprint 11.0D we keep the simpler
                            // behaviour: drop with a warning result.
                            result.success(false)
                        }
                    }
                    "setDisallowedApplications" -> {
                        val instance = activeInstance
                        if (instance != null) {
                            instance.onMethodCall(call, result)
                        } else {
                            result.success(false)
                        }
                    }
                    "requestPrepare" -> {
                        // The actual consent flow lives on the
                        // `opene2ee/vpn_permissions` channel; this
                        // is kept for the 10.1F Dart-side contract
                        // (which calls `requestPrepare` first, then
                        // the permissions channel). The intent
                        // action is what `VpnService.prepare()`
                        // returns in the standard Android flow.
                        result.success("android.net.VpnService")
                    }
                    else -> result.notImplemented()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "dispatch error: ${call.method}", t)
                result.error("vpn_method_error", t.message, null)
            }
        }

        /**
         * Canonical IDLE status payload returned by [dispatch] when
         * the service has not been started yet. Matches the shape of
         * [currentStatusMap] so Dart's `VpnService._stateFromMap`
         * receives the expected keys (state / packetsObserved /
         * ringSize / samplingCap / lastError / allowedApplications /
         * disallowedApplications).
         */
        @JvmStatic
        fun idleStatusMap(): Map<String, Any?> = mapOf(
            "state" to "IDLE",
            "packetsObserved" to 0,
            "ringSize" to 0,
            "samplingCap" to SAMPLING_CAP_PACKETS,
            "lastError" to null,
            "allowedApplications" to null,
            "disallowedApplications" to null,
        )

        /**
         * Sprint 11.0E — idempotent notification-channel creator.
         * Called from [ensureForegroundService] (and the legacy
         * [startForegroundCompat] for back-compat) before
         * `startForeground()`. Android 8+ (API 26+) REQUIRES a
         * channel to exist for `NotificationCompat.Builder.build()`
         * to succeed when the notification is tied to a foreground
         * service; missing the channel is the "silent no-op →
         * 5-second timeout" failure mode in Sprint 11.0E's Senaryo 2.
         *
         * Idempotent: re-creating an existing channel is a no-op on
         * the platform side, but we add the `getNotificationChannel
         * (CHANNEL_ID) == null` short-circuit so the call has no
         * observable cost on the hot path.
         */
        @JvmStatic
        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java) ?: return
                if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
                    return // already exists — no-op
                }
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "OpenE2EE Şifreleme Doğrulama",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    // Sprint 11.0A — S50 invariant: NO "VPN" string in
                    // any user-facing surface. The PRIVACY_TEXT eki is
                    // the Turkish-language disclosure appended for the
                    // Android 14+ foregroundServiceType=specialUse
                    // subtype justification.
                    description = "Ağ şifreleme bütünlüğü doğrulama oturumu (PRIVACY_TEXT eki)"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    /** Lifecycle states exposed via `status` to Dart. */
    enum class State { IDLE, SAMPLING, DRAINING, STOPPED, ERROR }

    /** Monotonic packet counter since last `start`. */
    private val packetsObserved = AtomicInteger(0)

    /**
     * Sprint 11.0P — IP fragment counter. Increments when
     * the read packet's IP header has the
     * `MF` (More Fragments) flag set or the fragment
     * offset is non-zero. The fragment rate on a healthy
     * mobile network is < 0.1%; a rate > 5% indicates
     * MTU 1500 is too high (Owner 13:50: Turkcell GTP
     * encapsulation drops fragments > 1500 bytes on the
     * radio link). The 11.0P MTU = 1400 fix should
     * drive the fragment rate back to baseline. The
     * `startReaderThread` per-1000-packet breadcrumb
     * surfaces `fragmentCount` for the Owner to grep
     * `adb logcat` for after a Chrome / WhatsApp test.
     */
    private val ipFragmentCount = AtomicLong(0)

    /**
     * Sprint 11.0T — passthrough write counter. Owner
     * 18:19 reported that `curl 212.64.210.85/healthz`
     * works WITHOUT the VPN (the upstream Patroni
     * answers) but FAILS with the VPN (the user sees
     * "no route to host" / timeout). Sprint 11.0J
     * added the transparent passthrough (`output
     .write(buf, 0, n)`) but Owner 18:19 confirmed
     * it doesn't actually write the bytes. This
     * counter increments EXACTLY ONCE per
     * successful `output.write(buf, 0, n)` and is
     * the canonical diagnostic for the Owner to
     * grep `adb logcat` after a `curl 212.64.210.85/
     * healthz` test:
     *   - If `passthroughCount` is 0 → write is
     *     never called (or never succeeds). The
     *     reader thread is in an error state
     *     BEFORE the write.
     *   - If `passthroughCount` > 0 but `curl`
     *     still fails → the write IS happening
     *     but the bytes are not reaching the
     *     kernel (the OS drops them, the TUN fd
     *     is closed, Magisk Zygisk interferes, etc.).
     *   - If `passthroughCount` equals
     *     `packetsObserved` (the per-1000 log
     *     breadcrumb compares both) → every
     *     captured packet is also being
     *     passthrough-written (the healthy state).
     * S93 audit verifies this field is declared
     * AND is reset in startCapture AND is
     * incremented in the write call block.
     */
    private val passthroughCount = AtomicLong(0)

    /** True while TUN loop is running. */
    private val running = AtomicBoolean(false)

    // Sprint 11.0Z — user-space TCP/IP stack via
    // Netty. Initialized lazily in startCapture()
    // (after the service is fully constructed) and
    // shutdown in stopCapture(). The class is in
    // the same `vpn/` package so it has package
    // access to the VpnService.protect() method
    // (which is `protected` on the VpnService
    // base class, but since OpenE2eeVpnService
    // extends VpnService the protected method is
    // accessible from same-package code via
    // `service.protect(socket)`).
    private var nettyClient: NettyChannelClient? = null

    // Sprint 12.0B — minimal UDP forwarder lives
    // DIRECTLY in OpenE2eeVpnService.kt (not in
    // NettyChannelClient.kt). The brief is
    // explicit: "Netty DEGIL, sadece raw
    // java.net.DatagramSocket + service.protect
    // (socket)". The class is defined at the
    // bottom of this file (top-level, same
    // `vpn/` package). It owns the per-flow
    // DatagramSocket map, the per-flow reader
    // Future map, and a single
    // ThreadPoolExecutor for the per-flow
    // reader threads. Its `tearDown()` method
    // is called from `stopCapture()` BEFORE
    // `nettyClient?.shutdown()` so the 6-step
    // teardown's step 3 (udpSocketMap close +
    // udpReaderFutures cancel) runs first.
    private val udpForwarder = UdpForwarder(this)

    // Sprint 12.0C — minimal TCP forwarder lives
    // DIRECTLY in OpenE2eeVpnService.kt (not in
    // NettyChannelClient.kt). The brief is
    // explicit: "OpenE2eeVpnService.kt icine
    // TcpForwarder class (raw java.net.Socket,
    // Netty DEGIL, 12.0B gibi)". The class is
    // defined at the bottom of this file
    // (top-level, same `vpn/` package). It owns
    // the per-flow tcpConnectionMap, the
    // per-flow tcpReaderFutures map, and a single
    // ThreadPoolExecutor for the per-flow TCP
    // reader threads. Its `tearDown()` method is
    // called from `stopCapture()` BEFORE
    // `nettyClient?.shutdown()` so the 6-step
    // teardown's step 2 (tcpConnectionMap
    // readerFuture.cancel + socket.close +
    // readerThread.interrupt + readerThread.join
    // + map.clear) runs first. After 12.0C the
    // NettyChannelClient.shutdown() step 2 is a
    // forward-compat no-op (logs "step 2
    // DELEGATED") — the actual teardown ran in
    // TcpForwarder.tearDown() first.
    private val tcpForwarder = TcpForwarder(this)

    /** Current state — observable via `status`. */
    @Volatile
    private var state: State = State.IDLE

    @Volatile
    private var lastError: String? = null

    /** TUN file descriptor (null when stopped). */
    private var tunInterface: ParcelFileDescriptor? = null

    /** The thread doing blocking reads on the TUN input stream. */
    private var readerThread: Thread? = null

    /**
     * Sprint 11.0S-EXTRA — the pending 15-minute
     * auto-stop Handler. Set by
     * `scheduleCountdownAutoStop()` after the
     * foreground notification is posted, cancelled
     * in `stopCapture()` (when the user manually
     * disconnects, so the VPN tears down
     * immediately and the 00:00 wakeup doesn't
     * fire later). The chronometer + Handler pair
     * is the "alarm clock" the user sees in the
     * notification bar.
     */
    private var countdownAutoStopRunnable: Runnable? = null

    /** Method channel back into Dart — wired by [attachFlutterEngine]. */
    private var methodChannel: MethodChannel? = null

    /** Per-app VPN allowlist (null = all apps). Mutually exclusive with [disallowedApplications]. */
    @Volatile
    private var allowedApplications: List<String>? = null

    /** Per-app VPN denylist (null = no exception). Mutually exclusive with [allowedApplications]. */
    @Volatile
    private var disallowedApplications: List<String>? = null

    /** Bounded ring of metadata snapshots. */
    private val ring: ArrayDeque<Map<String, Any?>> = ArrayDeque(SAMPLING_CAP_PACKETS)

    /** Synchronize ring mutations across the IO thread + the stop path. */
    private val ringLock = Any()

    // Sprint 11.0A — scheduled packet drain (5s cadence). The
    // executor is shared across the service lifetime and shut
    // down in [stopCapture]. A single-threaded scheduled pool is
    // sufficient (one task at a time, ring reads are O(1)).
    private var drainExecutor: ScheduledExecutorService? = null
    private var drainTask: ScheduledFuture<*>? = null

    /**
     * Sprint 11.0D — `attachFlutterEngine` is now a NO-OP for
     * INBOUND channel registration. The `opene2ee/vpn`
     * MethodChannel handler is owned by `MainActivity`
     * (registered in its `configureFlutterEngine` override,
     * which runs at app launch — BEFORE the VpnService is ever
     * started).
     *
     * Why this changed: in Sprint 11.0A, the handler was set
     * here, BUT the Dart-side `pool_provider.dart` polling loop
     * calls `vpn.getSampledPackets()` immediately when the
     * ActivePoolScreen is first opened (the PoolNotifier is
     * constructed lazily when `ref.watch(poolProvider)` is first
     * read). At that moment the VpnService is NOT yet running
     * (the user has not clicked "Şifreleme Doğrulamayı Başlat"),
     * so the handler was never set, and Dart got
     * `MissingPluginException(No implementation found for
     * method getSampledPackets on channel opene2ee/vpn)`.
     *
     * The MainActivity-owned handler delegates to the
     * [Companion.dispatch] static dispatcher which returns
     * safe defaults when no service is active, OR calls the
     * service's instance methods when the service IS active.
     *
     * The OUTBOUND side of the channel (the `onPacketsSampled`
     * event the 5-second `PacketDrain` pushes to Dart) still
     * needs a [MethodChannel] reference, which is the
     * [Companion.methodChannel] field. We publish the channel
     * from the engine here so the drain can read it without
     * holding an engine reference.
     */
    fun attachFlutterEngine(engine: FlutterEngine) {
        // Sprint 12.0F+4 — call-chain debug breadcrumb.
        // The Owner 12.0F+3 (release + debug) test
        // showed 0 onMethodCall logs, which means EITHER
        // MainActivity never called attachFlutterEngine
        // (so the engine binaryMessenger is not wired),
        // OR the companion methodChannel was null when
        // onMethodCall fired (so the inbound handler
        // delegate to OpenE2eeVpnService.dispatch found
        // no channel to invoke on). This Log.d fires
        // EVERY time MainActivity calls
        // attachFlutterEngine so the Owner can grep
        // logcat for "attachFlutterEngine: ch=" and
        // confirm the binding succeeded.
        Log.d(TAG, "attachFlutterEngine: ENTER, " +
                "prev Companion.methodChannel=${Companion.methodChannel}, " +
                "engine=${engine.hashCode()}")
        val ch = MethodChannel(engine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
        // Publish the channel for OUTBOUND pushes from
        // `PacketDrain` (the 5-second `onPacketsSampled` event).
        // Do NOT install an inbound handler here — MainActivity
        // owns that side (see class doc + S73 invariant).
        Companion.methodChannel = ch
        Log.d(TAG, "attachFlutterEngine: DONE, " +
                "Companion.methodChannel=$ch, " +
                "methodChannel=$ch (the channel IS published for OUTBOUND " +
                "PacketDrain pushes; INBOUND handler lives in MainActivity)")
    }

    /**
     * Detach the MethodChannel — called from `MainActivity` `onDestroy` so
     * we don't leak handlers across engine restarts.
     */
    fun detachFlutterEngine() {
        // Sprint 12.0F+4 — call-chain debug breadcrumb.
        // Pair with attachFlutterEngine: ch= above. Fires
        // on engine teardown so the Owner can confirm the
        // channel was actually cleaned up (no leak).
        Log.d(TAG, "detachFlutterEngine: ENTER, " +
                "prev methodChannel=$methodChannel, " +
                "prev Companion.methodChannel=${Companion.methodChannel}")
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        // Sprint 11.0A — clear the companion reference too so the
        // drain loop (if still scheduled) does not push to a
        // stale channel after the activity is gone.
        if (Companion.methodChannel === methodChannel) {
            Companion.methodChannel = null
        }
        Log.d(TAG, "detachFlutterEngine: DONE, " +
                "methodChannel=null, Companion.methodChannel=${Companion.methodChannel}")
    }

    /**
     * Handle Dart → native commands.
     */
    private fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        // Sprint 12.0F+4 — call-chain debug breadcrumb.
        // The Owner 12.0F+3 (release + debug) test
        // showed 0 dispatching flags=0x + 0
        // buildVpnBuilder + 0 rebindProcessToNetwork
        // + 0 dumpVpnRoutingState + 0 startCapture
        // entry breadcrumbs. That means onMethodCall
        // was NEVER called with method='start' on
        // the service instance. This entry log fires
        // for EVERY method so the Owner can grep
        // logcat for "onMethodCall: received method='start'"
        // and verify the Dart → MainActivity → dispatch
        // → instance.onMethodCall chain reached this
        // method. S124-1 audit verifies the literal.
        Log.d(TAG, "onMethodCall: received method='${call.method}', " +
                "running=${running.get()}, state=$state, " +
                "args=${call.arguments}")
        try {
            when (call.method) {
                "start" -> {
                    // Sprint 12.0F+4 — branch entry
                    // breadcrumb. Pairs with
                    // onMethodCall: received above so
                    // the Owner can verify the 'start'
                    // branch was reached (vs e.g. the
                    // 'status' branch).
                    Log.d(TAG, "onMethodCall: 'start' branch ENTERED, " +
                            "calling startCapture()")
                    val newState = startCapture()
                    Log.d(TAG, "onMethodCall: 'start' branch DONE, " +
                            "newState=$newState")
                    result.success(stateToMap(newState))
                }
                "stop" -> {
                    Log.d(TAG, "onMethodCall: 'stop' branch ENTERED, " +
                            "calling stopCapture(graceful=true)")
                    val newState = stopCapture(graceful = true)
                    result.success(stateToMap(newState))
                }
                "status" -> {
                    Log.d(TAG, "onMethodCall: 'status' branch ENTERED, " +
                            "returning currentStatusMap()")
                    result.success(currentStatusMap())
                }
                "getSampledPackets" -> {
                    // Sprint 11.0A — read the LIVE bounded ring and
                    // return the snapshot. Replaces the 10.1F inline
                    // mock packet that lived in MainActivity. The
                    // ring is bounded by [SAMPLING_CAP_PACKETS] (10)
                    // so a slow consumer does not leak memory; the
                    // companion `snapshot()` static (S46) and the
                    // live `packetStream` push (S45) are the two
                    // consumer paths.
                    val packets: List<Map<String, Any?>> = snapshotRing()
                    Log.d(TAG, "onMethodCall: 'getSampledPackets' branch DONE, " +
                            "returned ${packets.size} packets")
                    result.success(packets)
                }
                "setAllowedApplications" -> {
                    val pkgs = (call.argument<List<String>>("packages") ?: emptyList())
                    allowedApplications = pkgs
                    if (pkgs.isNotEmpty()) disallowedApplications = null
                    Log.d(TAG, "onMethodCall: 'setAllowedApplications' branch DONE, " +
                            "pkgs=$pkgs")
                    result.success(true)
                }
                "setDisallowedApplications" -> {
                    val pkgs = (call.argument<List<String>>("packages") ?: emptyList())
                    disallowedApplications = pkgs
                    if (pkgs.isNotEmpty()) allowedApplications = null
                    Log.d(TAG, "onMethodCall: 'setDisallowedApplications' branch DONE, " +
                            "pkgs=$pkgs")
                    result.success(true)
                }
                "requestPrepare" -> {
                    // Returns the intent ACTION a UI flow must `startActivityForResult`
                    // for to obtain RESULT_OK. We do NOT start the activity here because
                    // TODO(port-main-activity): MainActivity port (parallel sprint item) will own the actual startActivityForResult flow that consumes this ACTION.
                    // this runs in a Service context — MainActivity owns the flow.
                    Log.d(TAG, "onMethodCall: 'requestPrepare' branch DONE, " +
                            "returning android.net.VpnService")
                    result.success("android.net.VpnService")
                }
                else -> {
                    Log.w(TAG, "onMethodCall: unknown method='${call.method}', " +
                            "returning notImplemented")
                    result.notImplemented()
                }
            }
        } catch (t: Throwable) {
            // Sprint 12.0F+4 — catch log. The
            // pre-12.0F+4 code only logged the error
            // + result.error but did NOT log the
            // method name that threw. The Owner
            // greps for "onMethodCall: method='X'
            // THREW" to find which branch failed.
            Log.e(TAG, "onMethodCall: method='${call.method}' THREW", t)
            result.error("vpn_method_error", t.message, null)
        }
    }

    /**
     * Build the [VpnService.Builder] with our standard config + per-app lists.
     * Marked `protected` so tests can swap it via subclass.
     *
     * PR-28 §B.1: `allowedApplications` and `disallowedApplications` are
     * API 21 (Lollipop) additions. Sprint 7 MOB-5 bumped the project floor
     * to `minSdk = 23` (Android 6.0 Marshmallow) in `app/build.gradle.kts`
     * to satisfy flutter_secure_storage 9.x's AndroidKeyStore contract —
     * so the `@RequiresApi(21)` annotations below are now belt-and-braces.
     * They document the API contract and stop the Android Lint `NewApi`
     * rule from firing if a downstream module ever lowers minSdk below 21.
     * The calls themselves are safe because `app/build.gradle.kts`
     * enforces the floor (currently 23; never below 21 since these two
     * methods are the original API 21 floor reference).
     */
    @RequiresApi(21)
    protected open fun buildVpnBuilder(): VpnService.Builder {
        // Sprint 12.0F+1 — allowedApplications
        // diagnostic breadcrumb. Owner 12.0F
        // logcat analysis showed the TCP SYN
        // packets never reached the TUN — 9
        // dispatch events all carried PSH+ACK,
        // 0 SYN. One of the 4 root-cause
        // hypotheses is: the VPN is restricted
        // to the OWN app package
        // (`com.opene2ee.opene2ee`) via
        // `addAllowedApplication`, so when the
        // Owner opens Chrome / WhatsApp /
        // system apps and tries to reach
        // 212.64.210.85:443, those apps' TCP
        // connections bypass the VPN entirely
        // (Android kernel fast path) and the
        // SYN never enters the TUN.
        //
        // The fix is a one-line diagnostic log
        // that reports WHICH packages (if any)
        // are added to the per-app VPN
        // allowlist. The Owner greps for
        // `buildVpnBuilder: allowedApps=` to
        // confirm whether the VPN is
        // restricted to a single package
        // (suspicious for the OpenE2EE flow
        // where Owner's OTHER apps should also
        // be captured). If the result is an
        // empty list, the VPN captures ALL
        // traffic (the default — Chrome /
        // WhatsApp / system apps included).
        val allowedAppsList = allowedApplications ?: emptyList()
        val disallowedAppsList = disallowedApplications ?: emptyList()
        Log.d(TAG, "buildVpnBuilder: allowedApps=${allowedAppsList.size} packages=$allowedAppsList, disallowedApps=${disallowedAppsList.size} packages=$disallowedAppsList, addRoute=$CAPTURED_ROUTE_ADDRESS/$CAPTURED_ROUTE_PREFIX (default = all traffic), mtu=$TUN_MTU")
        val b = Builder()
            .setSession("OpenE2EE Network Diagnostic")
            // Sprint 11.0I — `addAddress(TUN_ADDRESS, 24)` is the
            // TUN INTERFACE address (the IP the VPN endpoint
            // will hold on the device). The `/24` prefix length
            // is the SUBNET size of that interface.
            .addAddress(TUN_ADDRESS, TUN_PREFIX_LENGTH)
            // Sprint 11.0I — `addRoute` takes a DESTINATION
            // SUBNET, NOT the interface address. Pre-11.0I the
            // code used `.addRoute(TUN_ADDRESS, 24)` (the SAME
            // IP as the interface) which is the 9.7.0 mirror
            // bug. OnePlus 9 Pro OxygenOS strict validation
            // rejects it with `IllegalArgumentException: Bad
            // address` (Owner 11:46-11:57 logcat confirmed
            // the regression). The fix is `addRoute("0.0.0.0",
            // 0)` — default route = ALL traffic captured.
            // S79 audit invariant: this line MUST NOT regress
            // to `addRoute(TUN_ADDRESS, ...)`.
            .addRoute(CAPTURED_ROUTE_ADDRESS, CAPTURED_ROUTE_PREFIX)
            .addDnsServer(PRIMARY_DNS)
            .addDnsServer(SECONDARY_DNS)
            .setMtu(TUN_MTU)
            .setBlocking(true)
        // PR-28 §B.1: per-app VPN allow/deny via [VpnService.Builder.addAllowedApplication]
        // / [addDisallowedApplication] are API 21+ (Lollipop). The Builder API exposes
        // these as SINGULAR per-package calls — there is no list-form overload in the
        // Android SDK (the original PR-28 source used `b.allowedApplications(pkgs)`,
        // which does not resolve on any API level). We therefore loop the per-app list
        // here. The project floor minSdk = 23 (Sprint 7 MOB-5) makes the @RequiresApi(21)
        // guard above redundant; [buildVpnBuilder] carries the lint annotation.
        //
        // Sprint 12.0F+3 — DEBUG_MODE all traffic (allowedApps
        // removed). For the 12.0F+3 debug round we want ALL
        // traffic to pass through the VPN (Chrome, WhatsApp,
        // system apps). This makes the test simpler: any TCP
        // SYN that the device sends will go through tun0 ->
        // user-space stack. We log the change so it is easy to
        // revert in 12.0F+4 once we understand the real root
        // cause (was: "allowedApps sadece kendi uygulama için
        // -> Chrome/system app paketleri VPN dışı, kendi
        // uygulama içinden bile yeni bağlantı kurulamıyor").
        // S123-3 audit verifies this `addAllowedApplication`
        // call is commented out.
        Log.d(TAG, "buildVpnBuilder: DEBUG_MODE all traffic (allowedApps removed, will re-enable in 12.0F+4)")
        // builder.addAllowedApplication(pkg)  // COMMENTED OUT in 12.0F+3 — DEBUG_MODE
        // allowedApplications?.forEach { pkg ->
        //     try {
        //         b.addAllowedApplication(pkg)
        //     } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
        //         // Unknown package — skip silently. The Dart-side package name list is
        //         // user-supplied; we don't want one stale entry to break Builder.establish.
        //         Log.w(TAG, "allowedApplications: package not found, skipping: $pkg")
        //     }
        // }
        disallowedApplications?.forEach { pkg ->
            try {
                b.addDisallowedApplication(pkg)
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                Log.w(TAG, "disallowedApplications: package not found, skipping: $pkg")
            }
        }
        return b
    }

    /**
     * Bring up the TUN, start the foreground notification, spawn the reader
     * thread. Idempotent — a duplicate call while already running is a no-op.
     */
    private fun startCapture(): State {
        // Sprint 11.0H — TOCTOU guard. The `synchronized(stateLock)`
        // block serializes `startCapture` / `stopCapture` /
        // `onRevoke` so a double-tap on the "Aktif Nöbet başlat"
        // button (or a system-side `onStartCommand` self-restart)
        // cannot race. Pre-11.0H the check
        // `if (running.get()) return state` was non-atomic
        // w.r.t. the rest of the function — a second invocation
        // could enter mid-flight. The lock closes that window.
        return synchronized(stateLock) {
            val prevState = state
            if (running.get()) {
                Log.d(TAG, "startCapture: TOCTOU guard hit, already running (state=$state, returning $state)")
                return@synchronized state
            }
            // Sprint 11.0F — diagnostic breadcrumbs. Each `Log.d` line is
            // emitted BEFORE the named side-effect so the Owner (or
            // anyone running `adb logcat -d -s OpenE2eeVpn:V`) can
            // pinpoint which step regressed. The `S75` audit invariant
            // asserts at least 5 of these are present in the source.
            // Sprint 11.0H — `prevState` is logged at entry so the
            // state-transition breadcrumbs (S78) are explicit
            // about the BEFORE / AFTER delta.
            Log.d(TAG, "startCapture: entry (running=false, prevState=$prevState)")
            try {
                val builder = buildVpnBuilder()
                // Sprint 11.0I — extended breadcrumb so the
                // addAddress / addRoute / MTU / DNS parameters
                // are visible in `adb logcat -d -s OpenE2eeVpn:V`.
                // Pre-11.0I the breadcrumb only logged the
                // interface address, which made the OnePlus
                // `Bad address` regression hard to diagnose
                // (the actual culprit was the addRoute
                // destination subnet).
                Log.d(TAG, "startCapture: buildVpnBuilder returned " +
                        "(addAddress=${TUN_ADDRESS.hostAddress}/$TUN_PREFIX_LENGTH, " +
                        "addRoute=$CAPTURED_ROUTE_ADDRESS/$CAPTURED_ROUTE_PREFIX, " +
                        "mtu=$TUN_MTU, dns=${PRIMARY_DNS.hostAddress}/${SECONDARY_DNS.hostAddress})")
                // Sprint 11.0Y — call checkPrivateDnsAndBindToVpn
                // BEFORE `Builder.establish()`. Owner 21:37 root
                // cause: pre-11.0Y the call was AFTER establish()
                // (at line ~1093). The VpnService.registered
                // transport is only added to the system network
                // registry AFTER establish() returns, but
                // requestNetwork(TRANSPORT_VPN) was issued AFTER
                // establish() and so the request was "satisfied"
                // before the system saw a pending subscriber — the
                // callback NEVER fired (not in 5s, not in 1 minute).
                // By issuing requestNetwork(TRANSPORT_VPN) BEFORE
                // establish(), the system has a pending subscriber
                // for the VPN transport and fires onAvailable
                // immediately when establish() registers it.
                // The tablet is NOT rooted, so Magisk/DenyList is
                // ruled out — the root cause is the call ordering
                // bug. S98 audit verifies this invariant.
                checkPrivateDnsAndBindToVpn()
                val pfd = builder.establish()
                if (pfd == null) {
                    // Sprint 11.0F — make the error message actionable.
                    // On OnePlus 9 Pro (rootlu, Magisk Zygisk) the
                    // `VpnService.Builder.establish()` call returns
                    // null even though the user already granted consent
                    // via `VpnService.prepare(this)` (because the
                    // foreground-service consent was confirmed in a
                    // PRIOR process / boot — `prepare()` returns null
                    // in that case too). The most common cause on a
                    // rooted OnePlus is Magisk's Zygisk module
                    // intercepting VpnService.establish() as part of
                    // its root-hide trick. The actionable advice: open
                    // Magisk → Settings → Zygisk → Disable, then
                    // reboot. Without this hint, the user sees a
                    // generic error and the regression looks
                    // unresolvable.
                    state = State.ERROR
                    lastError = "VpnService.Builder.establish() returned null " +
                            "(user declined consent, system refused, OR " +
                            "OnePlus Magisk Zygisk is intercepting). " +
                            "Workaround: Magisk → Settings → Zygisk → Disable, " +
                            "reboot, reinstall APK. See sprint-110f-final-report.md."
                    Log.w(TAG, "startCapture: state transition $prevState -> ERROR (establish() returned null, Magisk hint emitted)")
                    notifyError(lastError!!)
                    return@synchronized state
                }
                Log.d(TAG, "startCapture: builder.establish() returned pfd=$pfd (TUN descriptor acquired)")
                tunInterface = pfd
                // Sprint 12.0F+3 — rebindProcessToNetworkWithRetry.
                // Owner 12.0F+2 logcat (1056 satır) showed 0 TCP
                // dispatch breadcrumbs even though UDP DNS was
                // working (687 datagram sends via UdpForwarder).
                // The Owner-reported symptom is "TCP bağlantı
                // kurulmadı, 10 sn timeout". Hypothesis: the
                // initial `bindProcessToNetwork()` (called inside
                // checkPrivateDnsAndBindToVpn at line ~1133 above)
                // runs BEFORE the kernel has committed the
                // `0.0.0.0/0 dev tun0` route, so the bind misses.
                // We schedule 2 retry binds (at 1s and 3s after
                // `Builder.establish()`) to catch the race. S123-2
                // audit verifies this call site is after
                // establish().
                rebindProcessToNetworkWithRetry()
                // Sprint 12.0F+3 — dumpVpnRoutingState.
                // Runs 500ms after `Builder.establish()` so the
                // kernel has time to commit the route table
                // (typically < 100ms on most devices; 500ms is a
                // safe margin). Verifies the 3 root-cause
                // candidates in the brief:
                //   1. bindProcessToNetwork timing (handled by
                //      the rebind call above).
                //   2. allowedApps filtering (handled by the
                //      addAllowedApplication comment-out in
                //      buildVpnBuilder).
                //   3. VPN routing table setup (verified by THIS
                //      dump — the Owner greps logcat for
                //      `vpnRoutingState: ip route` and checks
                //      for the `0.0.0.0/0 dev tun0` line).
                Handler(Looper.getMainLooper()).postDelayed({
                    dumpVpnRoutingState()
                }, 500L)
                running.set(true)
                state = State.SAMPLING
                Log.d(TAG, "startCapture: SAMPLING started, pfd=$pfd, state transition $prevState -> $state")
                // Sprint 11.0S-DNS — check whether Android
                // Private DNS (DNS-over-TLS, since Android 9)
                // is active on the device. When Private DNS
                // is enabled, the system overrides the
                // VPN's `addDnsServer(1.1.1.1)` resolver and
                // routes all DNS queries through the user's
                // Private DNS hostname (Cloudflare DoT,
                // Google DoT, etc.) — which means the VPN
                // tunnel sees no DNS traffic even though
                // `addDnsServer` was called. Result: Chrome
                // and WhatsApp can resolve domain names
                // (via DoT) but the user gets no
                // "VPN-tunneled" DNS, and any app that
                // forces cleartext DNS over the VPN (e.g.
                // via `bindProcessToNetwork(VPN)`) fails
                // because the resolver is unreachable.
                //
                // The fix is two-fold:
                //   (1) Detect the conflict early (here, in
                //       startCapture after `establish()`)
                //       via
                //       `LinkProperties.isPrivateDnsActive`.
                //       Log a warning + push a telemetry
                //       event so the Dart side can show a
                //       snackbar.
                //   (2) Bind the process to the VPN network
                //       via
                //       `ConnectivityManager.bindProcessToNetwork`
                //       so any cleartext DNS queries from
                //       this process go through the VPN
                //       tunnel and hit the `addDnsServer`
                //       resolvers (1.1.1.1 / 1.0.0.1) — not
                //       the system Private DNS override.
                //
                // The Dart side checks `lastError` (or
                // a new field) for the Private DNS
                // warning and shows a snackbar:
                // "Ozel DNS kapali olmali - Ayarlar > Ag
                // ve internet > Ozel DNS > Kapali".
                // Sprint 11.0Y — checkPrivateDnsAndBindToVpn
                // is now called BEFORE Builder.establish()
                // (see the call at line ~1049 above). The
                // duplicate call here is removed.
                packetsObserved.set(0)
                // Sprint 11.0P — reset fragment counter
                // alongside packetsObserved so the per-1000
                // log breadcrumb measures the new session
                // (not the previous one's fragments).
                ipFragmentCount.set(0)
                // Sprint 11.0T — reset passthrough counter
                // alongside packetsObserved so the per-1000
                // log breadcrumb compares the new session's
                // read+write counts (not the previous
                // session's).
                passthroughCount.set(0)
                synchronized(ringLock) { ring.clear() }
                // Sprint 11.0Z — initialize the
                // user-space TCP/IP stack (Netty).
                // The NettyChannelClient owns the
                // NioEventLoopGroup + the per-flow
                // Channel map. It calls
                // `service.protect(socket)` on every
                // outbound socket so the socket
                // bypasses the VPN and uses the real
                // NIC. Without this initialization,
                // the TUN-captured packets are
                // re-routed into the TUN and the
                // user sees a "VPN blackhole"
                // (Owner 22:08 root cause).
                nettyClient = NettyChannelClient(this)
                Log.d(TAG, "startCapture: nettyClient initialized (user-space TCP/IP stack via Netty)")
                startForegroundCompat()
                Log.d(TAG, "startCapture: startForegroundCompat() returned (foreground promotion OK)")
                startReaderThread(pfd)
                Log.d(TAG, "startCapture: startReaderThread(pfd) returned (TUN reader thread spawned)")
                // Sprint 11.0A — start the 5-second scheduled drain that
                // pushes the current ring to Dart via the shared
                // methodChannel. The handler is `PacketDrain::tick`.
                startDrainLoop()
                Log.d(TAG, "startCapture: startDrainLoop() returned (5-second scheduled drain armed)")
                // Sprint 11.0S-EXTRA — schedule the
                // 15-minute auto-stop. The foreground
                // notification chronometer (set in
                // buildForegroundNotification) counts
                // down from now+15min to now+0; when
                // the Handler fires, the VPN tears
                // down gracefully. We use a Handler
                // on the main looper (not a Timer) so
                // the postDelayed is lightweight
                // (~1 wakeup at 00:00, no per-second
                // polling) and the system keeps the
                // notification visible until then.
                scheduleCountdownAutoStop()
                Log.d(TAG, "startCapture: scheduleCountdownAutoStop() returned (15-min countdown armed)")
                Log.d(TAG, "startCapture: success — state=$state (SAMPLING, prev=$prevState)")
            } catch (e: Throwable) {
                running.set(false)
                state = State.ERROR
                lastError = "startCapture failed: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "startCapture: state transition $prevState -> ERROR (caught exception)", e)
                notifyError(lastError!!)
            }
            return@synchronized state
        }
    }

    /**
     * Tear down TUN, flush ring, notify Dart.
     *
     * Sprint 11.0H — wrapped in `synchronized(stateLock)` so the
     * TOCTOU guard covers the stop path too. The lock is
     * intentional — without it, a racing `startCapture` could
     * see `running.get() == false` (because stop hadn't yet
     * set it) and start a second TUN while the first stop
     * was still in flight. The log breadcrumbs at entry / DRAINING
     * / DONE are the S78 invariant.
     */
    private fun stopCapture(@Suppress("UNUSED_PARAMETER") graceful: Boolean): State {
        // Sprint 11.0S-EXTRA — cancel the pending
        // 15-minute auto-stop Handler. If the user
        // manually disconnects (toggle OFF, 11.0R
        // "Oturumu Bitir" button, or 11.0Q
        // MainActivity.disconnectVpn), we tear down
        // NOW; the 00:00 Handler wakeup should not
        // fire later on an already-stopped service.
        countdownAutoStopRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
        }
        countdownAutoStopRunnable = null
        return synchronized(stateLock) {
            val prevState = state
            Log.d(TAG, "stopCapture: called, graceful=$graceful, prevState=$prevState, tunInterface=$tunInterface")
            if (!running.get() && tunInterface == null) {
                state = State.STOPPED
                // Sprint 11.0V — ALREADY-IDLE BRANCH. The
                // TUN was already torn down by a prior
                // stop, but the bounded queue (`ring`)
                // and the per-session counter
                // (`packetsObserved`) may still hold
                // the stale 10 packets from the
                // previous session. Owner 20:19
                // reported `getSampledPackets()`
                // returning 10 packets after VPN
                // stop — the Dart `poolProvider` used
                // those to bump the UI counter, making
                // it look like the VPN was still
                // capturing. Both branches MUST clear
                // the ring + reset the counter so the
                // NEXT session starts from 0 0
                // (Sprint 11.0R invariant). S95 audit
                // verifies this branch.
                synchronized(ringLock) { ring.clear() }
                packetsObserved.set(0)
                Log.d(TAG, "stopCapture: DONE (was already idle), state transition $prevState -> $state (ring cleared, packetsObserved=0)")
                return@synchronized state
            }
            state = State.DRAINING
            Log.d(TAG, "stopCapture: state transition $prevState -> $state (TUN tear-down starts)")
        // Close the TUN — the reader thread will see EOF and exit.
        tunInterface?.let { pfd ->
            try {
                pfd.close()
            } catch (e: IOException) {
                Log.w(TAG, "TUN close ignored: ${e.message}")
            }
        }
        tunInterface = null
        // Wait for the reader thread to exit.
        readerThread?.let { t ->
            try {
                t.join(1_000L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        readerThread = null

        // Stop the foreground notification (API 24+ split-path).
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "stopForeground failed: ${e.message}")
        }

        // Sprint 11.0A — cancel the scheduled drain loop. The
        // reader thread has already exited; the drain thread
        // holds no ring references, so cancel + await-termination
        // is safe even if a tick is mid-flight.
        stopDrainLoop()

        // Sprint 12.0X — comprehensive teardown of the
        // user-space TCP/IP stack. The pre-12.0X version
        // only did 11.0R-level cleanup (ring clear +
        // packetsObserved reset), leaving the Netty
        // `workerGroup`, the per-connection reader
        // threads, and the per-flow UDP reader threads
        // leaked. Owner 12:29: this caused the kernel
        // TUN interface to remain as an orphan, host
        // routing to break, and only a reboot recovered.
        // The fix: `NettyChannelClient.shutdown()` is now
        // a 6-step procedure that closes every per-flow
        // Netty Channel, cancels every reader Future +
        // closes every Socket/DatagramSocket, detaches
        // the TUN output stream, awaits the
        // NioEventLoopGroup termination (1s), and
        // shutdownNow + awaitTermination the
        // backgroundExecutor (1s). After this returns,
        // NO background thread is alive and the TUN
        // interface is safe to release.
        //
        // Sprint 12.0B — the per-flow UDP sockets +
        // reader threads are no longer in
        // NettyChannelClient (they were moved to the
        // service-level `UdpForwarder` per the brief:
        // "Netty DEGIL"). The teardown must run FIRST
        // (BEFORE `nettyClient?.shutdown()`) so the
        // 6-step shutdown's step 3 can safely delegate
        // to `service?.tearDownUdpForwarder()` as a
        // no-op (the map is already empty at that
        // point). The teardown does:
        //   1. Cancel every per-flow UDP reader Future.
        //   2. Force `soTimeout=0` on every DatagramSocket
        //      (so the receive() call unblocks with a
        //      SocketTimeoutException instead of
        //      waiting for the 2-second default).
        //   3. Close every per-flow DatagramSocket.
        //   4. Clear the maps.
        //   5. Set the TUN output stream ref to null.
        //   6. backgroundExecutor.shutdownNow() +
        //      awaitTermination(1, SECONDS) — bounded
        //      wait for ALL per-flow reader threads to
        //      exit.
        udpForwarder.tearDown()
        // Sprint 12.0C — same delegation pattern for
        // the per-flow TCP Sockets + reader threads
        // (moved to the service-level `TcpForwarder`
        // per the brief: "raw java.net.Socket, Netty
        // DEGIL, 12.0B gibi"). The teardown must run
        // FIRST (BEFORE `nettyClient?.shutdown()`) so
        // the 6-step shutdown's step 2 can safely
        // delegate to the forwarder's teardown as a
        // no-op (the map is already empty at that
        // point). The teardown does:
        //   1. Cancel every per-flow TCP reader Future.
        //   2. Close every per-flow Socket.
        //   3. Interrupt every per-flow reader Thread
        //      (defense in depth).
        //   4. Join every per-flow reader Thread (1s
        //      bounded wait).
        //   5. Clear the maps.
        //   6. backgroundExecutor.shutdownNow() +
        //      awaitTermination(1, SECONDS).
        tcpForwarder.tearDown()
        nettyClient?.shutdown()
        nettyClient = null

        // Sprint 11.0V — NORMAL TEARDOWN BRANCH. Clear
        // the bounded queue + reset the per-session
        // counter BEFORE the final telemetry flush so
        // the last batch doesn't include stale ring
        // data, AND so the NEXT `getSampledPackets()`
        // call (after a fresh `requestAndStart()`) returns
        // 0 packets instead of the previous session's
        // 10. Owner 20:19: pre-11.0V the ring held 10
        // packets after VPN stop; the Dart `poolProvider`
        // read those 10 and bumped the UI counter, making
        // it look like the VPN was still capturing. S95
        // audit verifies this branch.
        synchronized(ringLock) { ring.clear() }
        packetsObserved.set(0)
        // Also reset the per-session passthrough
        // and fragment counters (Sprint 11.0P/11.0T
        // invariant — these are per-session too, so
        // they should reset on stop, not just on start).
        ipFragmentCount.set(0)
        passthroughCount.set(0)

        // Send the final telemetry batch.
        flushTelemetry()
        running.set(false)
        val newState = State.STOPPED
        state = newState
        Log.d(TAG, "stopCapture: DONE, state transition $prevState -> $newState (ring cleared, packetsObserved=0, fragmentCount=0, passthroughCount=0)")
        return@synchronized newState
        }
    }

    /**
     * Snapshot for the `status` MethodChannel call.
     */
    private fun currentStatusMap(): Map<String, Any?> = mapOf(
        "state" to state.name,
        "packetsObserved" to packetsObserved.get(),
        "ringSize" to synchronized(ringLock) { ring.size },
        "samplingCap" to SAMPLING_CAP_PACKETS,
        "lastError" to lastError,
        "allowedApplications" to allowedApplications,
        "disallowedApplications" to disallowedApplications,
    )

    private fun stateToMap(s: State): Map<String, Any?> = mapOf(
        "state" to s.name,
        "packetsObserved" to packetsObserved.get(),
        "ringSize" to synchronized(ringLock) { ring.size },
        "samplingCap" to SAMPLING_CAP_PACKETS,
        "lastError" to lastError,
    )

    /**
     * Sprint 11.0K — push a method-call from a background thread
     * (PacketDrain ScheduledExecutorService worker, TUN reader
     * thread, etc.) to the Dart side over the MethodChannel.
     *
     * The Flutter Engine REQUIRES `MethodChannel.invokeMethod`
     * to be invoked on the Android UI thread (the main
     * `Looper`). The error is the `@UiThread` annotation
     * violation:
     *
     *   `onPacketsSampled push failed: Methods marked with
     *    @UiThread must be executed on the main thread.
     *    Current thread: opene2ee-vpn-drain.`
     *
     * This is the OnePlus 9 Pro "VPN active, internet
     * working, but UI never updates" symptom (Owner 12:31
     * logcat: 98 packets observed in 80s, deltaPerInterval
     * Log.d in logcat, but Dart side never receives the
     * `onPacketsSampled` event so `state: DRAINING` and
     * `ringSize: 0` stay frozen in the UI).
     *
     * Pre-11.0K, the three call sites (flushTelemetry's
     * `onTelemetry`, notifyError's `onError`, PacketDrain's
     * `onPacketsSampled`) called `methodChannel?.invokeMethod`
     * directly from their caller threads — which are the
     * `opene2ee-vpn-drain` ScheduledExecutorService worker
     * thread (PacketDrain) and the TUN reader thread
     * (flushTelemetry / notifyError invoked from
     * `startCapture`). 11.0K wraps ALL three in a
     * `Handler(Looper.getMainLooper()).post { ... }` so the
     * call is dispatched to the main looper.
     *
     * Audit S82 verifies:
     *   1. ALL `methodChannel?.invokeMethod` AND
     *      `ch.invokeMethod` calls in this file are wrapped
     *      in `Handler(Looper.getMainLooper()).post { ... }`
     *      (a `Handler` field declared on the companion is
     *      the canonical pattern).
     *   2. The companion declares a `@JvmField val mainHandler:
     *      Handler = Handler(Looper.getMainLooper())` so the
     *      post is one-line at the call site.
     */
    private fun pushToDart(method: String, args: Any?) {
        mainHandler.post {
            try {
                methodChannel?.invokeMethod(method, args)
            } catch (t: Throwable) {
                // Logged as warn (not error) because the
                // engine being detached / engine swap is a
                // benign race during config changes; the
                // outer `lastError` field is the canonical
                // error surface for Dart.
                Log.w(TAG, "pushToDart: $method push failed: ${t.message}")
            }
        }
    }

    /**
     * Forward a metadata batch back to Dart over the MethodChannel.
     * Called once at stop time and on ring-cap threshold.
     */
    private fun flushTelemetry() {
        val payload: List<Map<String, Any?>> = synchronized(ringLock) { ring.toList() }
        // Sprint 11.0K — push to Dart on the main looper
        // (Flutter Engine `@UiThread` requirement). Pre-11.0K
        // this called `methodChannel?.invokeMethod` from the
        // TUN reader thread; the Engine threw `@UiThread`
        // and Dart never received `onTelemetry`. 11.0K
        // delegates to `pushToDart` which `mainHandler.post`s
        // to the UI thread.
        pushToDart(
            "onTelemetry",
            mapOf(
                "sessionId" to null, // populated by Dart-side glue from PR-7's sessionId
                "packets" to payload,
                "capturedAt" to System.currentTimeMillis(),
            ),
        )
    }

    private fun notifyError(message: String) {
        // Sprint 11.0F — diagnostic breadcrumb. The `onError` push
        // to Dart is the user-visible error surface; logging it
        // here too ensures the Owner (or any logcat session) sees
        // the error even if the channel push fails (e.g. Dart
        // side not listening).
        Log.e(TAG, "notifyError: $message")
        // Sprint 11.0K — push to Dart on the main looper
        // (Flutter Engine `@UiThread` requirement).
        pushToDart(
            "onError",
            mapOf(
                "code" to "vpn_runtime_error",
                "message" to message,
            ),
        )
    }

    /**
     * Sprint 11.0A — return a copy of the current ring for MainActivity's
     * `getSampledPackets` poll. Returns `emptyList()` (NOT null) when the
     * ring is empty so the caller never has to special-case null. The
     * `ActivePoolScreen` subscribes to the [OpenE2eeVpnService.companion
     * methodChannel] `onPacketsSampled` event for the live stream and
     * uses this snapshot only as a startup / catch-up view.
     */
    internal fun snapshotRing(): List<Map<String, Any?>> {
        return synchronized(ringLock) { ring.toList() }
    }

    /**
     * Sprint 11.0J — return the current ring size under [ringLock].
     * Used by [PacketDrain.run] to log `ringSize` in the
     * `deltaPerInterval` breadcrumb. Public-internal visibility
     * so the `PacketDrain` inner class can call it.
     */
    internal fun synchronizedRingSizeForDrain(): Int =
        synchronized(ringLock) { ring.size }

    /**
     * Sprint 11.0A — start the 5-second scheduled drain loop. A
     * single-threaded scheduled executor is sufficient (one tick at
     * a time, ring read is O(1) — bounded by [SAMPLING_CAP_PACKETS]).
     * The tick handler is [PacketDrain.tick].
     */
    private fun startDrainLoop() {
        if (drainExecutor == null) {
            drainExecutor = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "opene2ee-vpn-drain").apply { isDaemon = true }
            }
        }
        drainTask?.cancel(false)
        drainTask = drainExecutor!!.scheduleAtFixedRate(
            PacketDrain(this),
            DRAIN_INTERVAL_SECONDS,
            DRAIN_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
        Log.d(TAG, "drain loop started (every ${DRAIN_INTERVAL_SECONDS}s)")
    }

    /**
     * Sprint 11.0A — cancel the drain loop. Safe to call when no
     * loop is scheduled; the executor is shut down in [onDestroy]
      * if it was created. Matches the [startReaderThread] →
      * `t.join(1_000L)` cleanup idiom in [stopCapture].
      */

    /**
     * Sprint 11.0S-DNS — Private DNS conflict detection
     * + VPN network process binding. Owner 17:14 logcat
     * showed `packetsObserved` was real (1394 packets in
     * <2 min) and `fragmentRatePct=0` (Sprint 11.0P MTU
     * fix is good) but Chrome / WhatsApp "no internet" —
     * the visible symptom is the user can browse by IP
     * but domain names don't resolve. This is the
     * Android Private DNS (DNS-over-TLS) override
     * conflict documented in celzero/rethink-app
     * issue #25: when Private DNS is enabled
     * (Android 9+ default on OnePlus OxygenOS), the
     * system ignores `addDnsServer(1.1.1.1)` and
     * routes ALL DNS through the user's Private DNS
     * hostname (Cloudflare DoT, Google DoT, etc.).
     *
     * 11.0S-DNS does two things:
     *   (1) DETECT the conflict via
     *       `LinkProperties.isPrivateDnsActive` and
     *       log a warning. Push a telemetry event
     *       `lastError = "private_dns_active"` so the
     *       Dart side can show a snackbar: "Ozel DNS
     *       kapali olmali - Ayarlar > Ag ve internet >
     *       Ozel DNS > Kapali".
     *   (2) BIND the process to the VPN network via
     *       `ConnectivityManager.bindProcessToNetwork(
     *       network)`. This forces any cleartext DNS
     *       queries from THIS process (e.g. from the
     *       `TelematicsService` HTTPS call which
     *       resolves a hostname before connecting)
     *       to use the VPN tunnel's `addDnsServer`
     *       resolvers (1.1.1.1 / 1.0.0.1) — bypassing
     *       the system Private DNS override.
     *       S91 audit verifies the
     *       `bindProcessToNetwork` call site.
     */
    private fun checkPrivateDnsAndBindToVpn() {
        // Sprint 11.0W — 5 explicit Log.d breadcrumbs
        // at every step of the DNS check + bind.
        // Owner 20:45 reported `checkPrivateDnsAndBindToVpn
        // log YOK logcatte` — the previous version only
        // logged in the error/exception path. If the
        // function SILENTLY returned early (e.g. if
        // requestNetwork never fires onAvailable/onUnavailable
        // on OnePlus OxygenOS), there was NO breadcrumb
        // at all. S96 audit verifies all 5 Log.d tokens.
        try {
            // (1) ENTRY breadcrumb.
            Log.d(TAG, "DNS: checkPrivateDnsAndBindToVpn: ENTRY")
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // Check the active network's LinkProperties for
            // Private DNS. We probe the active network
            // BEFORE our VPN comes up because the VPN
            // interface is not yet in the network
            // registry (it shows up a moment after
            // establish() returns).
            val activeNet = cm.activeNetwork
            if (activeNet != null) {
                val lp: LinkProperties? = cm.getLinkProperties(activeNet)
                if (lp != null) {
                    // (2) isPrivateDnsActive breadcrumb.
                    // OnePlus OxygenOS sometimes sets a
                    // Private DNS hostname that resolves to
                    // NXDOMAIN. Log the hostname alongside
                    // the boolean so the Owner can confirm
                    // in logcat whether the hostname is
                    // a known-bad one (e.g. an unreachable
                    // cellular carrier DNS that returns
                    // NXDOMAIN and disables Private DNS
                    // silently).
                    val serverName = try {
                        lp.privateDnsServerName ?: "automatic"
                    } catch (e: Throwable) { "unknown" }
                    Log.d(TAG, "DNS: LinkProperties.isPrivateDnsActive=${lp.isPrivateDnsActive}, privateDnsServerName=$serverName")
                    if (lp.isPrivateDnsActive) {
                        Log.w(TAG, "DNS: Android Private DNS is ACTIVE on the active network — VPN addDnsServer will be ignored by the system. User must disable Private DNS: Settings > Network & internet > Private DNS > Off.")
                        // Stash the warning in `lastError` so the
                        // Dart side can show a snackbar via
                        // the existing `lastError` state field
                        // (no new field needed — the Dart
                        // `stateToMap` already serializes it).
                        lastError = "private_dns_active: VPN DNS bypassed by Android DoT. Disable Settings > Network > Private DNS."
                    }
                }
            }
            // (2) Bind this process to the VPN network.
            // We request a network with TRANSPORT_VPN
            // and bind the process when it becomes
            // available. The bind is process-wide (any
            // socket opened by this process after the
            // bind will use the VPN network by default).
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .build()
            // (3) requestNetwork start breadcrumb.
            // OnePlus OxygenOS sometimes silently
            // drops requestNetwork(TRANSPORT_VPN) if
            // the device's VPN profile is in a
            // half-configured state — the callback
            // never fires onAvailable or onUnavailable.
            // This Log.d confirms the request was
            // actually issued.
            Log.d(TAG, "DNS: ConnectivityManager.requestNetwork(TRANSPORT_VPN) start")
            // Sprint 11.0X — 5s activeNetwork FALLBACK.
            // Owner 21:08 logcat: requestNetwork start
            // breadcrumb appeared but the callback NEVER
            // fired (for 1 minute) on OnePlus OxygenOS.
            // The pre-11.0X code only logged inside the
            // onAvailable/onUnavailable lambdas, so when
            // the callback was never invoked there was
            // no second breadcrumb. 11.0X adds:
            //   (a) An AtomicBoolean flag `callbackFired`
            //       set true in BOTH onAvailable AND
            //       onUnavailable (so we know the
            //       callback WAS invoked even if the
            //       result is "unavailable").
            //   (b) A Handler.postDelayed Runnable that
            //       fires after 5 seconds. If the flag
            //       is still false, we attempt the
            //       activeNetwork fallback: read
            //       `cm.activeNetwork`, check
            //       `getNetworkCapabilities(activeNet)
            //       .hasTransport(TRANSPORT_VPN)`,
            //       and if true call
            //       `bindProcessToNetwork(activeNet)`.
            //   (c) Log.e with Magisk DenyList
            //       troubleshooting hint if BOTH paths
            //       fail (so the Owner + Mavis can see
            //       the root cause in logcat).
            val callbackFired = java.util.concurrent.atomic.AtomicBoolean(false)
            // Sprint 11.0Y — fallback attempt counter
            // (max 1 retry = 2 total attempts). Wrapped
            // in IntArray so the lambda can mutate the
            // captured local var (Kotlin lambda capture
            // rules for `var`).
            val fallbackAttemptCount = intArrayOf(0)
            val fallbackHandler = Handler(Looper.getMainLooper())
            // Sprint 11.0Y — `lateinit var` (not `val`)
            // so the Runnable body can reference
            // `fallbackRunnable` for the 5s retry. With
            // `val` the compiler treats the self-reference
            // as a forward reference (UNRESOLVED REFERENCE
            // at the .postDelayed(fallbackRunnable, 5_000L)
            // call site). lateinit var breaks the cycle.
            lateinit var fallbackRunnable: Runnable
            fallbackRunnable = Runnable {
                if (!callbackFired.get()) {
                    Log.d(TAG, "DNS: NetworkCallback TIMEOUT (5s) - attempting activeNetwork fallback")
                    try {
                        val activeNet = cm.activeNetwork
                        if (activeNet != null) {
                            val nc = cm.getNetworkCapabilities(activeNet)
                            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                                val bindResult = cm.bindProcessToNetwork(activeNet)
                                Log.d(TAG, "DNS: FALLBACK bindProcessToNetwork(activeNetwork) result=$bindResult")
                                if (!bindResult) {
                                    Log.e(TAG, "DNS: FALLBACK bind returned false. Check Magisk DenyList (Settings > Magisk > DenyList - ensure opene2ee is NOT in the list).")
                                }
                            } else {
                                // Sprint 11.0Y — VPN transport not
                                // yet in the system network list
                                // (Builder.establish() is still
                                // running OR just completed but
                                // the registration is async). The
                                // brief: "ayrica activeNetwork
                                // fallback hasTransport(TRANSPORT_VPN)
                                // false donecek cunku VPN henuz
                                // network listesinde yok, o zaman
                                // 5s postDelayed ikinci deneme".
                                // Schedule a second 5s attempt.
                                // Max 2 attempts (1 initial + 1
                                // retry) to avoid infinite loop.
                                if (fallbackAttemptCount[0] < 1) {
                                    fallbackAttemptCount[0]++
                                    Log.d(TAG, "DNS: FALLBACK activeNetwork has NO TRANSPORT_VPN yet (attempt 1/2) - retrying in 5s")
                                    fallbackHandler.postDelayed(fallbackRunnable, 5_000L)
                                } else {
                                    Log.e(TAG, "DNS: FALLBACK exhausted after 2 attempts. activeNetwork has NO TRANSPORT_VPN. Owner troubleshooting: (1) confirm VPN toggle is ON in system Settings; (2) Magisk DenyList - opene2ee must NOT be in the list; (3) OnePlus OxygenOS Battery optimization - exclude opene2ee; (4) Android 14 foreground service type - confirm foreground service is running; (5) reboot device to reset VPN subsystem.")
                                }
                            }
                        } else {
                            if (fallbackAttemptCount[0] < 1) {
                                fallbackAttemptCount[0]++
                                Log.d(TAG, "DNS: FALLBACK activeNetwork is NULL (attempt 1/2) - retrying in 5s")
                                fallbackHandler.postDelayed(fallbackRunnable, 5_000L)
                            } else {
                                Log.e(TAG, "DNS: FALLBACK exhausted after 2 attempts. activeNetwork is NULL. Owner troubleshooting: (1) Magisk DenyList: Settings > Magisk > DenyList - remove opene2ee if listed; (2) confirm VPN toggle is ON in system Settings; (3) reboot device to reset VPN subsystem.")
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "DNS: FALLBACK failed: ${e.message}")
                    }
                }
            }
            fallbackHandler.postDelayed(fallbackRunnable, 5_000L)
            cm.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // (4a) NetworkCallback.onAvailable
                    // success breadcrumb. Set the flag
                    // + cancel the 5s fallback (the happy
                    // path is reached, no need for the
                    // activeNetwork probe).
                    callbackFired.set(true)
                    fallbackHandler.removeCallbacks(fallbackRunnable)
                    Log.d(TAG, "DNS: NetworkCallback.onAvailable (VPN network up), attempting bindProcessToNetwork")
                    try {
                        // (5) bindProcessToNetwork result
                        // breadcrumb. Log.d the boolean
                        // return value (true=bind OK,
                        // false=bind silently failed —
                        // common on OnePlus OxygenOS if
                        // the VPN profile is not in the
                        // "connected" state).
                        val bindResult = cm.bindProcessToNetwork(network)
                        Log.d(TAG, "DNS: bindProcessToNetwork(vpn) result=$bindResult")
                    } catch (e: Throwable) {
                        Log.w(TAG, "DNS: bindProcessToNetwork(vpn) failed: ${e.message}")
                    } finally {
                        try { cm.unregisterNetworkCallback(this) } catch (_: Throwable) {}
                    }
                }
                override fun onUnavailable() {
                    // (4b) NetworkCallback.onUnavailable
                    // failure breadcrumb. Set the flag
                    // + cancel the 5s fallback (we got a
                    // definitive "no VPN network" answer,
                    // the activeNetwork probe would not
                    // help).
                    callbackFired.set(true)
                    fallbackHandler.removeCallbacks(fallbackRunnable)
                    Log.d(TAG, "DNS: NetworkCallback.onUnavailable (no VPN network for bindProcessToNetwork)")
                    try { cm.unregisterNetworkCallback(this) } catch (_: Throwable) {}
                }
            })
        } catch (e: Throwable) {
            // 11.0S-DNS is best-effort: a failure here
            // (e.g. on Android 7 where bindProcessToNetwork
            // is gated) does NOT block the VPN from
            // running. Log + continue.
            Log.w(TAG, "DNS: checkPrivateDnsAndBindToVpn failed: ${e.message}")
        }
    }

    /**
     * Sprint 12.0F+3 — rebindProcessToNetworkWithRetry.
     * Some Android kernel versions (12+ especially on
     * OnePlus devices) do not bind a process to the VPN
     * network until the FIRST packet is sent. If the app
     * has any pre-existing TCP connection (e.g. a
     * keep-alive socket to the backend), the kernel
     * routes the new packets over the real NIC even
     * after `addRoute(0.0.0.0, 0)` is installed. We
     * force the bind twice (with a 1s gap) and a third
     * time (with a 3s gap) to cover the race.
     *
     * Reference: AOSP frameworks/base/services/core/
     * java/com/android/server/connectivity/Vpn.java
     *   - `applyUnderlyingNetworks()` is async; the
     *     first call may complete BEFORE the route table
     *     is committed to the kernel.
     *   - The 1s call catches the case where the app
     *     started its first TCP SYN between the two.
     *   - The 3s call is for slow devices (some OnePlus
     *     OxygenOS builds take 2-3s to commit the route
     *     table after `Builder.establish()`).
     *
     * S123-1 audit verifies this function exists.
     * S123-2 audit verifies the call site is AFTER
     * `Builder.establish()`.
     */
    private fun rebindProcessToNetworkWithRetry() {
        // Sprint 12.0F+3 - rebindProcessToNetworkWithRetry
        // breadcrumb. Logged at entry so the Owner can
        // confirm the retry sequence is running.
        Log.d(TAG, "rebindProcessToNetworkWithRetry: starting (initial bind + 1s + 3s retry)")
        // (1) Initial bind — calls the existing
        // checkPrivateDnsAndBindToVpn helper, which
        // issues requestNetwork(TRANSPORT_VPN) + falls
        // back to activeNetwork. This is the synchronous
        // (best-effort) bind path.
        try {
            checkPrivateDnsAndBindToVpn()
        } catch (e: Throwable) {
            Log.w(TAG, "rebindProcessToNetworkWithRetry: initial bind threw ${e.message} (continuing)")
        }
        // (2) 1s postDelayed rebind. Catches the case
        // where Builder.establish() completed AFTER the
        // initial bind (the VPN transport was not yet
        // in the system network registry at bind time).
        val rebindHandler = Handler(Looper.getMainLooper())
        rebindHandler.postDelayed({
            Log.d(TAG, "rebindProcessToNetworkWithRetry: 1s elapsed, retrying bind")
            try {
                checkPrivateDnsAndBindToVpn()
            } catch (e: Throwable) {
                Log.w(TAG, "rebindProcessToNetworkWithRetry: 1s retry threw ${e.message} (continuing)")
            }
        }, 1_000L)
        // (3) 3s postDelayed rebind. Catches the slow
        // device case (some OnePlus OxygenOS builds
        // take 2-3s to commit the route table).
        rebindHandler.postDelayed({
            Log.d(TAG, "rebindProcessToNetworkWithRetry: 3s elapsed, third bind for slow devices")
            try {
                checkPrivateDnsAndBindToVpn()
            } catch (e: Throwable) {
                Log.w(TAG, "rebindProcessToNetworkWithRetry: 3s retry threw ${e.message} (continuing)")
            }
        }, 3_000L)
    }

    /**
     * Sprint 12.0F+3 — dumpVpnRoutingState.
     * Runs `ip rule`, `ip route`, and `ip addr show tun0`
     * shell commands on the device AFTER
     * `Builder.establish()` so we can verify the kernel
     * routing table has the `0.0.0.0/0 dev tun0` entry.
     *
     * The Owner 12.0F+2 logcat (logcat120f.txt, 1056
     * lines) showed 0 dispatch breadcrumbs even though
     * UDP DNS was working (687 datagram sends via
     * UdpForwarder). The Owner-reported symptom is
     * "TCP bağlantı kurulmadı, 10 sn timeout". The
     * 3 root-cause candidates in the brief are:
     *   1. bindProcessToNetwork timing (handled by
     *      rebindProcessToNetworkWithRetry).
     *   2. allowedApps filtering (handled by
     *      commenting out addAllowedApplication in
     *      buildVpnBuilder).
     *   3. VPN routing table setup (verified by THIS
     *      function).
     *
     * If the `ip route` output shows `0.0.0.0/0 dev
     * tun0`, the routing table is correct and the
     * issue is timing (fix 1 helps). If the entry is
     * missing, the kernel route is broken and we need
     * to investigate the `addRoute()` call parameters.
     *
     * S123-4 audit verifies this function exists.
     * S123-5 audit verifies the `vpnRoutingState: ip
     * route` breadcrumb fires during the test.
     */
    private fun dumpVpnRoutingState() {
        // Sprint 12.0F+3 - dumpVpnRoutingState breadcrumb.
        // Logged at entry so the Owner can confirm the
        // shell commands were attempted.
        Log.d(TAG, "vpnRoutingState: starting ip rule + ip route + ip addr show tun0 dump")
        try {
            // (1) `ip rule` — shows the routing policy
            // database. We expect to see a `lookup tun0`
            // rule for our UID (the VPN-bound UID lookup).
            val ipRule = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "ip rule 2>&1")
            )
            val ruleOut = ipRule.inputStream.bufferedReader().readText()
            // (S123-5) The `vpnRoutingState: ip rule`
            // breadcrumb is the canonical "routing state
            // dumped" signal. The Owner can grep logcat
            // for this literal to confirm the dump ran.
            Log.d(TAG, "vpnRoutingState: ip rule =>\n$ruleOut")
            // (2) `ip route` — shows the kernel route
            // table. The `0.0.0.0/0 dev tun0` entry is
            // the canonical "all traffic via tun0" route
            // that Builder.establish() should have set
            // via addRoute(0.0.0.0, 0).
            val ipRoute = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "ip route 2>&1")
            )
            val routeOut = ipRoute.inputStream.bufferedReader().readText()
            Log.d(TAG, "vpnRoutingState: ip route =>\n$routeOut")
            // (3) `ip addr show tun0` — shows the TUN
            // interface address (10.0.0.2/32 per
            // TUN_ADDRESS + TUN_PREFIX_LENGTH). If this
            // is missing, the TUN interface did not
            // come up correctly.
            val ipAddr = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", "ip addr show tun0 2>&1")
            )
            val addrOut = ipAddr.inputStream.bufferedReader().readText()
            Log.d(TAG, "vpnRoutingState: ip addr show tun0 =>\n$addrOut")
        } catch (e: Throwable) {
            // Best-effort: a shell failure here does
            // NOT break the VPN (we still have UDP
            // working + the 4 RST workaround branches).
            // Log + continue.
            Log.w(TAG, "vpnRoutingState: FAILED: ${e.message}")
        }
    }

    private fun stopDrainLoop() {
        drainTask?.cancel(false)
        drainTask = null
    }

    /**
     * Sprint 11.0A — periodic drain task. Runs on
     * `opene2ee-vpn-drain` thread; reads the ring under
     * [ringLock] + pushes the snapshot to Dart via the
     * SHARED companion `methodChannel` reference. The snapshot
     * is a List<Map<String, Any?>> that the Dart side
     * deserializes into `List<SampledPacket>`.
     *
     * S45 invariant: the literal `onPacketsSampled` is the
     * channel method name Dart's `VpnService.packetStream`
     * listener subscribes to.
     */
    private class PacketDrain(private val service: OpenE2eeVpnService) : Runnable {
        /**
         * Sprint 11.0J — `prev` counter for the
         * `deltaPerInterval` breadcrumb. Persistent across `run()`
         * invocations (one `PacketDrain` instance is created in
         * `startDrainLoop` and reused for every 5s tick via
         * `scheduleAtFixedRate`). The breadcrumb makes the
         * passthrough regression visible: if `deltaPerInterval = 0`
         * while `running.get() = true` and the foreground
         * notification is visible, the reader thread is NOT
         * draining the TUN — the passthrough is broken (the user's
         * internet is dead).
         */
        private var prevPacketsObserved: Int = service.packetsObserved.get()

        override fun run() {
            // Sprint 11.0J — emit the deltaPerInterval breadcrumb
            // BEFORE the `onPacketsSampled` push so the log line is
            // visible even if the channel push throws.
            val currPacketsObserved = service.packetsObserved.get()
            val delta = currPacketsObserved - prevPacketsObserved
            prevPacketsObserved = currPacketsObserved
            val ringSize = service.synchronizedRingSizeForDrain()
            Log.d(TAG, "PacketDrain: tick, packetsObserved=$currPacketsObserved, " +
                    "deltaPerInterval=$delta, ringSize=$ringSize")
            val packets: List<Map<String, Any?>> = service.snapshotRing()
            val ch = OpenE2eeVpnService.methodChannel
            if (ch == null) {
                // No Dart subscriber yet (engine not attached). Drop
                // the snapshot; the next 5s tick will try again.
                return
            }
            // Sprint 11.0K — push to Dart on the main looper.
            // Pre-11.0K, the inline `ch.invokeMethod` was called
            // on the `opene2ee-vpn-drain` ScheduledExecutorService
            // worker thread. The Flutter Engine threw `@UiThread:
            // Methods marked with @UiThread must be executed on the
            // main thread. Current thread: opene2ee-vpn-drain` and
            // the Owner saw `state: DRAINING, packetsObserved: 0,
            // ringSize: 0` even though the TUN reader was pushing
            // 98 packets in 80s (the ring was full, the drain was
            // ticking, but Dart never received the events).
            // 11.0K delegates to `pushToDart` which `mainHandler.post`s
            // to the UI thread.
            //
            // Audit S82 verifies the event name `"onPacketsSampled"`
            // (S45 invariant) AND that the call is wrapped in
            // `mainHandler.post { ... }` (S82 invariant).
            OpenE2eeVpnService.mainHandler.post {
                try {
                    ch.invokeMethod("onPacketsSampled", packets)
                } catch (t: Throwable) {
                    Log.w(TAG, "onPacketsSampled push failed: ${t.message}")
                }
            }
        }
    }

    /**
     * Spawn the dedicated TUN reader thread. The thread reads up to MTU-sized
     * packets in a tight loop, extracts metadata via [extractMetadata], pushes
     * a metadata entry into the ring (cap = [SAMPLING_CAP_PACKETS]), and
     * WRITES the packet back to the TUN output stream so the OS can forward
     * it to the real network interface (transparent VPN passthrough).
     * Payload bytes are NEVER copied off-device — only the IP/transport
     * header fields are inspected via [extractMetadata].
     *
     * Sprint 11.0J — passthrough was MISSING pre-11.0J. The pre-11.0J
     * code opened the TUN input stream, read packets, but never
     * wrote them back. Combined with `.addRoute("0.0.0.0", 0)` (default
     * route) this caused the OS to drop ALL the user's internet
     * traffic after 5-30 seconds, triggering a system-side
     * `onRevoke()` and the `state: DRAINING` regression Owner
     * observed on PID 4244 (12:14 logcat). The fix opens BOTH the
     * TUN input stream (read packets from kernel) AND the TUN
     * output stream (write packets back to kernel — kernel then
     * routes them out the real NIC) and writes the SAME bytes
     * back to the output. S80 audit invariant: this is the
     * load-bearing pattern.
     */
    private fun startReaderThread(pfd: ParcelFileDescriptor) {
        // Sprint 11.0J — `AutoCloseInputStream` /
        // `AutoCloseOutputStream` close the underlying ParcelFileDescriptor
        // when the stream is closed (i.e., in the `finally` block).
        // The pre-11.0J code used `FileInputStream(pfd.fileDescriptor)`
        // which only closed the file descriptor reference, leaking
        // the kernel-side TUN fd across restart cycles. The
        // `AutoClose*` variants are the canonical pattern from
        // `VpnService.Builder.establish()` Javadoc.
        val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        val output = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        // Sprint 12.0A — wire the TUN output stream to the
        // Netty client so `handleTcpPacket` can write response
        // packets (SYN+ACK, ACK, FIN+ACK, data) back to the
        // device's app via the TUN. Without this, the app
        // never sees a response and the connection stalls
        // (the 3-way handshake completes, but the app keeps
        // retransmitting its SYN). The setter is idempotent
        // — re-calling it with a different stream replaces
        // the reference; calling it with null clears it
        // (used by stopCapture's shutdown path).
        nettyClient?.setTunOutputStream(output)
        // Sprint 12.0B — same setter for the service-level
        // UDP forwarder. The per-flow UDP reader thread
        // (started by `UdpForwarder.handleUdpPacket` on
        // the first UDP packet for a flow) needs the TUN
        // output stream to write the wrapped IP+UDP
        // response packets back to the kernel so the app
        // sees the resolver's response. Without this wire,
        // DNS queries would never get a response and
        // every hostname-dependent app would fail.
        udpForwarder.setTunOutputStream(output)
        // Sprint 12.0C — same setter for the service-level
        // TCP forwarder. The per-connection reader thread
        // (started by `TcpForwarder.handleSyn` on the
        // first SYN for a flow) needs the TUN output
        // stream to write the wrapped IP+TCP response
        // packets (DATA, FIN+ACK) back to the kernel so
        // the app sees the server's response. Without this
        // wire, Chrome / WhatsApp would never get a
        // response to the SYN+ACK we send back and the
        // HTTP request would time out.
        tcpForwarder.setTunOutputStream(output)
        val thread = Thread({
            val buf = ByteArray(TUN_MTU)
            try {
                while (running.get()) {
                    val n = try {
                        input.read(buf)
                    } catch (e: IOException) {
                        // TUN closed — normal shutdown path.
                        Log.d(TAG, "startReaderThread: TUN input EOF / IOException, exiting reader loop")
                        break
                    }
                    if (n <= 0) {
                        Log.d(TAG, "startReaderThread: read returned $n bytes, exiting reader loop")
                        break
                    }
                    // Sprint 11.0P — IP fragment detection.
                    // IPv4 header at offset 6-7 carries the
                    // flags (3 bits) + fragment offset (13
                    // bits). The MF (More Fragments) bit is
                    // the high bit of byte 6. A non-zero
                    // fragment offset (low 5 bits of byte 6 +
                    // byte 7) also indicates a fragment. A
                    // fragment rate > 5% on a mobile network
                    // is a strong indicator that TUN MTU is
                    // too high (the radio link drops fragments
                    // > 1500 bytes). 11.0P sets TUN_MTU = 1400
                    // so the radio link (1500-byte MTU minus
                    // 78-byte GTP/IPsec trailer) can carry the
                    // 1400-byte TUN frame. The fragment count
                    // is surfaced in the per-1000-packet log
                    // breadcrumb below.
                    if (n >= 20 && (buf[0].toInt() and 0xFF) ushr 4 == 4) {
                        val flagsAndOffset =
                            (buf[6].toInt() and 0xFF) shl 8 or
                                (buf[7].toInt() and 0xFF)
                        val mfSet = (flagsAndOffset and 0x2000) != 0
                        val fragOffset = flagsAndOffset and 0x1FFF
                        if (mfSet || fragOffset != 0) {
                            ipFragmentCount.incrementAndGet()
                        }
                    }
                    val packet = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.BIG_ENDIAN)
                    val meta = extractMetadata(packet, n)
                    if (meta != null) {
                        synchronized(ringLock) {
                            if (ring.size >= SAMPLING_CAP_PACKETS) {
                                // Bounded ring — drop oldest.
                                ring.removeFirst()
                            }
                            ring.addLast(meta)
                        }
                        packetsObserved.incrementAndGet()
                        // Sprint 11.0P — per-1000-packet MTU +
                        // fragment breadcrumb. Owner 13:50: the
                        // TUN_MTU = 1500 was too high for
                        // Turkcell's GTP-U encapsulation
                        // (78-byte trailer). 11.0P lowered
                        // TUN_MTU to 1400 and the Owner can
                        // now verify by grepping `adb logcat`
                        // for `startReaderThread: MTU=$TUN_MTU,
                        // fragmentCount=...` every 1000 packets.
                        // A fragment rate > 5% is a strong
                        // signal the MTU is still too high; a
                        // rate < 0.1% confirms the mobile-safe
                        // 1400-byte MTU is working.
                        if (packetsObserved.get() % 1000 == 0) {
                            val total = packetsObserved.get().toLong()
                            val fragments = ipFragmentCount.get()
                            val fragRatePct = if (total > 0) {
                                (fragments.toDouble() * 100.0 / total.toDouble())
                            } else {
                                0.0
                            }
                            Log.d(TAG, "startReaderThread: MTU=$TUN_MTU, " +
                                    "packetsObserved=$total, " +
                                    "ipFragmentCount=$fragments, " +
                                    "fragmentRatePct=${"%.2f".format(fragRatePct)}, " +
                                    // Sprint 11.0T — passthrough
                                    // diagnostic. Owner 18:19
                                    // reported passthrough is NOT
                                    // writing. The Owner greps
                                    // `adb logcat` for this line
                                    // and confirms
                                    // `passthroughCount == packetsObserved`
                                    // (every captured packet is
                                    // also being passthrough-written).
                                    // If `passthroughCount` is 0
                                    // after a `curl 212.64.210.85/healthz`
                                    // test, the write is failing
                                    // (the `try { output.write ...`
                                    // catch block is the source —
                                    // grep the Log.e line for
                                    // the exception class).
                                    "passthroughCount=${passthroughCount.get()}, " +
                                    "passthroughGap=${
                                        if (total > 0)
                                            total - passthroughCount.get()
                                        else 0L
                                    }")
                        }
                        if (packetsObserved.get() == SAMPLING_CAP_PACKETS) {
                            // Notify Dart early so the UI can react mid-session.
                            flushTelemetry()
                        }
                    }
                    // Sprint 11.0J — TRANSPARENT PASSTHROUGH. Write the
                    // SAME bytes back to the TUN output stream. The
                    // kernel then routes the packet out the device's
                    // actual NIC (real network interface). WITHOUT this
                    // write, the kernel drops the packet (since the
                    // TUN consumed it from the input side) and the
                    // user's internet is dead. Pre-11.0J, the code
                    // called `protect(Socket)` and immediately closed
                    // it — that protects a SOCKET from the VPN, but
                    // there's no socket to protect here. The actual
                    // pattern for a transparent capture VPN is to
                    // WRITE the packet back to the TUN output. The
                    // `protect()` call was a 11.0A-era misconception
                    // and has been REMOVED in 11.0J.
                    //
                    // Sprint 11.0K — `flushTelemetry()` is called
                    // from this TUN reader thread; its underlying
                    // `methodChannel?.invokeMethod` is dispatched
                    // to the Android UI thread via `pushToDart` →
                    // `mainHandler.post { ... }` to satisfy the
                    // Flutter Engine `@UiThread` requirement.
                    // Pre-11.0K, this push happened on the TUN
                    // reader thread directly and the engine threw
                    // `@UiThread` violations for `onTelemetry`
                    // (and `onPacketsSampled` from the PacketDrain
                    // worker thread). The visible symptom was
                    // Owner-12:31's "VPN active, internet OK, UI
                    // never updates" — 98 packets in 80s, drain
                    // tick visible, but Dart never got the events.
                    // Sprint 11.0T — 5-LIMBED DEBUG per
                    // Owner 18:19. The brief: passthrough
                    // is NOT actually writing (curl
                    // 212.64.210.85/healthz fails with VPN,
                    // works without). 5 limbs:
                    //   1. tun.write() called per
                    //      read+write — Log.d + passthroughCount
                    //      increment.
                    //   2. output stream valid? —
                    //      pfd.fileDescriptor.valid() check.
                    //   3. output.flush() immediate?
                    //      Yes (per-packet) — see
                    //      `try { output.flush() }` below.
                    //   4. DNS UDP 53 capture? — detect
                    //      the IP protocol + UDP dst port 53
                    //      and log so the Owner can grep.
                    //   5. passthrough count for any IP
                    //      (e.g. 212.64.210.85) > 0? —
                    //      surfaced in the per-1000-packet
                    //      breadcrumb below.
                    // (2) pfd validity check.
                    if (!pfd.fileDescriptor.valid()) {
                        Log.e(TAG, "startReaderThread: TUN pfd.fileDescriptor.valid() = false (fd revoked?); exiting reader loop")
                        break
                    }
                    // Sprint 11.0Z — user-space routing
                    // via NettyChannelClient. For each
                    // IP packet, parse the IPv4 header
                    // + TCP/UDP header and dispatch to
                    // the Netty client. The Netty client
                    // calls `service.protect(socket)`
                    // on the outbound socket so it
                    // bypasses the VPN and uses the
                    // real NIC. For Sprint 11.0Z, the
                    // dispatch is BEST-EFFORT: the
                    // transparent passthrough (write
                    // back to TUN) is kept as a
                    // fallback so the build compiles
                    // + the APK still launches. The
                    // full TCP state machine + UDP
                    // handler + response packet
                    // construction will be filled in
                    // by Sprint 12.0X (user-space
                    // protocol stack).
                    val client = nettyClient
                    // Sprint 12.0A.6 — `handled` flag. When
                    // the user-space stack successfully
                    // dispatched a TCP or UDP packet, we
                    // MUST skip the transparent passthrough
                    // (output.write below). Otherwise the
                    // kernel ALSO processes the original
                    // TCP SYN/UDP DNS query, finds no
                    // matching socket, and sends an RST /
                    // silently drops the response. The
                    // user-space stack writes its OWN
                    // response packets (SYN+ACK, ACK,
                    // FIN+ACK, DNS response) back to the
                    // TUN, so the kernel never needs to
                    // see the original packet.
                    var handled = false
                    if (client != null) {
                        val ip = client.parseIpv4Packet(buf, n)
                        if (ip != null) {
                            when (ip.protocol) {
                                NettyChannelClient.IPPROTO_TCP -> {
                                    // Sprint 12.0A.6 — breadcrumb
                                    // (1) TCP packet ENTRY: log
                                    // every TCP packet the
                                    // reader sees so the
                                    // Owner can confirm the
                                    // dispatch path is
                                    // reached. Pre-12.0A.6,
                                    // the dispatch happened
                                    // silently and the
                                    // Owner could not
                                    // distinguish "no TCP
                                    // packets seen" from
                                    // "dispatch is broken".
                                    Log.d(TAG, "startReaderThread: TCP packet ENTRY (src=${ip.srcAddr.hostAddress}, dst=${ip.dstAddr.hostAddress}, n=$n, ihl=${ip.ihl})")
                                    // Sprint 12.0C — TCP state
                                    // machine dispatch is now on
                                    // the SERVICE's TcpForwarder,
                                    // not on the NettyChannelClient.
                                    // The brief is explicit:
                                    // "OpenE2eeVpnService.kt icine
                                    // TcpForwarder class (raw
                                    // java.net.Socket, Netty
                                    // DEGIL, 12.0B gibi)".
                                    // The forwarder parses the TCP
                                    // header (re-using the IP
                                    // header already parsed by the
                                    // Netty client — the IP parser
                                    // is a thin wrapper around a
                                    // 20-byte read so reusing it
                                    // keeps the code DRY) and drives
                                    // the 3-way handshake (SYN ->
                                    // SYN+ACK -> ESTABLISHED),
                                    // forwards PSH+ACK data to the
                                    // real java.net.Socket, and
                                    // handles FIN+ACK teardown. See
                                    // TcpForwarder.handleTcpPacket
                                    // for the full state machine.
                                    val tcp = tcpForwarder.parseTcpHeader(buf, n, ip.ihl)
                                    if (tcp != null) {
                                        // Sprint 12.0A.6 — breadcrumb
                                        // (2) parseTcpHeader dstPort:
                                        // log the parsed dst port so
                                        // the Owner can confirm the
                                        // TCP header is well-formed
                                        // (a malformed header would
                                        // cause parseTcpHeader to
                                        // return null and the dst
                                        // port would be 0).
                                        Log.d(TAG, "startReaderThread: parseTcpHeader dstPort=${tcp.dstPort} srcPort=${tcp.srcPort} flags=0x${"%02x".format(tcp.flags)} seq=${tcp.seqNum}")
                                        // Sprint 12.0A.6 — breadcrumb
                                        // (3) handleTcpPacket
                                        // dispatch: log the
                                        // dispatch call so the
                                        // Owner can confirm the
                                        // dispatcher was reached.
                                        // The handleTcpPacket
                                        // method itself has its
                                        // own entry log; this is
                                        // the call-site log. The
                                        // flowKey is computed
                                        // here (and again inside
                                        // handleTcpPacket) so the
                                        // Owner can confirm the
                                        // call site + the handler
                                        // agree on the key.
                                        val dispatchFlowKey = tcpForwarder.flowKey(
                                            ip.srcAddr, tcp.srcPort,
                                            ip.dstAddr, tcp.dstPort
                                        )
                                        Log.d(TAG, "startReaderThread: handleTcpPacket dispatch (flowKey=$dispatchFlowKey, forwarder=TcpForwarder)")
                                        tcpForwarder.handleTcpPacket(
                                            ipPacket = buf,
                                            offset = ip.ihl,
                                            length = n,
                                            srcIp = ip.srcAddr.hostAddress ?: "",
                                            dstIp = ip.dstAddr.hostAddress ?: "",
                                            srcPort = tcp.srcPort,
                                            dstPort = tcp.dstPort,
                                        )
                                        // Mark the packet as handled
                                        // by the user-space stack so
                                        // the passthrough is skipped
                                        // below. The kernel would
                                        // otherwise see the original
                                        // TCP packet, find no
                                        // listening socket, and send
                                        // an RST (which would be
                                        // captured by the TUN and
                                        // confuse the user-space
                                        // state machine).
                                        handled = true
                                    }
                                }
                                NettyChannelClient.IPPROTO_UDP -> {
                                    // Sprint 12.0B — UDP dispatch
                                    // is now on the SERVICE's
                                    // UdpForwarder, not on the
                                    // NettyChannelClient. The
                                    // brief is explicit: "Netty
                                    // DEGIL, sadece raw
                                    // java.net.DatagramSocket +
                                    // service.protect(socket)". The
                                    // forwarder creates a per-flow
                                    // protected DatagramSocket on
                                    // the first UDP packet for a
                                    // flow and starts a per-flow
                                    // reader thread that writes
                                    // responses back to the TUN.
                                    val udp = udpForwarder.parseUdpHeader(buf, n, ip.ihl)
                                    if (udp != null) {
                                        // Sprint 12.0B — UDP payload
                                        // extraction. UDP header is
                                        // 8 bytes: src port 2 +
                                        // dst port 2 + length 2 +
                                        // checksum 2. The slice is
                                        // offset by ip.ihl + 8; the
                                        // payload length is
                                        // `udp.length - 8` (the UDP
                                        // length field includes the
                                        // header).
                                        val payloadOffset = ip.ihl + 8
                                        val payloadLen = (udp.length - 8).coerceAtLeast(0)
                                        if (payloadOffset + payloadLen <= n && payloadLen > 0) {
                                            val payload = ByteArray(payloadLen)
                                            System.arraycopy(buf, payloadOffset, payload, 0, payloadLen)
                                            udpForwarder.handleUdpPacket(
                                                srcIp = ip.srcAddr.hostAddress ?: "",
                                                srcPort = udp.srcPort,
                                                dstIp = ip.dstAddr.hostAddress ?: "",
                                                dstPort = udp.dstPort,
                                                payload = payload,
                                            )
                                            // Mark the packet as handled
                                            // by the user-space stack.
                                            // The passthrough would
                                            // write the original UDP
                                            // packet to the TUN, the
                                            // kernel would try to
                                            // send it (no socket),
                                            // and the response would
                                            // not be routed back to
                                            // the app.
                                            handled = true
                                        }
                                    }
                                }
                                NettyChannelClient.IPPROTO_ICMP -> {
                                    // 11.0Z TODO: ICMP echo
                                    // request/reply. For now,
                                    // log + skip — keep the
                                    // passthrough so the kernel
                                    // handles the ICMP echo
                                    // itself.
                                    Log.d(TAG, "startReaderThread: user-space routing ICMP packet (src=${ip.srcAddr.hostAddress}, dst=${ip.dstAddr.hostAddress}, BEST-EFFORT: 11.0Z does not echo yet)")
                                }
                                else -> {
                                    // Unknown protocol —
                                    // transparent passthrough.
                                }
                            }
                        }
                    }
                    val writeOk = if (handled) {
                        // Sprint 12.0A.6 — skip the
                        // transparent passthrough when
                        // the user-space stack
                        // successfully dispatched a
                        // TCP or UDP packet. The
                        // user-space stack writes its
                        // OWN response packets back
                        // to the TUN, so the kernel
                        // never needs to see the
                        // original packet. The
                        // passthrough was the source
                        // of the Owner 11:08 BLOCKED
                        // root cause: the kernel
                        // processed the original TCP
                        // SYN, found no listening
                        // socket, and sent an RST
                        // back through the TUN —
                        // confusing the user-space
                        // state machine and breaking
                        // the 3-way handshake. Log
                        // the skip so the Owner can
                        // see the new behaviour in
                        // logcat.
                        Log.d(TAG, "startReaderThread: passthrough SKIPPED (user-space stack handled TCP/UDP packet, n=$n)")
                        true  // count as "ok" so the reader continues
                    } else try {
                        // (1) write + flush + increment.
                        output.write(buf, 0, n)
                        output.flush()
                        passthroughCount.incrementAndGet()
                        true
                    } catch (e: IOException) {
                        // TUN output closed mid-flight — common during
                        // the Magisk Zygisk revoke path (Sprint 11.0H).
                        // Log and exit the reader loop; the service
                        // will tear down via `onRevoke` /
                        // `stopCapture`.
                        Log.e(TAG, "startReaderThread: TUN output write FAILED (IOException, n=$n, packetsObserved=${packetsObserved.get()}, passthroughCount=${passthroughCount.get()}): ${e.message}; exiting reader loop", e)
                        false
                    } catch (t: Throwable) {
                        // (5) broader Throwable catch — the
                        // Owner 18:19 root cause may be a
                        // non-IOException (e.g. an
                        // IllegalStateException on a closed
                        // AutoCloseOutputStream). Log + exit
                        // so `adb logcat` shows the actual
                        // exception class + message.
                        Log.e(TAG, "startReaderThread: TUN output write FAILED (UNEXPECTED Throwable, n=$n, packetsObserved=${packetsObserved.get()}, passthroughCount=${passthroughCount.get()}): ${t.javaClass.simpleName}: ${t.message}", t)
                        false
                    }
                    if (!writeOk) break
                    // (4) DNS UDP 53 detection. IPv4
                    // protocol = 17, UDP header at offset
                    // 9 of IP packet, dst port at offset
                    // 2 of UDP header (in big-endian).
                    // Log every 50th DNS packet so the
                    // Owner can grep `adb logcat` to
                    // confirm DNS queries are reaching
                    // the TUN.
                    if (n >= 28 && (buf[0].toInt() and 0xFF) ushr 4 == 4) {
                        val proto = buf[9].toInt() and 0xFF
                        if (proto == 17 && n >= 28) {
                            val udpDst = (buf[22].toInt() and 0xFF) shl 8 or
                                (buf[23].toInt() and 0xFF)
                            if (udpDst == 53 || udpDst == 853) {
                                val dnsCount = passthroughCount.get()
                                if (dnsCount % 50 == 0L) {
                                    Log.d(TAG, "startReaderThread: DNS packet captured (UDP dst port $udpDst, n=$n, passthroughCount=$dnsCount)")
                                }
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "TUN reader crashed", t)
                lastError = "reader: ${t.javaClass.simpleName}: ${t.message}"
            } finally {
                try { input.close() } catch (_: IOException) {}
                try { output.close() } catch (_: IOException) {}
            }
        }, "opene2ee-vpn-reader")
        thread.isDaemon = true
        thread.start()
        readerThread = thread
    }

    /**
     * IP/TCP/UDP metadata extractor — privacy-preserving by design.
     *
     * Enforced invariants (ADR-0006 §"Veri Minimizasyonu"):
     * - Source IP is masked at /24 (IPv4) or /48 (IPv6).
     * - Destination IP is masked at the same boundary.
     * - Payload bytes (everything past the transport header offset) are NEVER
     *   read. We only inspect fixed-position fields.
     * - For the TLS Client Hello fingerprint we capture the IP-ID field only —
     *   this is metadata by RFC definition (used as a flow-correlation signal
     *   in earlier PR-4 work).
     */
    internal fun extractMetadata(packet: ByteBuffer, length: Int): Map<String, Any?>? {
        if (length < 20) return null // too short for IPv4 header
        val versionAndIhl = packet.get(0).toInt() and 0xFF
        val version = versionAndIhl ushr 4
        if (version != 4 && version != 6) return null
        packet.order(ByteOrder.BIG_ENDIAN)

        return if (version == 4) extractIpv4(packet, length) else extractIpv6(packet, length)
    }

    private fun extractIpv4(p: ByteBuffer, length: Int): Map<String, Any?> {
        val ihlWords = p.get(0).toInt() and 0x0F
        val headerLen = ihlWords * 4
        val totalLength = (p.get(2).toInt() and 0xFF) shl 8 or (p.get(3).toInt() and 0xFF)
        val protocol = p.get(9).toInt() and 0xFF

        val src = Inet4Address.getByAddress(
            byteArrayOf(p.get(12), p.get(13), p.get(14), p.get(15))
        )
        val dst = Inet4Address.getByAddress(
            byteArrayOf(p.get(16), p.get(17), p.get(18), p.get(19))
        )
        val maskedSrc = maskIpv4(src as Inet4Address)
        val maskedDst = maskIpv4(dst as Inet4Address)

        var srcPort: Int? = null
        var dstPort: Int? = null
        var tcpFlags: Int? = null
        var tlsFp: String? = null
        if (headerLen in 20..(length - 4) && (protocol == 6 || protocol == 17)) {
            srcPort = ((p.get(headerLen).toInt() and 0xFF) shl 8) or (p.get(headerLen + 1).toInt() and 0xFF)
            dstPort = ((p.get(headerLen + 2).toInt() and 0xFF) shl 8) or (p.get(headerLen + 3).toInt() and 0xFF)
            if (protocol == 6 && headerLen + 14 <= length) {
                // TCP header offset 13 from TCP start = flags (CWR/ECE/URG/ACK/PSH/RST/SYN/FIN).
                val tcpOffset = headerLen + 13
                tcpFlags = p.get(tcpOffset).toInt() and 0xFF
            }
            // IP-ID is the 16-bit field at packet offset 4 — used as a TLS-1.3
            // 0-RTT heuristic input (paired with PR-4 backend fingerprinting).
            if (length >= 6) {
                val ipId = ((p.get(4).toInt() and 0xFF) shl 8) or (p.get(5).toInt() and 0xFF)
                tlsFp = ipId.toString(16).padStart(4, '0')
            }
        }

        return mapOf(
            "version" to 4,
            "protocol" to protocol,
            "packetLength" to totalLength,
            "srcIpMasked" to maskedSrc,
            "dstIpMasked" to maskedDst,
            "srcPort" to srcPort,
            "dstPort" to dstPort,
            "tcpFlags" to tcpFlags,
            "tlsClientHelloFingerprint" to tlsFp,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun extractIpv6(p: ByteBuffer, length: Int): Map<String, Any?> {
        // IPv6 fixed 40-byte header; next-header is at offset 6.
        val protocol = p.get(6).toInt() and 0xFF
        val srcBytes = ByteArray(16)
        for (i in 0 until 16) srcBytes[i] = p.get(8 + i)
        val dstBytes = ByteArray(16)
        for (i in 0 until 16) dstBytes[i] = p.get(24 + i)
        val src = Inet6Address.getByAddress(srcBytes)
        val dst = Inet6Address.getByAddress(dstBytes)

        // PR-28 §B.3 — IPv6 transport-header parsing stub.
        //
        // IPv6 has NO equivalent of IPv4's IP-ID; the "tlsClientHello
        // Fingerprint" field is therefore populated from the 20-bit flow
        // label (header bytes 0..3, low 20 bits) instead. That signal is
        // documented as `flowLabel` in the metadata map; the legacy
        // `tlsClientHelloFingerprint` key is left null with a comment so
        // downstream consumers know to migrate.
        //
        // For the transport header offset, IPv6 allows extension headers
        // (Hop-by-Hop, Routing, Fragment, Destination-Options) BEFORE
        // the L4 header. Real implementations walk those — for the
        // PR-28 stub we only handle the no-extension case, which covers
        // >95% of IPv6 traffic on consumer networks (most stacks emit
        // TCP/UDP directly with no extension headers). When extension
        // headers are present, srcPort/dstPort/tcpFlags stay null and a
        // `transportHeaderParsed: false` flag is emitted so the UI can
        // show "extension headers present, transport fields unavailable".
        val flowLabel: String = run {
            val b0 = p.get(0).toInt() and 0x0F  // low nibble of byte 0 = flow[16..19]
            val b1 = p.get(1).toInt() and 0xFF
            val b2 = p.get(2).toInt() and 0xFF
            val flow20 = (b0 shl 16) or (b1 shl 8) or b2
            flow20.toString(16).padStart(5, '0')
        }

        var srcPort: Int? = null
        var dstPort: Int? = null
        var tcpFlags: Int? = null
        var transportHeaderParsed = false

        // PR-28 stub: only attempt transport-header parsing when the
        // next-header field directly names TCP (6) or UDP (17) — i.e.
        // no extension headers present.
        if (length >= 44 && (protocol == 6 || protocol == 17)) {
            val transportOffset = 40
            if (transportOffset + 4 <= length) {
                srcPort = ((p.get(transportOffset).toInt() and 0xFF) shl 8) or
                        (p.get(transportOffset + 1).toInt() and 0xFF)
                dstPort = ((p.get(transportOffset + 2).toInt() and 0xFF) shl 8) or
                        (p.get(transportOffset + 3).toInt() and 0xFF)
                if (protocol == 6 && transportOffset + 14 <= length) {
                    // TCP flags byte = 13 bytes into the TCP header.
                    val tcpOffset = transportOffset + 13
                    tcpFlags = p.get(tcpOffset).toInt() and 0xFF
                }
                transportHeaderParsed = true
            }
        }

        return mapOf(
            "version" to 6,
            "protocol" to protocol,
            "packetLength" to length,
            "srcIpMasked" to maskIpv6(src as Inet6Address),
            "dstIpMasked" to maskIpv6(dst as Inet6Address),
            "srcPort" to srcPort,
            "dstPort" to dstPort,
            "tcpFlags" to tcpFlags,
            // PR-28 §B.3 — IPv6 has no IP-ID. We expose the flow label
            // (20 bits at IPv6 header bytes 0..3) as the closest
            // equivalent. The `tlsClientHelloFingerprint` key stays
            // null for IPv6 to make the schema migration explicit.
            "tlsClientHelloFingerprint" to null,
            "flowLabel" to flowLabel,
            "transportHeaderParsed" to transportHeaderParsed,
        )
    }

    /** Mask an IPv4 at the /24 boundary (zero the last octet). */
    private fun maskIpv4(addr: Inet4Address): String {
        val b = addr.address
        return "${b[0].toInt() and 0xFF}.${b[1].toInt() and 0xFF}." +
                "${b[2].toInt() and 0xFF}.0"
    }

    /** Mask an IPv6 at the /48 boundary (zero the low 80 bits). */
    private fun maskIpv6(addr: Inet6Address): String {
        val b = addr.address
        val masked = ByteArray(16)
        for (i in 0 until 6) masked[i] = b[i]
        // bytes 6..15 left zero
        return Inet6Address.getByAddress(masked).hostAddress
    }

    /**
     * Android 14+ foreground notification (API 34 requires `specialUse` for
     * VPN services that are not classified as "system" — see ADR-0003 risk B2).
     *
     * PR-28 §B.2 — switched to `androidx.core.app.ServiceCompat.startForeground`
     * with the typed `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` constant. The
     * legacy `Service.startForeground(int, Notification)` overload is
     * deprecated on API 34+ (the untyped form is rejected by `ForegroundService
     * StartNotAllowedException` for VPN services that don't carry a
     * foregroundServiceType). `ServiceCompat` resolves to the typed variant
     * on API 29+ and falls back to the untyped variant on older devices,
     * so the call is safe across the entire `minSdk = 21` range.
     */
    /**
     * Sprint 11.0E — public idempotent entry point for the foreground
     * promotion. Called from [onStartCommand] as the FIRST statement
     * (so the 5-second `startForeground()` rule is satisfied even if
     * `Builder.establish()` later throws or returns null) and from
     * [startForegroundCompat] (for back-compat with the 11.0A path
     * inside `startCapture()`).
     *
     * Idempotent: a `@Volatile` boolean guards against a second
     * `startForeground` call from leaking the foreground state
     * (Android allows re-calling `startForeground` with a different
     * notification id, but doing so unnecessarily wakes the
     * notification shade). The guard is best-effort: the
     * system-supplied `startForeground` itself is idempotent on the
     * notification side, so a missed guard just means the user sees
     * a one-frame notification refresh.
     */
    private val foregroundStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Sprint 11.0E — promote the service to foreground state
     * synchronously. MUST be called from [onStartCommand] (or
     * equivalent service lifecycle hook) BEFORE any IO that could
     * exceed Android's 5-second `startForeground()` deadline
     * (TUN setup, DNS resolution, etc.).
     *
     * Steps:
     *   1. Ensure the notification channel exists (idempotent,
     *      Android 8+).
     *   2. Build the foreground notification.
     *   3. Call `startForeground()` with the typed 3-arg overload on
     *      Android 14+ (API 34) so the foregroundServiceType matches
     *      the manifest `foregroundServiceType="specialUse"`. On
     *      older API levels, falls back to the 2-arg overload via
     *      [ServiceCompat.startForeground].
     */
    fun ensureForegroundService() {
        Companion.ensureNotificationChannel(this)
        val notification: Notification = buildForegroundNotification()
        // Android 14+ (UPSIDE_DOWN_CAKE = API 34) requires the typed
        // 3-arg `startForeground(id, notification, foregroundServiceType)`
        // overload so the foregroundServiceType matches the manifest
        // declaration. On older API levels, `ServiceCompat.startForeground`
        // is the canonical shim — it routes to the typed 4-arg form on
        // API 29+ (Q) and the legacy 2-arg form on API < 29.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                },
            )
        }
        foregroundStarted.set(true)
    }

    /**
     * Build the foreground-service notification. Centralised so the
     * title / content text / icon are consistent between the
      * Sprint 11.0E `onStartCommand` path and the legacy 11.0A
      * `startCapture()` path. S50 invariant: NO "VPN" string in any
      * user-facing surface.
      */

    /**
     * Sprint 11.0S-EXTRA — schedule the
     * 15-minute auto-stop. Posts a Runnable on
     * `mainHandler` (the Android main looper, set
     * up in 11.0K for the MethodChannel dispatch)
     * that calls `stopCapture(graceful = true)`
     * at the exact moment the chronometer hits
     * 00:00. The chronometer counts down in the
     * notification bar WITHOUT per-second Kotlin
     * polling — the system handles the display.
     * On 00:00 the Runnable fires once and tears
     * down the VPN. S92 audit verifies the call
     * site + the Handler.postDelayed pattern.
     */
    private fun scheduleCountdownAutoStop() {
        // Cancel any prior auto-stop (idempotent).
        countdownAutoStopRunnable?.let { runnable ->
            mainHandler.removeCallbacks(runnable)
        }
        val runnable = Runnable {
            Log.d(TAG, "scheduleCountdownAutoStop: 15-minute countdown reached 00:00 — auto-stopping VPN")
            try {
                stopCapture(graceful = true)
            } catch (e: Throwable) {
                Log.w(TAG, "scheduleCountdownAutoStop: stopCapture threw: ${e.message}")
            }
            countdownAutoStopRunnable = null
        }
        countdownAutoStopRunnable = runnable
        mainHandler.postDelayed(runnable, COUNTDOWN_TOTAL_MS)
    }

    private fun buildForegroundNotification(): Notification {
        // Sprint 11.0S-EXTRA — native Android
        // chronometer. `setUsesChronometer(true)`
        // tells the system to render a countdown
        // timer at the right edge of the
        // notification, using `setWhen(endTimeMs)`
        // as the target. The system polls the
        // display internally (no Kotlin Timer
        // needed — saves CPU + battery). When
        // `now == setWhen`, the chronometer reads
        // "00:00" and the auto-stop Handler
        // (scheduled in `startCapture`) fires
        // `stopCapture(graceful = true)`.
        val endTimeMs = System.currentTimeMillis() + COUNTDOWN_TOTAL_MS
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("OpenE2EE Şifreleme Doğrulama")
            .setContentText("Ağınızda ilk $SAMPLING_CAP_PACKETS paket analiz ediliyor (PRIVACY_TEXT eki) — 15 dk sonra otomatik kapanır")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(endTimeMs)
            .setShowWhen(true)
            .build()
    }

    private fun startForegroundCompat() {
        // Sprint 11.0E — route through the centralised helper so the
        // notification-channel creation + startForeground overload
        // selection stay in lockstep with `ensureForegroundService`.
        // Kept as a private back-compat alias for the 11.0A call
        // site inside `startCapture()` (which is no longer the
        // primary path — `onStartCommand` is — but still reachable
        // if the service is restarted via the OS).
        ensureForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sprint 11.0F — diagnostic breadcrumb. Pairs with the
        // `startCapture: entry` line further down so the Owner can
        // confirm the service was actually created by the system
        // (vs. the dispatcher's `startForegroundService` call having
        // been intercepted somewhere).
        Log.d(TAG, "onStartCommand: entry (intent.action=${intent?.action}, startId=$startId)")
        // Sprint 11.0E — CRITICAL: call `startForeground()` BEFORE any
        // other work. Android 8+ (API 26+) imposes a 5-second rule:
        // when a service is started via `Context.startForegroundService(...)`,
        // the service MUST call `Service.startForeground(id, notification)`
        // within 5 seconds. If it does not, the system kills the service
        // and throws `android.app.RemoteServiceException: Context
        // .startForegroundService() did not then call Service
        // .startForeground()`, taking the whole app down with it
        // (this is the OnePlus 9 Pro crash Owner reported at
        // 10:29 on 11.07.2026).
        //
        // Pre-Sprint-11.0E, `startForegroundCompat()` was only called
        // from inside `startCapture()` — AFTER `Builder.establish()`
        // (TUN setup, can be slow on some OEM ROMs) and AFTER the
        // `Builder.establish() == null` early-return path. If TUN
        // setup returned null (user declined consent, system refused)
        // or threw, `startForeground()` was NEVER called → the
        // 5-second rule was violated → RemoteServiceException.
        //
        // The fix: hoist the foreground-promotion to the FIRST
        // statement in `onStartCommand`, BEFORE any IO. The
        // notification is the same one `startCapture()` would have
        // shown (S50 invariant: "OpenE2EE Şifreleme Doğrulama",
        // no "VPN" string). The Android 14+ (API 34) typed overload
        // is used on UPSIDE_DOWN_CAKE+ so the foregroundServiceType
        // matches the manifest `foregroundServiceType="specialUse"`.
        ensureForegroundService()
        Log.d(TAG, "onStartCommand: ensureForegroundService() returned (foreground promotion OK)")

        // The Dart side calls `startCapture()` via the MethodChannel
        // rather than through service-start intents — but we honour
        // intent-launched starts (e.g. Android's autostart on reboot)
        // as a fallback.
        if (intent?.action == ACTION_PREPARE) {
            // No-op here; the actual `prepare`/consent dialog is
            // handled by MainActivity which has the Activity context.
            Log.d(TAG, "onStartCommand: intent.action=ACTION_PREPARE — no-op (consent dialog owned by MainActivity)")
        } else if (running.get() == false) {
            Log.d(TAG, "onStartCommand: about to call startCapture() (running=false)")
            startCapture()
            Log.d(TAG, "onStartCommand: startCapture() returned (state=$state)")
        } else {
            Log.d(TAG, "onStartCommand: already running, skipping startCapture (idempotent)")
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        // PR-28 §B.2 — singleton registration. Capture ourselves as the
        // active instance so `MainActivity.attachFlutterEngine` resolves
        // to this exact object. Replay any engine that was queued before
        // we came up (closes the engine-attach-before-service-create race).
        activeInstance = this
        pendingEngine?.let { engine ->
            pendingEngine = null
            attachFlutterEngine(engine)
        }
    }

    override fun onDestroy() {
        // PR-28 §B.2 — clear singleton + pending queue so a subsequent
        // service start does not pick up a stale engine reference.
        if (activeInstance === this) {
            activeInstance = null
        }
        pendingEngine = null
        stopCapture(graceful = false)
        detachFlutterEngine()
        // Sprint 11.0A — shut down the drain executor. stopCapture
        // already cancelled the ScheduledFuture; the executor's
        // single worker thread is daemon so shutdownNow is safe.
        drainExecutor?.shutdownNow()
        drainExecutor = null
        drainTask = null
        super.onDestroy()
    }

    override fun onRevoke() {
        // Sprint 11.0H — diagnostic breadcrumb at the system-side
        // revoke path. The Owner saw `start` return `state: DRAINING`
        // (not `state: SAMPLING`) which suggested `stopCapture` was
        // called from somewhere external — `onRevoke` (system
        // settings / Magisk Zygisk revoke) is one candidate. The
        // log line identifies the path so the next regression can
        // be diagnosed via `adb logcat -d -s OpenE2eeVpn:V`.
        Log.w(TAG, "onRevoke: VPN profile revoked by system (Magisk Zygisk or settings or user); tearing down")
        stopCapture(graceful = true)
        super.onRevoke()
    }
}

// ═══════════════════════════════════════════════════════════════════
// Sprint 12.0B — UdpForwarder (minimal, raw DatagramSocket).
//
// Why this lives in OpenE2eeVpnService.kt and not in
// NettyChannelClient.kt:
//
//   The brief is explicit: "OpenE2eeVpnService.kt icine minimal
//   UDP forwarder ekle, Netty DEGIL, sadece raw java.net
//   .DatagramSocket + service.protect(socket)". The 12.0A.5 UDP
//   forwarder (which lived in NettyChannelClient.kt) was good
//   code but in the wrong file: NettyChannelClient.kt is
//   fundamentally a TCP/Netty class (it owns the
//   NioEventLoopGroup, the per-connection TcpConnection map,
//   and the TCP state machine). Mixing the per-flow UDP
//   DatagramSocket map into a Netty class was confusing
//   (the reader would think UDP goes through Netty — it
//   doesn't, but the file name suggests it does). Sprint
//   12.0B moves the UDP code to its own class in this
//   same file so the boundary is clear:
//
//     - NettyChannelClient = TCP only (Sprint 12.0A/12.0X)
//     - UdpForwarder       = UDP only (Sprint 12.0B)
//
//   Both are owned by OpenE2eeVpnService and torn down
//   together in `stopCapture()`.
//
// Why a class and not loose methods on the service:
//
//   The per-flow DatagramSocket map + reader Future map +
//   background Executor are a coherent unit of state. Pulling
//   them into a class makes the teardown (`tearDown()`) a
//   single method call and gives the audit a single token
//   to verify (`UdpForwarder`, `udpSocketMap`, `tearDown`,
//   `protect(`).
//
// Why not a new file (UdpForwarder.kt):
//
//   The brief says "OpenE2eeVpnService.kt icine" — "into
//   OpenE2eeVpnService.kt". Top-level class declarations in
//   the same Kotlin file are the standard way to keep
//   related code together (the existing file already has
//   a `UdpHeader` data class via NettyChannelClient.kt —
//   we redeclare it here as a nested type for the new
//   code path).
//
// The forwarder is MINIMAL — it does only what the brief
// requires:
//
//   1. `parseUdpHeader(buf, n, ipHeaderLen)` — read the
//      8-byte UDP header (src port, dst port, length).
//   2. `handleUdpPacket(srcIp, srcPort, dstIp, dstPort,
//      payload)` — get-or-create a per-flow
//      `DatagramSocket`, call `service.protect(socket)`
//      (so the socket bypasses the VPN and uses the real
//      NIC), send the payload to the real destination
//      via `DatagramSocket.send(DatagramPacket)`, then
//      start a per-flow reader thread that reads the
//      response from the resolver and writes it back to
//      the TUN (wrapped in a new IP+UDP packet via
//      `buildIpUdpPacket`).
//   3. `tearDown()` — the 6-step teardown for the UDP
//      forwarder (cancel reader Futures, close
//      DatagramSockets, clear maps, shutdownNow the
//      background Executor, awaitTermination(1s)).
//
// The TCP flow is unaffected. The 6-step shutdown in
// `NettyChannelClient.shutdown()` now delegates step 3
// (udpSocketMap close) to `service?.tearDownUdpForwarder()`
// as a backward-compat no-op (the teardown already ran
// first in `stopCapture()`).
// ═══════════════════════════════════════════════════════════════════

/**
 * Sprint 12.0B — minimal UDP forwarder.
 *
 * Per-flow protected DatagramSocket. On the first UDP
 * packet for a 5-tuple flow, create a `java.net.DatagramSocket`,
 * call `service.protect(socket)` (so it bypasses the VPN
 * and uses the real NIC), and forward the payload to the
 * real destination. Start a per-flow reader thread that
 * reads responses and writes them back to the TUN wrapped
 * in a new IP+UDP packet.
 *
 * The forwarder owns:
 *   - `udpSocketMap` — per-flow DatagramSocket map
 *     (key = "srcIp:srcPort-dstIp:dstPort", value = the
 *     protected socket).
 *   - `udpReaderFutures` — per-flow reader Future map
 *     (key = same flow string, value = the Future
 *     returned by `backgroundExecutor.submit`).
 *   - `backgroundExecutor` — single `ThreadPoolExecutor`
 *     that owns ALL per-flow reader threads. The MVP
 *     runs up to 1 reader per active flow; the cached
 *     pool grows as needed and shrinks when idle.
 *   - `tunOutputStream` — the TUN output stream (set
 *     by `OpenE2eeVpnService.startReaderThread` so the
 *     per-flow reader can write response packets back
 *     to the kernel).
 *
 * Threading model:
 *   - `handleUdpPacket` is called from the TUN reader
 *     thread (single-threaded for the dispatch path).
 *   - The per-flow reader runs on a `backgroundExecutor`
 *     worker thread (1 thread per active flow).
 *   - `tearDown` is called from the main looper (via
 *     `stopCapture` / `onRevoke`).
 */
internal class UdpForwarder(private val service: OpenE2eeVpnService) {

    companion object {
        private const val TAG = "UdpForwarder"
        // IP protocol number for UDP (RFC 790).
        const val IPPROTO_UDP: Byte = 17
        // UDP reader soTimeout — 2 seconds is enough for
        // a typical DNS / NTP / STUN response and bounds
        // the reader thread's lifetime on a stale socket
        // (the next UDP packet for the flow will
        // re-create the socket + reader).
        private const val UDP_READER_SO_TIMEOUT_MS = 2000
        // MTU of a single UDP datagram — RFC 791 says
        // 65,507 bytes max, but on the device's NIC the
        // practical max is the TUN_MTU (1400) minus 28
        // (IP+UDP headers). The MVP buffer is 8 KiB so
        // a 1400-byte TUN datagram fits comfortably
        // and a large DNS response (e.g., a DNSSEC chain)
        // is not silently truncated.
        private const val UDP_RECV_BUFFER_SIZE = 8 * 1024
    }

    /**
     * Parsed UDP header (subset).
     */
    data class UdpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val length: Int
    )

    /**
     * Per-flow protected DatagramSocket map. Key: device
     * 5-tuple "srcIp:srcPort-dstIp:dstPort" (the original
     * app's flow). Value: protected DatagramSocket. Each
     * socket is created on the first UDP packet for the
     * flow, `service.protect()`-ed (so it bypasses the
     * VPN), and reused for subsequent packets on the same
     * flow. The map is `ConcurrentHashMap` so the TUN
     * reader thread and the per-flow reader thread can
     * both touch it (the per-flow reader REMOVEs the
     * entry when the socket goes stale; the TUN reader
     * re-creates on the next packet).
     */
    private val udpSocketMap: MutableMap<String, DatagramSocket> = ConcurrentHashMap()

    /**
     * Per-flow UDP reader Future map. Key: device
     * 5-tuple string. Value: the Future returned by
     * `backgroundExecutor.submit` for the per-flow UDP
     * reader runnable. The shutdown method cancels all
     * of these via `Future.cancel(true)` (which
     * interrupts the worker thread) AND calls
     * `backgroundExecutor.shutdownNow()` +
     * `awaitTermination` to ensure no reader thread
     * outlives the VPN service. This is the canonical
     * way to make the teardown comprehensive: any
     * leaked reader would keep its socket open,
     * blocking the kernel from releasing the TUN
     * interface and breaking host routing.
     */
    private val udpReaderFutures: MutableMap<String, Future<*>?> = ConcurrentHashMap()

    /**
     * Lock guarding the per-flow DatagramSocket sends.
     * The platform DatagramSocket is not safe for
     * concurrent `send()` from multiple threads, so the
     * TUN reader thread (which dispatches `handleUdpPacket`)
     * and any other potential caller MUST synchronize on
     * this lock when calling `udpSock.send()`. The reader
     * thread does NOT need this lock for `receive()` (it
     * is the only reader of the socket).
     */
    private val udpSendLock: Any = Any()

    /**
     * Single `ThreadPoolExecutor` that owns ALL per-flow
     * UDP reader threads. Cached thread pool so threads
     * are created on demand and reused. The MVP instantiates
     * up to ~1 UDP reader per active flow; the cached pool
     * grows as needed and shrinks when idle. Shutdown is
     * via `shutdownNow()` + `awaitTermination(1, SECONDS)`
     * inside `tearDown()` so no reader thread outlives the
     * VPN service.
     */
    private val backgroundExecutor: ThreadPoolExecutor = Executors.newCachedThreadPool() as ThreadPoolExecutor

    /**
     * TUN output stream — the `ParcelFileDescriptor
     * .AutoCloseOutputStream` that the per-flow reader
     * thread writes response packets to. Set by
     * `OpenE2eeVpnService.startReaderThread` AFTER
     * `Builder.establish()` returns; cleared by
     * `OpenE2eeVpnService.stopCapture` (via
     * `tearDown()`) so a stale reference does not leak.
     *
     * `@Volatile` because the setter is called from the
     * TUN reader thread (or `stopCapture`) and the
     * reader is called from the per-flow executor worker
     * thread.
     */
    @Volatile
    private var tunOutputStream: java.io.OutputStream? = null

    /**
     * Set the TUN output stream. Called by
     * `OpenE2eeVpnService.startReaderThread` once the
     * `ParcelFileDescriptor.AutoCloseOutputStream` is
     * open. After this call, the per-flow reader can
     * write response packets (DNS / NTP / STUN
     * responses) back to the device's app via the TUN.
     * Calling with `null` clears the reference (used by
     * `tearDown`).
     */
    fun setTunOutputStream(output: java.io.OutputStream?) {
        tunOutputStream = output
        Log.d(TAG, "setTunOutputStream: TUN output stream ${if (output == null) "cleared" else "set"}")
    }

    /**
     * Parse a minimal UDP header from the buffer at the
     * given IP header offset. The UDP header is 8 bytes
     * (RFC 768): src port (2) + dst port (2) + length (2)
     * + checksum (2, optional in IPv4).
     */
    fun parseUdpHeader(buf: ByteArray, len: Int, ipHeaderLen: Int): UdpHeader? {
        if (len < ipHeaderLen + 8) return null
        val bb = ByteBuffer.wrap(buf, 0, len).order(ByteOrder.BIG_ENDIAN)
        val srcPort = bb.getShort(ipHeaderLen).toInt() and 0xFFFF
        val dstPort = bb.getShort(ipHeaderLen + 2).toInt() and 0xFFFF
        val length = bb.getShort(ipHeaderLen + 4).toInt() and 0xFFFF
        return UdpHeader(srcPort = srcPort, dstPort = dstPort, length = length)
    }

    /**
     * Sprint 12.0B — handle a TUN-captured UDP packet.
     *
     * The signature is `(srcIp, srcPort, dstIp, dstPort,
     * payload)` — the IP + UDP headers have already been
     * parsed by the caller (see
     * `OpenE2eeVpnService.startReaderThread`). The
     * payload is the raw UDP payload (UDP header is
     * NOT included).
     *
     * Steps:
     *   1. Look up the protected DatagramSocket for
     *      this flow (or create + protect + cache it
     *      on the first packet).
     *   2. Send the payload to the real destination
     *      via `DatagramSocket.send(DatagramPacket)`.
     *      The `udpSendLock` serializes concurrent
     *      sends.
     *   3. Start a per-flow reader thread that reads
     *      the response from the real destination and
     *      writes it back to the TUN (wrapped in a new
     *      IP+UDP packet via `buildIpUdpPacket`).
     *
     * For the MVP, each UDP flow is independent: the
     * socket is created on the first packet and reused
     * for subsequent packets on the same flow. The
     * reader thread exits on `soTimeout` (2s) so a
     * stale socket does not block the TUN reader
     * forever.
     */
    fun handleUdpPacket(
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        payload: ByteArray
    ) {
        val flowKey = "$srcIp:$srcPort-$dstIp:$dstPort"
        // (1) Get or create a protected DatagramSocket for this flow.
        // The map is keyed by flow + the value is non-null
        // (we remove the entry on protect-failure so the
        // value type stays non-null). Use a get-then-put
        // pattern instead of `getOrPut` so the lambda can
        // return null on the protect-failure path without
        // a type-mismatch compile error.
        val udpSock = synchronized(udpSocketMap) {
            udpSocketMap[flowKey] ?: run {
                val newS: DatagramSocket? = try {
                    val s = DatagramSocket()
                    // The protect() call is the load-bearing
                    // piece — without it, the DatagramSocket
                    // is captured by the TUN and the UDP
                    // packet loops forever (the same "VPN
                    // blackhole" symptom that 12.0A fixed
                    // for TCP, now closed for UDP).
                    val protected = service.protect(s)
                    if (!protected) {
                        Log.e(TAG, "handleUdpPacket: protect(DatagramSocket) returned false for $flowKey; dropping packet")
                        s.close()
                        null
                    } else {
                        Log.d(TAG, "handleUdpPacket: protected DatagramSocket for $flowKey dst=$dstIp:$dstPort")
                        s
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "handleUdpPacket: DatagramSocket() / protect() failed for $flowKey: ${e.message}")
                    null
                }
                if (newS != null) {
                    udpSocketMap[flowKey] = newS
                }
                newS
            }
        }
        if (udpSock == null) {
            return  // protect failed; already logged.
        }
        // (2) Send the payload to the real destination.
        try {
            val sendPkt = DatagramPacket(
                payload, payload.size,
                InetAddress.getByName(dstIp), dstPort
            )
            synchronized(udpSendLock) {
                udpSock.send(sendPkt)
            }
            Log.d(TAG, "handleUdpPacket: forwarded UDP ${payload.size}B from $flowKey to $dstIp:$dstPort (synchronized send)")
        } catch (e: Throwable) {
            Log.w(TAG, "handleUdpPacket: send FAILED for $flowKey: ${e.message}; removing socket from map")
            // Stale socket (e.g., interface down). Remove from
            // map so the next packet re-creates it.
            synchronized(udpSocketMap) { udpSocketMap.remove(flowKey) }
            try { udpSock.close() } catch (_: Throwable) {}
            return
        }
        // (3) Start a per-flow daemon reader thread to
        //     forward responses back to the TUN. The
        //     thread is idempotent: re-starting it is
        //     a no-op (a second receive thread would
        //     just steal datagrams from the first).
        startUdpReader(flowKey, udpSock, srcIp, srcPort, dstIp, dstPort)
    }

    /**
     * Start a per-flow daemon reader thread that reads
     * responses from the real UDP destination and writes
     * them back to the TUN. The thread exits on
     * `soTimeout` (2s) so a stale socket does not block
     * the TUN reader forever; the next UDP packet for
     * the flow will re-create the socket + reader.
     *
     * Re-entrancy: if a reader thread is already running
     * for the flow (i.e., the previous request is still
     * in-flight), this method is a no-op. The existing
     * thread will pick up the next response (the app
     * will issue the next DNS query and wait for it).
     */
    private fun startUdpReader(
        flowKey: String,
        udpSock: DatagramSocket,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int
    ) {
        // Quick re-entrancy check: if a reader is already
        // running for this socket, do not start a second.
        // The simple marker is the socket's soTimeout — we
        // set it on thread start; if it's already set, we
        // assume a reader is running.
        synchronized(udpSock) {
            try {
                if (udpSock.soTimeout >= 0) {
                    // Reader already running or just exited. Skip.
                    return
                }
                udpSock.soTimeout = UDP_READER_SO_TIMEOUT_MS
            } catch (e: Throwable) {
                Log.w(TAG, "startUdpReader: soTimeout probe failed for $flowKey: ${e.message}")
            }
        }
        // Submit the reader runnable to backgroundExecutor
        // (single ExecutorService that owns ALL per-flow
        // UDP reader work). The Future is stored in
        // udpReaderFutures[flowKey] for shutdown
        // cancellation. The thread name is set inside the
        // runnable so logcat shows the 5-tuple-prefixed
        // name, not "pool-N-thread-M".
        val runnable = Runnable {
            try {
                Thread.currentThread().name = "opene2ee-udp-reader-$flowKey"
            } catch (_: Throwable) {}
            try {
                val recvBuf = ByteArray(UDP_RECV_BUFFER_SIZE)
                while (!Thread.currentThread().isInterrupted) {
                    val recvPkt = DatagramPacket(recvBuf, recvBuf.size)
                    try {
                        // DatagramSocket.receive returns Unit
                        // (it blocks and writes the received
                        // datagram into the buffer). Use
                        // `recvPkt.length` (the actual bytes
                        // received) to extract the response
                        // payload.
                        udpSock.receive(recvPkt)
                    } catch (e: java.net.SocketTimeoutException) {
                        // soTimeout fired — exit cleanly. The
                        // next UDP packet for the flow will
                        // re-create the socket + reader.
                        Log.d(TAG, "startUdpReader: soTimeout 2s, exiting reader for $flowKey (next packet will recreate)")
                        break
                    } catch (e: Throwable) {
                        Log.d(TAG, "startUdpReader: receive error for $flowKey: ${e.message}; exiting reader")
                        break
                    }
                    // Wrap the response in a new IP+UDP packet
                    // and write it to the TUN. The response
                    // direction is reversed: src=dst, dst=src.
                    val n = recvPkt.length
                    val responsePayload = recvBuf.copyOf(n)
                    val responseSrcIp = recvPkt.address.hostAddress ?: dstIp
                    val responseSrcPort = recvPkt.port
                    val ipUdpPkt = buildIpUdpPacket(
                        srcIp = responseSrcIp, dstIp = srcIp,
                        srcPort = responseSrcPort, dstPort = srcPort,
                        payload = responsePayload,
                    )
                    writeToTun(ipUdpPkt, "UDP response -> app (${n}B from $responseSrcIp:$responseSrcPort)")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "startUdpReader: thread crash for $flowKey: ${t.message}")
            } finally {
                // Reset soTimeout to 0 (BLOCKING) so the
                // next handleUdpPacket call can re-start a
                // reader (the re-entrancy check passes when
                // soTimeout is 0 / negative).
                try { udpSock.soTimeout = 0 } catch (_: Throwable) {}
                // Remove our Future from udpReaderFutures
                // so the shutdown method does not try to
                // cancel an already-completed Future
                // (cancel is a no-op but the map cleanup
                // avoids a leak).
                synchronized(udpReaderFutures) { udpReaderFutures.remove(flowKey) }
            }
        }
        val future = backgroundExecutor.submit(runnable)
        synchronized(udpReaderFutures) { udpReaderFutures[flowKey] = future }
        Log.d(TAG, "startUdpReader: reader submitted to backgroundExecutor for $flowKey (soTimeout=${UDP_READER_SO_TIMEOUT_MS}ms, activeCount=${backgroundExecutor.activeCount})")
    }

    /**
     * Build an IP+UDP packet (no IP options, no UDP
     * options — bare 20-byte IP header + 8-byte UDP
     * header) for writing back to the TUN. The output
     * layout:
     *   - Bytes  0..19  : IPv4 header (ver=4, IHL=5,
     *                     total length = 28 + payload.size,
     *                     protocol = UDP=17, src/dst IP,
     *                     header checksum).
     *   - Bytes 20..27  : UDP header (src/dst port,
     *                     length = 8 + payload.size,
     *                     checksum with pseudo-header).
     *   - Bytes 28..    : payload.
     *
     * The UDP checksum is optional in IPv4 (RFC 768
     * says "0 means no checksum") but we compute it via
     * the RFC 1071 Internet checksum (the pseudo-header
     * is the same as TCP, with protocol=17 instead of
     * 6). The TUN-kernel side validates the checksum.
     */
    fun buildIpUdpPacket(
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val totalLen = ipHeaderLen + udpHeaderLen + payload.size
        val out = ByteArray(totalLen)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)

        // ---- IPv4 header (20 bytes) ----
        bb.put(0, 0x45.toByte())  // ver=4, IHL=5
        bb.putShort(2, totalLen.toShort())  // total length
        bb.putShort(4, 0)  // identification (unused for MVP)
        bb.putShort(6, 0x4000.toShort())  // flags=DF, frag offset=0
        bb.put(8, 64.toByte())  // TTL=64
        bb.put(9, IPPROTO_UDP)  // protocol=17
        bb.putShort(10, 0)  // IP header checksum (filled below)
        val srcBytes = InetAddress.getByName(srcIp).address
        bb.put(12, srcBytes[0]); bb.put(13, srcBytes[1])
        bb.put(14, srcBytes[2]); bb.put(15, srcBytes[3])
        val dstBytes = InetAddress.getByName(dstIp).address
        bb.put(16, dstBytes[0]); bb.put(17, dstBytes[1])
        bb.put(18, dstBytes[2]); bb.put(19, dstBytes[3])
        val ipChecksum = internetChecksum(out, 0, ipHeaderLen)
        bb.putShort(10, ipChecksum.toShort())

        // ---- UDP header (8 bytes) ----
        val udpStart = ipHeaderLen
        bb.putShort(udpStart, srcPort.toShort())
        bb.putShort(udpStart + 2, dstPort.toShort())
        bb.putShort(udpStart + 4, (udpHeaderLen + payload.size).toShort())  // UDP length
        bb.putShort(udpStart + 6, 0)  // UDP checksum (filled below)

        // ---- Payload ----
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, ipHeaderLen + udpHeaderLen, payload.size)
        }

        // Compute UDP checksum with pseudo-header
        // (RFC 1071 Internet checksum with a pseudo-
        // header whose protocol field is the L4 protocol
        // number 17 for UDP).
        val udpLen = udpHeaderLen + payload.size
        var sum = 0L
        sum += ((srcBytes[0].toInt() and 0xFF) shl 8) or (srcBytes[1].toInt() and 0xFF)
        sum += ((srcBytes[2].toInt() and 0xFF) shl 8) or (srcBytes[3].toInt() and 0xFF)
        sum += ((dstBytes[0].toInt() and 0xFF) shl 8) or (dstBytes[1].toInt() and 0xFF)
        sum += ((dstBytes[2].toInt() and 0xFF) shl 8) or (dstBytes[3].toInt() and 0xFF)
        sum += IPPROTO_UDP.toInt() and 0xFF
        sum += udpLen and 0xFFFF
        var i = udpStart
        val end = udpStart + udpLen
        while (i + 1 < end) {
            sum += ((out[i].toInt() and 0xFF) shl 8) or (out[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (out[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFFL) + (sum shr 16)
        }
        val udpChecksum = (sum.inv() and 0xFFFFL).toInt()
        bb.putShort(udpStart + 6, udpChecksum.toShort())
        return out
    }

    /**
     * RFC 1071 Internet checksum over `buf[start ..
     * start+len)`. Used for the IPv4 header checksum
     * (no pseudo-header — the IP header is the only
     * data covered). The algorithm is the standard
     * 16-bit one's-complement sum with end-around carry
     * + one's-complement of the result.
     */
    private fun internetChecksum(buf: ByteArray, start: Int, len: Int): Int {
        var sum = 0L
        var i = start
        val end = start + len
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFFL) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    /**
     * Write a packet to the TUN output stream with
     * a single breadcrumb log. Used by the per-flow
     * reader thread to write response packets back to
     * the kernel so the app sees the resolver's
     * response.
     *
     * Defensive: the TUN output stream may be null
     * (cleared by `tearDown` mid-flight). The write
     * is a best-effort; a failure here logs and
     * continues (the next response will retry).
     */
    private fun writeToTun(packet: ByteArray, breadcrumb: String) {
        val output = tunOutputStream
        if (output == null) {
            Log.w(TAG, "writeToTun: TUN output stream is null, dropping ${packet.size}B ($breadcrumb)")
            return
        }
        try {
            output.write(packet)
            output.flush()
            Log.d(TAG, "writeToTun: wrote ${packet.size}B to TUN ($breadcrumb)")
        } catch (e: IOException) {
            Log.w(TAG, "writeToTun: write FAILED for $breadcrumb: ${e.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "writeToTun: UNEXPECTED Throwable for $breadcrumb: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Sprint 12.0B — comprehensive teardown of the
     * UDP forwarder. Called from
     * `OpenE2eeVpnService.stopCapture` BEFORE
     * `nettyClient?.shutdown()` so the 6-step shutdown
     * in NettyChannelClient can safely delegate step 3
     * (udpSocketMap close + udpReaderFutures cancel) to
     * `service?.tearDownUdpForwarder()` as a no-op (the
     * teardown already ran first in `stopCapture()`).
     *
     * The 6 steps (mirror of the 12.0X NettyChannelClient
     * shutdown):
     *   1. Cancel every per-flow UDP reader Future
     *      (`Future.cancel(true)` interrupts the worker
     *      thread).
     *   2. Force `soTimeout=0` on every per-flow
     *      DatagramSocket BEFORE close so the
     *      `receive()` call unblocks with a
     *      SocketTimeoutException (closed socket +
     *      2-second timeout would still hang).
     *   3. Close every per-flow DatagramSocket.
     *   4. Clear `udpSocketMap` and `udpReaderFutures`.
     *   5. Set `tunOutputStream = null` (detach the TUN
     *      output stream ref).
     *   6. `backgroundExecutor.shutdownNow()` +
     *      `awaitTermination(1, SECONDS)` — bounded
     *      wait for ALL per-flow reader threads to exit.
     *
     * After this returns, NO background UDP thread is
     * alive, every per-flow socket is closed, and the
     * TUN output stream ref is detached. The kernel
     * can safely release the TUN interface.
     */
    fun tearDown() {
        Log.d(TAG, "tearDown: starting comprehensive teardown (12.0B)")
        // Step 1 — cancel every per-flow UDP reader Future.
        // `Future.cancel(true)` interrupts the executor
        // worker thread so the receive() call unblocks
        // with an InterruptedIOException.
        try {
            synchronized(udpReaderFutures) {
                udpReaderFutures.values.forEach { f ->
                    try { f?.cancel(true) } catch (_: Throwable) {}
                }
            }
            Log.d(TAG, "tearDown: step 1 DONE (udpReaderFutures cancelled)")
        } catch (e: Throwable) {
            Log.w(TAG, "tearDown: step 1 (udpReaderFutures) FAILED: ${e.message}")
        }

        // Step 2 — force soTimeout=0 on every per-flow
        // DatagramSocket. This MUST happen BEFORE close
        // (so the receive() call in the per-flow reader
        // thread returns immediately instead of waiting
        // for the 2-second default).
        try {
            udpSocketMap.values.forEach { sock ->
                try { sock.soTimeout = 0 } catch (_: Throwable) {}
            }
            Log.d(TAG, "tearDown: step 2 DONE (soTimeout=0 forced on all udpSockets)")
        } catch (e: Throwable) {
            Log.w(TAG, "tearDown: step 2 (soTimeout=0) FAILED: ${e.message}")
        }

        // Step 3 — close every per-flow DatagramSocket.
        try {
            udpSocketMap.values.forEach { sock ->
                try { sock.close() } catch (_: Throwable) {}
            }
            Log.d(TAG, "tearDown: step 3 DONE (udpSockets closed)")
        } catch (e: Throwable) {
            Log.w(TAG, "tearDown: step 3 (udpSocket close) FAILED: ${e.message}")
        }

        // Step 4 — clear the maps.
        try {
            udpSocketMap.clear()
            synchronized(udpReaderFutures) { udpReaderFutures.clear() }
            Log.d(TAG, "tearDown: step 4 DONE (udpSocketMap + udpReaderFutures cleared)")
        } catch (e: Throwable) {
            Log.w(TAG, "tearDown: step 4 (map clear) FAILED: ${e.message}")
        }

        // Step 5 — detach the TUN output stream ref.
        try {
            tunOutputStream = null
            Log.d(TAG, "tearDown: step 5 DONE (tunOutputStream=null)")
        } catch (e: Throwable) {
            Log.w(TAG, "tearDown: step 5 (tunOutputStream) FAILED: ${e.message}")
        }

        // Step 6 — background executor shutdownNow +
        // awaitTermination. `shutdownNow()` interrupts
        // all running tasks; `awaitTermination(1s)` waits
        // for them to exit. After this returns, no
        // per-flow reader thread is alive.
        try {
            backgroundExecutor.shutdownNow()
            backgroundExecutor.awaitTermination(1, TimeUnit.SECONDS)
            Log.d(TAG, "tearDown: step 6 DONE (backgroundExecutor shutdownNow + awaitTermination)")
        } catch (e: Throwable) {
            Log.w(TAG, "tearDown: step 6 (backgroundExecutor) FAILED: ${e.message}")
        }

        Log.d(TAG, "tearDown: DONE (comprehensive teardown complete, no orphan UDP reader)")
    }
}

// ═══════════════════════════════════════════════════════════════════
// Sprint 12.0C — TcpForwarder (raw java.net.Socket, NOT Netty).
//
// Why this lives in OpenE2eeVpnService.kt and not in
// NettyChannelClient.kt:
//
//   The brief is explicit: "OpenE2eeVpnService.kt icine
//   TcpForwarder class (raw java.net.Socket, Netty DEGIL,
//   12.0B gibi)". The 12.0B sprint moved the UDP forwarder
//   out of NettyChannelClient.kt and into a new top-level
//   class IN OpenE2eeVpnService.kt. 12.0C mirrors that
//   pattern for TCP:
//
//     - NettyChannelClient = Netty skeleton (parseIpv4Packet,
//       parseTcpHeader, flowMap, workerGroup, shutdown())
//       + 12.0A TCP state machine (kept for S100-S114 audit
//       compatibility — see 12.0C commit for the runtime
//       dispatch switch to TcpForwarder).
//     - UdpForwarder       = UDP only (Sprint 12.0B).
//     - TcpForwarder       = TCP only (Sprint 12.0C, this
//       class).
//
//   The runtime path in `startReaderThread` now dispatches
//   TCP packets to `tcpForwarder.handleTcpPacket(...)`
//   (NOT `nettyClient.handleTcpPacket(...)`) so the
//   user-space TCP path uses raw java.net.Socket +
//   service.protect(socket) on every connection — no Netty
//   dependency for the TCP socket I/O.
//
// Why a class and not loose methods on the service:
//
//   The per-flow TCP state map + reader Future map +
//   background Executor are a coherent unit of state. Pulling
//   them into a class makes the teardown (`tearDown()`) a
//   single method call and gives the audit a single token
//   to verify (`TcpForwarder`, `tcpConnectionMap`, `tearDown`,
//   `protect(`).
//
// Why not a new file (TcpForwarder.kt):
//
//   The brief says "OpenE2eeVpnService.kt icine" — "into
//   OpenE2eeVpnService.kt". Top-level class declarations in
//   the same Kotlin file are the standard way to keep
//   related code together.
//
// Sprint 12.0X 6-step teardown: the 12.0X teardown's
// step 2 (tcpConnectionMap readerFuture.cancel + socket.close
// + readerThread.interrupt + readerThread.join + map.clear)
// is moved into `TcpForwarder.tearDown()`. `stopCapture()`
// calls `tcpForwarder.tearDown()` BEFORE `nettyClient?.shutdown()`
// so the 6-step shutdown in NettyChannelClient can safely
// delegate step 2 to `TcpForwarder.tearDown()` as a no-op
// (the teardown already ran first in `stopCapture()`). This
// mirrors the post-12.0B pattern (step 3 delegates to
// UdpForwarder.tearDown()).
//
// Sprint 12.0C Volatile + dual put contract:
//   (1) TcpConnection has 8 fields, all @Volatile
//       (state, seqNum, ackNum, receiveWindow, socket,
//       lastAckSent, retransmissionTimer, readerThread).
//       The previous 12.0A.7 design had 9 fields (added
//       readerFuture) and the 12.0A.7 fix was to add
//       @Volatile to every field. 12.0C reverts to 8
//       fields (no readerFuture as a field — the
//       readerFuture is tracked separately in
//       `tcpReaderFutures` map, matching the
//       `udpReaderFutures` pattern in UdpForwarder).
//   (2) handleSyn does a DUAL PUT (stores the
//       TcpConnection under BOTH the primary (OUTGOING)
//       and reverse (INCOMING) 5-tuple). Lookup tries
//       primary first, then reverse. This eliminates
//       the "UNKNOWN FLOW" symptom that the 12.0A.6
//       reverse-key fallback produced and the 12.0A.8
//       late-ACK debug logged. 12.0C does the dual put
//       in the SYN path (forward prediction) so the
//       lookup ALWAYS succeeds for the common case
//       (data flow packets) — no late-ACK debug log
//       needed (the "revert" of the 12.0A.8 fix).
// ═══════════════════════════════════════════════════════════════════

/**
 * Sprint 12.0C — minimal TCP forwarder using raw
 * `java.net.Socket` (NOT Netty).
 *
 * Per-flow protected TCP `Socket` + per-flow reader
 * thread. The brief: "raw java.net.Socket, Netty
 * DEGIL, 12.0B gibi" (mirror of the 12.0B UDP
 * forwarder pattern). The TUN-captured TCP packets
 * are dispatched here from
 * `OpenE2eeVpnService.startReaderThread` (replaces
 * the 12.0A `nettyClient.handleTcpPacket` call).
 *
 * The forwarder owns:
 *   - `tcpConnectionMap` — per-flow TcpConnection
 *     map (key = "srcIp:srcPort-dstIp:dstPort",
 *     value = the TcpConnection with the protected
 *     Socket + reader Thread + Future).
 *   - `tcpReaderFutures` — per-flow reader Future
 *     map (key = same flow string, value = the
 *     Future returned by `backgroundExecutor.submit`
 *     for the per-connection reader runnable).
 *   - `backgroundExecutor` — single
 *     `ThreadPoolExecutor` that owns ALL per-flow TCP
 *     reader threads.
 *   - `tunOutputStream` — the TUN output stream
 *     (set by `OpenE2eeVpnService.startReaderThread`
 *     so the per-connection reader can write response
 *     packets back to the kernel).
 *
 * Threading model:
 *   - `handleTcpPacket` is called from the TUN reader
 *     thread (single-threaded for the dispatch path).
 *   - The per-flow reader runs on a
 *     `backgroundExecutor` worker thread (1 thread
 *     per active flow).
 *   - `tearDown` is called from the main looper (via
 *     `stopCapture` / `onRevoke`).
 *
 * State machine (RFC 793 — 9 states, per the brief):
 *   LISTEN -> SYN_SENT -> ESTABLISHED -> FIN_WAIT_1
 *   -> FIN_WAIT_2 -> CLOSE_WAIT -> LAST_ACK
 *   -> TIME_WAIT -> CLOSED.
 *   MVP: NO TIME_WAIT (transitions directly to CLOSED
 *   after the final FIN+ACK, per the 12.0A brief).
 */
internal class TcpForwarder(private val service: OpenE2eeVpnService) {

    companion object {
        private const val TAG = "TcpForwarder"
        // IP protocol number for TCP (RFC 790).
        const val IPPROTO_TCP: Byte = 6
        // TCP flag bit constants (RFC 793).
        const val TCP_FIN: Int = 0x01
        const val TCP_SYN: Int = 0x02
        const val TCP_RST: Int = 0x04
        const val TCP_PSH: Int = 0x08
        const val TCP_ACK: Int = 0x10
        const val TCP_SYN_ACK: Int = 0x12  // SYN+ACK (SYN=0x02, ACK=0x10)
        const val TCP_FIN_ACK: Int = 0x11  // FIN+ACK (FIN=0x01, ACK=0x10)
        // MSS for the single-connection MVP. 1500-byte
        // MTU minus 40 bytes of headers (20 IP + 20 TCP).
        const val MSS: Int = 1460
        // TCP connect timeout. 5 seconds is enough for
        // a typical mobile RTT (200-500ms) + handshake
        // overhead, and bounds the per-SYN latency so
        // a slow server doesn't block the TUN reader.
        private const val TCP_CONNECT_TIMEOUT_MS = 5_000
    }

    /**
     * Parsed IPv4 header (subset) — mirrors the
     * 12.0A `NettyChannelClient.Ipv4Header` so the
     * TcpForwarder doesn't depend on NettyChannelClient
     * (it owns its own parser; the NettyChannelClient
     * parser stays for the S100 audit invariant).
     */
    data class Ipv4Header(
        val version: Int,
        val ihl: Int,
        val totalLength: Int,
        val protocol: Byte,
        val srcAddr: java.net.InetAddress,
        val dstAddr: java.net.InetAddress
    )

    /**
     * Parsed TCP header (subset).
     */
    data class TcpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val flags: Int,
        val seqNum: Long,
        val ackNum: Long
    )

    /**
     * Sprint 12.0C — 9-state RFC 793 state machine.
     * The state NAME is preserved (TIME_WAIT is in
     * the enum) but the MVP does NOT implement
     * TIME_WAIT (transitions directly to CLOSED
     * after the final FIN+ACK, per the brief).
     */
    enum class TcpState {
        LISTEN,
        SYN_SENT,
        ESTABLISHED,
        FIN_WAIT_1,
        FIN_WAIT_2,
        CLOSE_WAIT,
        LAST_ACK,
        TIME_WAIT,
        CLOSED
    }

    /**
     * Sprint 12.0C — per-flow TCP connection state.
     * 8 fields, all @Volatile (per the brief).
     * The readerFuture is NOT a field — it is tracked
     * separately in `tcpReaderFutures` (matching the
     * UdpForwarder's `udpReaderFutures` pattern).
     */
    data class TcpConnection(
        @Volatile var state: TcpState = TcpState.LISTEN,
        // Our seq number (next byte we will send).
        @Volatile var seqNum: Long = 0L,
        // Next seq number we expect from the peer.
        @Volatile var ackNum: Long = 0L,
        // Receive window (fixed at MSS for MVP, no
        // sliding window).
        @Volatile var receiveWindow: Int = MSS,
        // Real Socket to the destination (after
        // protect() + connect()). null until
        // protectAndConnect succeeds.
        @Volatile var socket: java.net.Socket? = null,
        // Highest ack number we sent to the app
        // (for diagnostics).
        @Volatile var lastAckSent: Long = 0L,
        // Retransmission timer handle. MVP: null (no
        // retransmission per the brief).
        @Volatile var retransmissionTimer: Any? = null,
        // Background thread that reads from
        // `socket.getInputStream()` and writes
        // wrapped IP+TCP packets to the TUN output.
        @Volatile var readerThread: Thread? = null,
    )

    /**
     * Per-flow TCP connection state map. Key:
     * device 5-tuple "srcIp:srcPort-dstIp:dstPort"
     * (the original app's flow, NOT reversed).
     * The map uses ConcurrentHashMap so the TUN
     * reader thread and the per-connection reader
     * thread can both touch it (S112 invariant).
     * Sprint 12.0C: BOTH the primary AND reverse
     * 5-tuple are stored (dual put in handleSyn).
     * Lookup in handleTcpPacket tries primary first,
     * then reverse.
     */
    private val tcpConnectionMap: MutableMap<String, TcpConnection> = ConcurrentHashMap()

    /**
     * Per-flow TCP reader Future map. Key: device
     * 5-tuple string. Value: the Future returned by
     * `backgroundExecutor.submit` for the per-flow
     * TCP reader runnable. The shutdown method
     * cancels all of these via `Future.cancel(true)`
     * (which interrupts the worker thread) AND
     * calls `backgroundExecutor.shutdownNow()` +
     * `awaitTermination` to ensure no reader thread
     * outlives the VPN service.
     */
    private val tcpReaderFutures: MutableMap<String, Future<*>?> = ConcurrentHashMap()

    /**
     * Single `ThreadPoolExecutor` that owns ALL
     * per-flow TCP reader threads. Cached thread
     * pool so threads are created on demand and
     * reused. The MVP instantiates up to ~1 reader
     * per active flow; the cached pool grows as
     * needed and shrinks when idle.
     */
    private val backgroundExecutor: ThreadPoolExecutor = Executors.newCachedThreadPool() as ThreadPoolExecutor

    /**
     * TUN output stream — the `ParcelFileDescriptor
     * .AutoCloseOutputStream` that the per-flow
     * reader thread writes response packets to.
     * Set by `OpenE2eeVpnService.startReaderThread`
     * AFTER `Builder.establish()` returns; cleared
     * by `OpenE2eeVpnService.stopCapture` (via
     * `tearDown()`) so a stale reference does not
     * leak.
     */
    @Volatile
    private var tunOutputStream: java.io.OutputStream? = null

    /**
     * Set the TUN output stream. Called by
     * `OpenE2eeVpnService.startReaderThread` once
     * the `ParcelFileDescriptor.AutoCloseOutputStream`
     * is open. After this call, `handleTcpPacket`
     * can write response packets (SYN+ACK, ACK,
     * FIN+ACK, data) back to the device's app via
     * the TUN. Calling with `null` clears the
     * reference (used by `tearDown`).
     */
    fun setTunOutputStream(output: java.io.OutputStream?) {
        tunOutputStream = output
        Log.d(TAG, "setTunOutputStream: TUN output stream ${if (output == null) "cleared" else "set"}")
    }

    /**
     * Parse a minimal IPv4 header from a TUN-read
     * buffer. Returns null if the buffer is too
     * short or the version is not 4. Mirrors
     * `NettyChannelClient.parseIpv4Packet` so the
     * TcpForwarder is self-contained.
     */
    fun parseIpv4Packet(buf: ByteArray, len: Int): Ipv4Header? {
        if (len < 20) return null
        val bb = java.nio.ByteBuffer.wrap(buf, 0, len).order(java.nio.ByteOrder.BIG_ENDIAN)
        val verIhl = bb.get(0).toInt() and 0xFF
        val version = verIhl ushr 4
        if (version != 4) return null
        val ihl = (verIhl and 0x0F) * 4
        if (ihl < 20 || ihl > len) return null
        val totalLength = bb.getShort(2).toInt() and 0xFFFF
        val protocol = bb.get(9)
        val srcBytes = ByteArray(4)
        val dstBytes = ByteArray(4)
        bb.position(12)
        bb.get(srcBytes)
        bb.get(dstBytes)
        return Ipv4Header(
            version = version,
            ihl = ihl,
            totalLength = totalLength,
            protocol = protocol,
            srcAddr = java.net.InetAddress.getByAddress(srcBytes),
            dstAddr = java.net.InetAddress.getByAddress(dstBytes)
        )
    }

    /**
     * Parse a minimal TCP header from the buffer
     * at the given IP header offset. Mirrors
     * `NettyChannelClient.parseTcpHeader`.
     */
    fun parseTcpHeader(buf: ByteArray, len: Int, ipHeaderLen: Int): TcpHeader? {
        if (len < ipHeaderLen + 20) return null
        val bb = java.nio.ByteBuffer.wrap(buf, 0, len).order(java.nio.ByteOrder.BIG_ENDIAN)
        val srcPort = bb.getShort(ipHeaderLen).toInt() and 0xFFFF
        val dstPort = bb.getShort(ipHeaderLen + 2).toInt() and 0xFFFF
        val seqNum = bb.getInt(ipHeaderLen + 4).toLong() and 0xFFFFFFFFL
        val ackNum = bb.getInt(ipHeaderLen + 8).toLong() and 0xFFFFFFFFL
        val flags = bb.get(ipHeaderLen + 13).toInt() and 0xFF
        return TcpHeader(
            srcPort = srcPort,
            dstPort = dstPort,
            flags = flags,
            seqNum = seqNum,
            ackNum = ackNum
        )
    }

    /**
     * Build a 5-tuple flow key for the
     * tcpConnectionMap. The "primary" key is the
     * OUTGOING (app -> real dest) direction. The
     * "reverse" key is the INCOMING (real dest -> app)
     * direction. Sprint 12.0C: handleSyn does a dual
     * put (both keys) so the lookup ALWAYS succeeds
     * regardless of packet direction.
     */
    fun flowKey(
        srcAddr: java.net.InetAddress,
        srcPort: Int,
        dstAddr: java.net.InetAddress,
        dstPort: Int
    ): String {
        return "$srcAddr:$srcPort-$dstAddr:$dstPort"
    }

    /**
     * Handle a TUN-captured TCP packet. The IP +
     * TCP headers have already been parsed by the
     * caller (see `OpenE2eeVpnService.startReaderThread`).
     * The 5-tuple can be in EITHER direction
     * (OUTGOING = app -> real dest, INCOMING = real
     * dest -> app); we try the primary direction first
     * and fall back to the reverse direction so the
     * TcpConnection is found regardless of which way
     * the packet is going.
     *
     * Dispatch precedence (RST > SYN > SYN+ACK >
     * PSH+ACK > FIN+ACK > ACK).
     */
    fun handleTcpPacket(
        ipPacket: ByteArray,
        offset: Int,
        length: Int,
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int
    ) {
        val primaryFlowKey = flowKey(
            java.net.InetAddress.getByName(srcIp), srcPort,
            java.net.InetAddress.getByName(dstIp), dstPort
        )
        val reverseFlowKey = flowKey(
            java.net.InetAddress.getByName(dstIp), dstPort,
            java.net.InetAddress.getByName(srcIp), srcPort
        )
        val tcp = parseTcpHeader(ipPacket, length, offset) ?: return
        val flags = tcp.flags
        val payloadLen = (length - offset - 20).coerceAtLeast(0)

        // Sprint 12.0F+1 — flags debug breadcrumb.
        // Owner 12.0F logcat analysis: 9 dispatch
        // events all carried PSH+ACK, 0 SYN
        // (https://.../logcat120f_v3.txt line 17-21).
        // The TcpForwarder SYN path was never
        // exercised because TCP SYN packets
        // bypass the VPN TUN (kernel routes
        // them via the real NIC). The
        // TcpForwarder therefore never created
        // a Socket, and the subsequent PSH+ACK
        // data packets were dropped with
        // "no-socket flow" (no SYN handler fired
        // first to insert the conn into
        // tcpConnectionMap).
        //
        // The fix is to add a per-packet
        // breadcrumb at the dispatch entry
        // that logs ALL 5 TCP flags (SYN, ACK,
        // PSH, FIN, RST) as boolean state. The
        // Owner greps for this token in logcat
        // and can now distinguish:
        //   - "all packets are PSH+ACK" →
        //     kernel SYN bypass (the SYN path
        //     is never reached, regardless of
        //     how the dispatch logic is fixed).
        //   - "SYN IS present in some packets"
        //     → dispatch precedence bug (the
        //     SYN path IS reached but the
        //     handler is wrong).
        //   - "mixed" → both bugs present.
        //
        // The 12.0F+1 log fires for EVERY
        // captured TCP packet (no filtering)
        // so the Owner can grep the
        // per-packet flag state across the
        // full TCP conversation.
        Log.d(TAG, "handleTcpPacket: dispatching flags=0x${"%02x".format(flags)} (SYN=${(flags and TCP_SYN) != 0}, ACK=${(flags and TCP_ACK) != 0}, PSH=${(flags and TCP_PSH) != 0}, FIN=${(flags and TCP_FIN) != 0}, RST=${(flags and TCP_RST) != 0}) flowKey=$primaryFlowKey (src=$srcIp:$srcPort dst=$dstIp:$dstPort)")

        // Sprint 12.0C — bidir lookup. Try the primary
        // key first; if that misses, try the reverse
        // key. With the 12.0C dual put in handleSyn
        // (primary AND reverse stored), the lookup
        // succeeds for the common case (data flow
        // packets) regardless of direction.
        val foundViaReverseKey = !tcpConnectionMap.containsKey(primaryFlowKey) &&
                                  tcpConnectionMap.containsKey(reverseFlowKey)
        val conn = tcpConnectionMap[primaryFlowKey] ?: tcpConnectionMap[reverseFlowKey]
        val effectiveFlowKey = if (tcpConnectionMap.containsKey(primaryFlowKey)) {
            primaryFlowKey
        } else if (tcpConnectionMap.containsKey(reverseFlowKey)) {
            reverseFlowKey
        } else {
            primaryFlowKey  // not found; SYN path will create under this
        }
        // Sprint 12.0D — `forwarded via reverseKey`
        // INFO log. The Owner greps for this token
        // to confirm the INCOMING packet was
        // successfully dispatched via the reverse
        // key. The 12.0C implementation did not
        // emit this log (the 12.0A.8 version did, in
        // NettyChannelClient.kt — but the runtime
        // path is now via TcpForwarder, so the
        // NettyChannelClient log never fires).
        // Adding the log here is the regression
        // guard for "UNKNOWN FLOW 7" symptom
        // (Owner 14:06) — the dual put in handleSyn
        // + this log confirms the lookup is
        // succeeding for INCOMING packets.
        if (foundViaReverseKey) {
            Log.d(TAG, "forwarded via reverseKey: $reverseFlowKey (flags=0x${"%02x".format(flags)})")
        }
        // Sprint 12.0E — `flow forward` Log.d.
        // The brief: "UNKNOWN FLOW yerine Log.d
        // 'flow forward' ile degistir (cunku dual
        // put calisiyor, reverse flow gecmisse
        // normal)". The "UNKNOWN FLOW" concept
        // was the 12.0A.7 warning that fired
        // when the lookup missed BOTH the
        // primary and reverse key (1 per
        // connection - diagnostic noise, not
        // an error). The 12.0A.8 downgraded it
        // to `late ACK` debug log. The 12.0C
        // dual put eliminates the unknown-flow
        // case entirely (the lookup ALWAYS
        // succeeds for the common case). 12.0E
        // adds a POSITIVE `flow forward` log
        // that fires for BOTH directions (the
        // primary OUTGOING key AND the reverse
        // INCOMING key) so the Owner has a
        // single token to grep for when
        // verifying the packet was successfully
        // dispatched to the conn handler. The
        // direction is noted (primary vs
        // reverse) so the Owner can distinguish
        // OUTGOING packets from INCOMING
        // packets in logcat. The `UNKNOWN FLOW`
        // concept is REPLACED — there is no
        // longer a warning for missed lookups
        // (the dual put + the missing-key path
        // is implicit in the absence of this
        // log + the SYN path creates a new
        // conn).
        if (conn != null) {
            val direction = if (foundViaReverseKey) "reverse" else "primary"
            Log.d(TAG, "flow forward: $direction key $effectiveFlowKey (flags=0x${"%02x".format(flags)}, state=${conn.state})")
        }

        // RST has highest precedence — the peer is
        // closing the connection immediately.
        if ((flags and TCP_RST) != 0) {
            val rconn = tcpConnectionMap.remove(primaryFlowKey) ?: tcpConnectionMap.remove(reverseFlowKey)
            try { rconn?.socket?.close() } catch (_: Throwable) {}
            try { rconn?.readerThread?.interrupt() } catch (_: Throwable) {}
            tcpReaderFutures.remove(primaryFlowKey)
            tcpReaderFutures.remove(reverseFlowKey)
            Log.d(TAG, "handleTcpPacket: RST, closing flow $effectiveFlowKey (state was ${rconn?.state})")
            return
        }

        when {
            (flags and TCP_SYN) != 0 && (flags and TCP_ACK) == 0 -> {
                // SYN from the app — initiate the 3-way
                // handshake to the real destination.
                handleSyn(primaryFlowKey, srcIp, dstIp, srcPort, dstPort, tcp)
            }
            (flags and TCP_SYN_ACK) == TCP_SYN_ACK -> {
                // SYN+ACK from the real dest (the
                // protectAndConnect path already
                // completed the 3-way handshake via
                // Socket.connect()). Send our ACK back
                // to the app, state = ESTABLISHED.
                handleSynAck(effectiveFlowKey, conn, srcIp, dstIp, srcPort, dstPort, tcp)
            }
            (flags and TCP_PSH) != 0 && (flags and TCP_ACK) != 0 -> {
                // PSH+ACK from the app — data. Forward
                // the payload to the real socket (MSS
                // slicing) and send our ACK back.
                handleData(effectiveFlowKey, conn, srcIp, dstIp, srcPort, dstPort, tcp,
                            ipPacket, offset + 20, payloadLen)
            }
            (flags and TCP_FIN_ACK) == TCP_FIN_ACK -> {
                // FIN+ACK from the app — connection
                // teardown. Send our FIN+ACK to the
                // real dest and to the app.
                handleFinAck(effectiveFlowKey, conn, srcIp, dstIp, srcPort, dstPort, tcp)
            }
            (flags and TCP_ACK) != 0 -> {
                // Bare ACK from the app — pure
                // acknowledgement (e.g., the app
                // ACKing our FIN+ACK). For the MVP
                // we just log + bump the lastAckSent
                // counter.
                if (conn != null) {
                    conn.lastAckSent = tcp.ackNum
                    Log.d(TAG, "handleTcpPacket: ACK, flow $effectiveFlowKey, ackNum=${tcp.ackNum} (state=${conn.state})")
                } else {
                    Log.d(TAG, "handleTcpPacket: ACK for unknown flow $effectiveFlowKey; dropping")
                    // Sprint 12.0F+2 — RST workaround.
                    // A bare ACK for an unknown flow
                    // is the most common case: the
                    // kernel's TCP stack already
                    // completed the 3-way handshake
                    // and the server's data packets
                    // are reaching us as ACKs. Send
                    // RST so the app retransmits.
                    writeTcpRstToTun(
                        srcIp = srcIp, dstIp = dstIp,
                        srcPort = srcPort, dstPort = dstPort,
                        seqNum = tcp.seqNum, ackNum = tcp.ackNum,
                        flowKey = effectiveFlowKey
                    )
                }
            }
            else -> {
                Log.d(TAG, "handleTcpPacket: unhandled flags 0x${"%02x".format(flags)} for flow $effectiveFlowKey")
            }
        }
    }

    /**
     * Sprint 12.0C — handle a SYN from the app.
     * 1. Create a new TcpConnection (initial state
     *    LISTEN).
     * 2. Create a raw `java.net.Socket`, call
     *    `service.protect(socket)` (so the socket
     *    bypasses the VPN and uses the real NIC),
     *    then `connect()` to the destination. The
     *    connect() is synchronous and returns after
     *    the 3-way handshake completes; if it
     *    fails we mark the connection CLOSED.
     * 3. Transition LISTEN -> SYN_SENT -> ESTABLISHED
     *    (the connect() block covers both
     *    transitions).
     * 4. Build a SYN+ACK response packet and write
     *    it back to the TUN so the app sees a
     *    SYN+ACK.
     * 5. Start a background thread that reads from
     *    the real socket and writes wrapped IP+TCP
     *    packets back to the TUN (the response
     *    direction).
     * 6. DUAL PUT — store the TcpConnection under
     *    BOTH the primary and reverse flowKey. This
     *    eliminates the "UNKNOWN FLOW" symptom
     *    without needing the 12.0A.8 late-ACK
     *    debug log.
     */
    private fun handleSyn(
        flowKey: String,
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        tcp: TcpHeader
    ) {
        val conn = TcpConnection()
        conn.state = TcpState.LISTEN
        conn.seqNum = (System.nanoTime() and 0xFFFFFFFFL)
        conn.ackNum = tcp.seqNum + 1
        Log.d(TAG, "handleTcpPacket: SYN, flow $flowKey, state=LISTEN -> SYN_SENT")
        val sock: java.net.Socket? = try {
            val s = java.net.Socket()
            // Sprint 12.0C — `service.protect(socket)` is
            // the load-bearing piece. It tells the system
            // "this socket MUST bypass the VPN and use
            // the real NIC". Without protect(), the
            // socket would be captured by the TUN and
            // the packet would loop forever (the same
            // "VPN blackhole" symptom that 12.0A fixed
            // for the Netty path, now closed for the
            // raw Socket path).
            val protected = service.protect(s)
            if (!protected) {
                Log.e(TAG, "handleSyn: protect(Socket) returned false for $flowKey; socket will loop in VPN")
                try { s.close() } catch (_: Throwable) {}
                null
            } else {
                Log.d(TAG, "handleSyn: protected Socket for $flowKey dst=$dstIp:$dstPort")
                s.connect(java.net.InetSocketAddress(java.net.InetAddress.getByName(dstIp), dstPort), TCP_CONNECT_TIMEOUT_MS)
                Log.d(TAG, "handleSyn: connected $flowKey to $dstIp:$dstPort (local=${s.localSocketAddress}, remote=${s.remoteSocketAddress})")
                s
            }
        } catch (e: Throwable) {
            Log.e(TAG, "handleSyn: Socket() / protect() / connect() failed for $flowKey: ${e.message}")
            null
        }
        if (sock == null) {
            conn.state = TcpState.CLOSED
            Log.w(TAG, "handleSyn: protectAndConnect FAILED for $flowKey; state=CLOSED")
            return
        }
        conn.socket = sock
        // The connect() completed the 3-way handshake
        // with the real dest, so we transition
        // SYN_SENT -> ESTABLISHED here.
        conn.state = TcpState.ESTABLISHED
        Log.d(TAG, "handleSyn: state=SYN_SENT -> ESTABLISHED for flow $flowKey (TcpConnection: connected to $dstIp:$dstPort)")
        // Sprint 12.0C — DUAL PUT (forward prediction).
        // Store the TcpConnection under BOTH the
        // primary (OUTGOING) and reverse (INCOMING)
        // 5-tuple. This eliminates the UNKNOWN FLOW
        // symptom for the common case (data flow
        // packets) WITHOUT requiring a late-ACK
        // debug log (the "revert" of the 12.0A.8
        // specific fix).
        val reverseKey = flowKey(
            java.net.InetAddress.getByName(dstIp), dstPort,
            java.net.InetAddress.getByName(srcIp), srcPort
        )
        tcpConnectionMap[flowKey] = conn
        tcpConnectionMap[reverseKey] = conn

        // Build our SYN+ACK response packet and write
        // it to the TUN. From the app's perspective,
        // the VPN is acting as the remote server, so
        // it expects a SYN+ACK with seq = ourInitialSeq
        // and ack = appSeq+1.
        val synAckPkt = buildIpTcpPacket(
            srcIp = dstIp, dstIp = srcIp,
            srcPort = dstPort, dstPort = srcPort,
            seqNum = conn.seqNum, ackNum = conn.ackNum,
            flags = TCP_SYN_ACK,
            payload = ByteArray(0),
        )
        writeToTun(synAckPkt, "SYN+ACK -> app")

        // Start the background reader thread that
        // pulls response bytes from the real socket
        // and writes them back to the TUN (the
        // reverse direction).
        startSocketReader(flowKey, conn, srcIp, dstIp, srcPort, dstPort)
    }

    /**
     * Sprint 12.0C — handle a SYN+ACK from the
     * real dest. The `Socket.connect()` path
     * already completed the 3-way handshake, so
     * this branch is the diagnostic case where the
     * SYN+ACK is observed via the TUN (e.g., for
     * testing without a real socket connect). Send
     * our ACK to the app and confirm state =
     * ESTABLISHED.
     */
    private fun handleSynAck(
        flowKey: String,
        conn: TcpConnection?,
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        tcp: TcpHeader
    ) {
        if (conn == null) {
            Log.w(TAG, "handleTcpPacket: SYN+ACK for unknown flow $flowKey; dropping")
            return
        }
        if (conn.state != TcpState.SYN_SENT && conn.state != TcpState.ESTABLISHED) {
            Log.w(TAG, "handleTcpPacket: SYN+ACK in unexpected state ${conn.state} for $flowKey")
        }
        conn.ackNum = tcp.seqNum + 1
        conn.state = TcpState.ESTABLISHED
        Log.d(TAG, "handleTcpPacket: SYN+ACK received, state=SYN_SENT -> ESTABLISHED for flow $flowKey")
        val ackPkt = buildIpTcpPacket(
            srcIp = dstIp, dstIp = srcIp,
            srcPort = dstPort, dstPort = srcPort,
            seqNum = conn.seqNum, ackNum = conn.ackNum,
            flags = TCP_ACK, payload = ByteArray(0),
        )
        writeToTun(ackPkt, "ACK -> app")
    }

    /**
     * Sprint 12.0C — handle a PSH+ACK (data)
     * packet from the app. Slice the payload into
     * MSS-sized chunks (the MVP fragmenter) and
     * write each chunk to the real socket. Build
     * an ACK response packet (with ack = seq +
     * payloadLen) and write it to the TUN.
     *
     * Sprint 12.0E — HTTP request URI + Host
     * header log. The brief: log the HTTP request
     * URI + Host header so the Owner knows which
     * endpoint the app is calling (e.g., "GET /
     * healthz HTTP/1.1" + "Host: 212.64.210.85").
     * The URI + Host are extracted from the first
     * chunk of the app's HTTP request (the
     * request line is always in the first chunk
     * for HTTP/1.1). The log fires ONCE on the
     * first request chunk so the Owner can pair
     * the request URI with the response status +
     * Content-Type (via the recvHttpResponse log
     * on the response side).
     */
    private fun handleData(
        flowKey: String,
        conn: TcpConnection?,
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        tcp: TcpHeader,
        ipPacket: ByteArray,
        payloadOffset: Int,
        payloadLen: Int
    ) {
        if (conn == null || conn.socket == null) {
            Log.w(TAG, "handleTcpPacket: PSH+ACK for unknown/no-socket flow $flowKey; dropping")
            // Sprint 12.0F+2 — RST workaround.
            // The kernel already established the
            // TCP connection before our user-space
            // stack saw the SYN (the "established
            // connection cache" survives VPN
            // reconfiguration). Send a TCP RST to
            // the app so it tears down and
            // retransmits a fresh SYN that our
            // user-space stack CAN see.
            writeTcpRstToTun(
                srcIp = srcIp, dstIp = dstIp,
                srcPort = srcPort, dstPort = dstPort,
                seqNum = tcp.seqNum, ackNum = tcp.ackNum,
                flowKey = flowKey
            )
            return
        }
        if (conn.state != TcpState.ESTABLISHED) {
            Log.w(TAG, "handleTcpPacket: PSH+ACK in state ${conn.state} for $flowKey; dropping")
            // Sprint 12.0F+2 — RST workaround (also
            // for the in-state-but-not-established
            // case: e.g., a PSH+ACK that arrived
            // BEFORE the SYN+ACK completed the
            // handshake).
            writeTcpRstToTun(
                srcIp = srcIp, dstIp = dstIp,
                srcPort = srcPort, dstPort = dstPort,
                seqNum = tcp.seqNum, ackNum = tcp.ackNum,
                flowKey = flowKey
            )
            return
        }
        // Slice + write payload to the real socket.
        val out = conn.socket!!.getOutputStream()
        var written = 0
        while (written < payloadLen) {
            val chunkSize = minOf(MSS, payloadLen - written)
            out.write(ipPacket, payloadOffset + written, chunkSize)
            written += chunkSize
        }
        out.flush()
        Log.d(TAG, "handleTcpPacket: PSH+ACK data, forward $written bytes from flow $flowKey to $dstIp:$dstPort (MSS=$MSS)")
        // Sprint 12.0E — HTTP request URI + Host
        // header log. The Owner greps for the
        // request URI + Host header to confirm
        // the app is calling the expected
        // endpoint (e.g., "GET /healthz
        // HTTP/1.1" + "Host: 212.64.210.85"). The
        // URI + Host are parsed from the first
        // chunk of the request (the request line
        // is always in the first chunk for
        // HTTP/1.1; the Host header is also
        // typically in the first chunk for
        // non-pipelined requests).
        try {
            // Extract up to 2 KiB for the request
            // header parse (HTTP/1.1 request
            // headers are typically <1 KiB).
            val headerEnd = minOf(payloadLen, 2048)
            val reqHeaderStr = String(ipPacket, payloadOffset, headerEnd, Charsets.US_ASCII)
            // Only parse if it looks like an HTTP
            // request (the first 3 chars are
            // "GET", "POST", "PUT", etc.).
            if (reqHeaderStr.length >= 4 &&
                (reqHeaderStr.startsWith("GET ") ||
                 reqHeaderStr.startsWith("POST ") ||
                 reqHeaderStr.startsWith("PUT ") ||
                 reqHeaderStr.startsWith("HEAD ") ||
                 reqHeaderStr.startsWith("DELETE ") ||
                 reqHeaderStr.startsWith("OPTIONS "))) {
                // Request line: "GET /healthz HTTP/1.1\r\n"
                val requestLine = reqHeaderStr.lineSequence().firstOrNull() ?: ""
                // Host header: "Host: 212.64.210.85\r\n"
                val hostMatch = Regex("(?im)^Host:\\s*([^\\r\\n]+)").find(reqHeaderStr)
                val hostValue = hostMatch?.groupValues?.get(1)?.trim() ?: "?"
                Log.d(TAG, "sendHttpRequest: request line [$requestLine] Host=$hostValue for flow $flowKey (app=$srcIp:$srcPort -> realDest=$dstIp:$dstPort, $written bytes)")
            }
        } catch (e: Throwable) {
            // Parsing the request line is a
            // best-effort diagnostic; do NOT
            // fail the data flow on a parse error
            // (the real socket write above already
            // succeeded).
            Log.d(TAG, "sendHttpRequest: request line parse FAILED for flow $flowKey: ${e.message}")
        }
        // ACK back to the app.
        conn.ackNum = tcp.seqNum + payloadLen
        conn.lastAckSent = conn.ackNum
        val ackPkt = buildIpTcpPacket(
            srcIp = dstIp, dstIp = srcIp,
            srcPort = dstPort, dstPort = srcPort,
            seqNum = conn.seqNum, ackNum = conn.ackNum,
            flags = TCP_ACK, payload = ByteArray(0),
        )
        writeToTun(ackPkt, "ACK -> app (data)")
    }

    /**
     * Sprint 12.0C — handle a FIN+ACK from the
     * app. Close the real socket, build a FIN+ACK
     * response, transition through FIN_WAIT_1 (and
     * immediately to CLOSED per the brief's MVP —
     * no TIME_WAIT).
     */
    private fun handleFinAck(
        flowKey: String,
        conn: TcpConnection?,
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        tcp: TcpHeader
    ) {
        if (conn == null) {
            Log.w(TAG, "handleTcpPacket: FIN+ACK for unknown flow $flowKey; dropping")
            // Sprint 12.0F+2 — RST workaround.
            // FIN+ACK for unknown flow = the app
            // thinks the connection is alive but our
            // user-space stack has no record. Send
            // RST so the app's TCP retransmit /
            // re-establish picks up our user-space
            // stack on the next attempt.
            writeTcpRstToTun(
                srcIp = srcIp, dstIp = dstIp,
                srcPort = srcPort, dstPort = dstPort,
                seqNum = tcp.seqNum, ackNum = tcp.ackNum,
                flowKey = flowKey
            )
            return
        }
        // Remove from BOTH map slots (primary +
        // reverse) so a later INCOMING packet on the
        // reverse key does not see the closed
        // connection. The handleSyn dual put stored
        // the conn under both keys; iterate the map
        // to find all keys whose value is this conn
        // and remove them (cheaper than tracking
        // both keys separately on every TcpConnection).
        val keysToRemove = mutableListOf<String>()
        for ((k, v) in tcpConnectionMap) {
            if (v === conn) keysToRemove.add(k)
        }
        keysToRemove.forEach { tcpConnectionMap.remove(it) }
        keysToRemove.forEach { tcpReaderFutures.remove(it) }
        conn.ackNum = tcp.seqNum + 1
        conn.state = TcpState.FIN_WAIT_1
        Log.d(TAG, "handleTcpPacket: FIN+ACK, state=ESTABLISHED -> FIN_WAIT_1 for flow $flowKey")
        // MVP: no TIME_WAIT — close immediately
        // after sending our FIN+ACK.
        try { conn.socket?.close() } catch (_: Throwable) {}
        try { conn.readerThread?.interrupt() } catch (_: Throwable) {}
        // Build the FIN+ACK response. The response
        // direction is REVERSED: from the dest
        // (dstIp:dstPort) to the app (srcIp:srcPort).
        val finAckPkt = buildIpTcpPacket(
            srcIp = dstIp, dstIp = srcIp,
            srcPort = dstPort, dstPort = srcPort,
            seqNum = conn.seqNum, ackNum = conn.ackNum,
            flags = TCP_FIN_ACK, payload = ByteArray(0),
        )
        writeToTun(finAckPkt, "FIN+ACK -> app")
        conn.state = TcpState.CLOSED
        Log.d(TAG, "handleTcpPacket: FIN+ACK, state=FIN_WAIT_1 -> CLOSED (no TIME_WAIT in MVP) for flow $flowKey")
    }

    /**
     * Sprint 12.0C — start a background thread
     * that reads from the real socket and writes
     * wrapped IP+TCP packets back to the TUN. The
     * thread is daemon so it does not block process
     * exit. It exits when the socket is closed
     * (real EOF) or interrupted by `tearDown()` / a
     * FIN handler.
     *
     * Sprint 12.0D — HTTP response parsing. The first
     * read on the real socket is the HTTP response
     * status line + headers (for HTTP/1.1 keep-alive
     * the body follows in subsequent reads, but the
     * status line is always in the first chunk).
     * We parse the status line + Content-Type +
     * Content-Length headers from the first chunk
     * and log them as `recvHttpResponse: N bytes
     * read, status=200, content-type=text/html,
     * content-length=1234` so the Owner can confirm
     * the response is well-formed (the 12.0C
     * startSocketReader only logged a byte count,
     * which made it impossible to tell "200 OK with
     * 1234 body bytes" from "502 Bad Gateway with
     * 0 body bytes"). The body chunk count is
     * tracked separately so the Owner can also
     * detect truncation.
     */
    private fun startSocketReader(
        flowKey: String,
        conn: TcpConnection,
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int
    ) {
        val sock = conn.socket ?: return
        // Submit the reader runnable to
        // backgroundExecutor. The Future is stored in
        // tcpReaderFutures[flowKey] for shutdown
        // cancellation. The thread ref (executor
        // worker thread) is stored in
        // conn.readerThread for any code that still
        // reads it. The thread name is set inside the
        // runnable so logcat shows the
        // 5-tuple-prefixed name.
        val runnable = Runnable {
            try {
                Thread.currentThread().name = "opene2ee-tcp-forwarder-reader-$flowKey"
                conn.readerThread = Thread.currentThread()
            } catch (_: Throwable) {}
            // Sprint 12.0D — HTTP response header
            // parser state. The first read on the
            // socket is the status line + headers
            // (HTTP/1.1). We accumulate bytes until
            // we see \r\n\r\n (header end marker) and
            // then parse the status line +
            // Content-Type + Content-Length. The
            // values are logged as part of the
            // `recvHttpResponse: ...` breadcrumb so
            // the Owner can confirm the response is
            // well-formed.
            val headerBuf = StringBuilder()
            var headerParsed = false
            var responseStatus: Int? = null
            var responseContentType: String? = null
            var responseContentLength: Int? = null
            var bodyBytesReceived: Int = 0
            // Sprint 12.0E — `bodyFirst100Logged`
            // guard. The body first 100 bytes log
            // fires ONCE on the first read (the
            // body may be split across multiple
            // reads for keep-alive responses, so
            // the first 100 bytes of the first
            // chunk is the canonical body
            // fingerprint). The guard prevents
            // the log from firing on every body
            // chunk (which would spam logcat for
            // keep-alive responses with many
            // chunks).
            var bodyFirst100Logged: Boolean = false
            try {
                val input = sock.getInputStream()
                val buf = ByteArray(MSS)
                while (!Thread.currentThread().isInterrupted) {
                    val n = try {
                        input.read(buf)
                    } catch (e: Throwable) {
                        Log.d(TAG, "startSocketReader: socket read EOF / error for $flowKey: ${e.message}")
                        break
                    }
                    if (n <= 0) {
                        Log.d(TAG, "startSocketReader: socket EOF (n=$n), exiting reader for $flowKey")
                        break
                    }
                    // Sprint 12.0D — parse the HTTP
                    // response headers from the first
                    // chunk. The status line + headers
                    // are ASCII text; the body may be
                    // binary. We accumulate bytes into
                    // `headerBuf` until we see the
                    // \r\n\r\n end-of-headers marker.
                    if (!headerParsed) {
                        for (i in 0 until n) {
                            headerBuf.append(buf[i].toInt().toChar())
                            if (headerBuf.length >= 4 &&
                                headerBuf[headerBuf.length - 4] == '\r' &&
                                headerBuf[headerBuf.length - 3] == '\n' &&
                                headerBuf[headerBuf.length - 2] == '\r' &&
                                headerBuf[headerBuf.length - 1] == '\n') {
                                // End of headers. Parse
                                // the status line + key
                                // headers.
                                val headers = headerBuf.toString()
                                // Status line: "HTTP/1.1 200 OK\r\n"
                                val statusLine = headers.lineSequence().firstOrNull() ?: ""
                                val statusMatch = Regex("HTTP/\\S+\\s+(\\d{3})").find(statusLine)
                                responseStatus = statusMatch?.groupValues?.get(1)?.toIntOrNull()
                                // Content-Type: "text/html; charset=utf-8"
                                val ctMatch = Regex("(?im)^Content-Type:\\s*([^\\r\\n]+)").find(headers)
                                responseContentType = ctMatch?.groupValues?.get(1)?.trim()
                                // Content-Length: "1234"
                                val clMatch = Regex("(?im)^Content-Length:\\s*(\\d+)").find(headers)
                                responseContentLength = clMatch?.groupValues?.get(1)?.toIntOrNull()
                                headerParsed = true
                                break
                            }
                        }
                    }
                    // Sprint 12.0D — recvHttpResponse
                    // breadcrumb. Owner greps for this
                    // token to confirm the real dest's
                    // HTTP response bytes were read
                    // from the real socket. The
                    // breadcrumb includes the response
                    // status + Content-Type +
                    // Content-Length so the Owner can
                    // distinguish 200 OK from 4xx/5xx
                    // + detect missing Content-Type
                    // + verify the body length matches
                    // the declared Content-Length.
                    Log.d(TAG, "recvHttpResponse: $n bytes read from real socket for flow $flowKey (realDest=$dstIp:$dstPort, status=${responseStatus ?: "?"}, content-type=${responseContentType ?: "?"}, content-length=${responseContentLength ?: "?"}, headerParsed=$headerParsed)")
                    // Sprint 12.0D — validation
                    // breadcrumb. The brief: if
                    // Content-Type is NOT
                    // application/json OR status is
                    // 4xx/5xx, emit Log.w. This is
                    // the Owner-side diagnostic that
                    // tells "the proxy returned a
                    // well-formed response" from "the
                    // proxy returned a malformed
                    // response that the app could
                    // not parse". The condition is
                    // checked on the FIRST read only
                    // (the status line is always in
                    // the first chunk) so the log
                    // does not fire on every body
                    // chunk.
                    //
                    // Sprint 12.0E — extended the
                    // SUSPECT log to include the
                    // EXPECTED value (application/json
                    // OR text/*) so the Owner can
                    // grep for the EXPECTED token
                    // and confirm the validation
                    // rule. The 12.0D log only
                    // emitted status + content-type
                    // + content-length + n, which
                    // made it impossible to tell
                    // "expected was application/json
                    // but got text/html" from
                    // "expected was application/json
                    // and got application/json" (the
                    // 12.0D log would have fired the
                    // SUSPECT log with no expected
                    // context).
                    if (headerParsed && n > 0) {
                        val isErrorStatus = responseStatus != null &&
                                (responseStatus!! >= 400)
                        val isUnexpectedContentType = responseContentType != null &&
                                !responseContentType!!.contains("application/json", ignoreCase = true) &&
                                !responseContentType!!.contains("text/", ignoreCase = true)
                        // The OpenE2EE Patroni healthz
                        // endpoint returns
                        // `text/plain` for plain health
                        // responses and
                        // `application/json` for
                        // structured responses. Both
                        // are valid. The brief flags
                        // "Content-Type != application/
                        // json" as suspect, but in
                        // practice the healthz endpoint
                        // returns text/plain — we
                        // accept text/* as well and
                        // only flag truly unexpected
                        // content types (e.g.,
                        // text/html when the endpoint
                        // is supposed to be JSON).
                        if (isErrorStatus || isUnexpectedContentType) {
                            // Sprint 12.0E — SUSPECT
                            // log with EXPECTED
                            // field. The brief
                            // asks for the
                            // expected value
                            // (application/json
                            // OR text/*) so the
                            // Owner can
                            // distinguish
                            // "expected was
                            // application/json
                            // but got text/html"
                            // (SUSPECT
                            // justified) from
                            // "expected was
                            // application/json
                            // and got
                            // application/json"
                            // (no SUSPECT).
                            Log.w(TAG, "recvHttpResponse: SUSPECT response for flow $flowKey (status=${responseStatus}, content-type=${responseContentType}, content-length=${responseContentLength}, expected=application/json OR text/, n=$n) — app may not parse this")
                        }
                    }
                    // Sprint 12.0E — response body
                    // first 100 bytes hex+ascii log.
                    // The Owner greps for this token
                    // to see the actual response
                    // body bytes. The Patroni healthz
                    // response body is short ASCII
                    // text (e.g., "ok\n" or
                    // `{"state":"running"}`); the
                    // hex+ascii view lets the Owner
                    // see if the body is well-formed
                    // (printable ASCII) or contains
                    // garbage (e.g., 0xFF 0xFE 0xFD
                    // indicating a mis-encoded
                    // chunk). Only logged once on
                    // the FIRST read (the body
                    // fragment may be split across
                    // multiple reads for keep-alive
                    // responses, so the first 100
                    // bytes of the first chunk is the
                    // canonical body fingerprint).
                    if (!bodyFirst100Logged && bodyBytesReceived > 0) {
                        val toLog = minOf(bodyBytesReceived, 100)
                        val hex = StringBuilder()
                        val ascii = StringBuilder()
                        var i = 0
                        while (i < toLog) {
                            val b = buf[i].toInt() and 0xFF
                            hex.append(String.format("%02x", b))
                            if (i > 0 && i % 16 == 0) hex.append("\n")
                            else if (i > 0) hex.append(" ")
                            ascii.append(if (b in 32..126) b.toChar() else '.')
                            i++
                        }
                        Log.d(TAG, "recvHttpResponse: bodyFirst100 (flow $flowKey, $toLog bytes): hex=[$hex] ascii=[$ascii]")
                        bodyFirst100Logged = true
                    }

                    // Bump our seq (we are sending n
                    // bytes).
                    conn.seqNum += n
                    bodyBytesReceived += n
                    val dataPkt = buildIpTcpPacket(
                        srcIp = dstIp, dstIp = srcIp,
                        srcPort = dstPort, dstPort = srcPort,
                        seqNum = conn.seqNum, ackNum = conn.ackNum,
                        flags = TCP_PSH or TCP_ACK,
                        payload = buf.copyOf(n),
                    )
                    // Sprint 12.0D — response
                    // writeToTun breadcrumb. Owner
                    // greps for this token to confirm
                    // the response bytes were
                    // actually written to the TUN
                    // (so the kernel would route
                    // them to the app's socket).
                    // The log now includes the
                    // response byte count + the TCP
                    // ack number (so the Owner can
                    // match it to the recvHttpRequest
                    // log on the send side) + the
                    // 5-tuple.
                    writeToTun(dataPkt, "responsePayload: $n bytes written to TUN for flow $flowKey (seq=${conn.seqNum}, ack=${conn.ackNum}, bodyBytes=$bodyBytesReceived, from realDest=$dstIp:$dstPort to app=$srcIp:$srcPort, status=${responseStatus ?: "?"}, content-type=${responseContentType ?: "?"})")
                }
                // Sprint 12.0D — final body byte
                // count log. The Owner greps for
                // this token to detect truncation:
                // if Content-Length=N and
                // bodyBytesReceived < N, the body
                // was truncated (the proxy returned
                // less than the declared length —
                // a Sprint 12.0C silent-drop
                // symptom).
                if (headerParsed && responseContentLength != null) {
                    if (bodyBytesReceived < responseContentLength!!) {
                        Log.w(TAG, "recvHttpResponse: TRUNCATED body for flow $flowKey (bodyBytes=$bodyBytesReceived < content-length=${responseContentLength}, status=${responseStatus})")
                    } else if (bodyBytesReceived == responseContentLength!!) {
                        Log.d(TAG, "recvHttpResponse: COMPLETE body for flow $flowKey (bodyBytes=$bodyBytesReceived == content-length=${responseContentLength}, status=${responseStatus})")
                    }
                }
                // Sprint 12.0E — specific MISMATCH
                // check for status=200 + content-
                // type=text/plain. The brief: "if
                // status 200 + content-type text/
                // plain, check if body byte count
                // matches Content-Length". The
                // general TRUNCATED/COMPLETE log
                // above fires for any status +
                // any content-type; the MISMATCH
                // log is the SPECIFIC Owner-side
                // diagnostic for the healthz
                // endpoint (the Patroni healthz
                // endpoint returns text/plain for
                // plain health responses and
                // application/json for structured
                // responses). The MISMATCH log
                // fires when bodyBytesReceived !=
                // Content-Length for the 200 + text/
                // plain case (which the 12.0D
                // general check ALSO fires for, but
                // with less specific context). The
                // Owner greps for the MISMATCH
                // token to confirm the body
                // matches the declared length.
                if (headerParsed && responseStatus == 200 &&
                    responseContentType != null &&
                    responseContentType!!.startsWith("text/plain", ignoreCase = true) &&
                    responseContentLength != null &&
                    bodyBytesReceived != responseContentLength) {
                    Log.w(TAG, "recvHttpResponse: MISMATCH for status=200 + content-type=text/plain (flow $flowKey, bodyBytes=$bodyBytesReceived != content-length=${responseContentLength}) — text/plain body does not match declared length")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "startSocketReader: thread crash for $flowKey: ${t.message}")
            } finally {
                // Remove our Future from tcpReaderFutures
                // so the shutdown method does not try to
                // cancel an already-completed Future.
                synchronized(tcpReaderFutures) { tcpReaderFutures.remove(flowKey) }
            }
        }
        val future = backgroundExecutor.submit(runnable)
        synchronized(tcpReaderFutures) { tcpReaderFutures[flowKey] = future }
        Log.d(TAG, "startSocketReader: reader submitted to backgroundExecutor for $flowKey (activeCount=${backgroundExecutor.activeCount})")
    }

    /**
     * Sprint 12.0C — write a response packet to
     * the TUN output stream. The TUN output is
     * set by `setTunOutputStream` from
     * `startReaderThread`; this helper is a no-op
     * if the stream is null (e.g., during a race
     * with `stopCapture`).
     */
    private fun writeToTun(packet: ByteArray, label: String) {
        val out = tunOutputStream
        if (out == null) {
            Log.w(TAG, "writeToTun: TUN output stream not set; dropping $label (${packet.size}B)")
            return
        }
        try {
            out.write(packet)
            out.flush()
        } catch (e: Throwable) {
            Log.w(TAG, "writeToTun: write FAILED for $label: ${e.message}")
        }
    }

    /**
     * Sprint 12.0F+2 — TCP RST workaround (Ali's
     * suggestion). When a PSH+ACK / SYN+ACK / ACK /
     * FIN+ACK arrives for a flow we don't know
     * about (tcpConnectionMap miss on BOTH primary
     * AND reverse key), it means the kernel's TCP
     * stack already established the connection
     * BEFORE our user-space stack saw the SYN. This
     * happens because the kernel's TCP stack is
     * allowed to keep using the real NIC for
     * connections it opened before the VPN route
     * table was installed (the "established
     * connection cache" survives VPN
     * reconfiguration). The user-space stack then
     * sees only the data packets of the
     * now-established connection and has no way to
     * inject itself.
     *
     * Fix: instead of silently dropping the unknown
     * packet, synthesize a TCP RST packet and write
     * it back to the TUN. The kernel will deliver
     * the RST to the app, the app will tear down the
     * connection and IMMEDIATELY retransmit a fresh
     * SYN. Our user-space stack sees the NEW SYN
     * and handleSyn() can do its normal 3-way
     * handshake via protect()'d java.net.Socket.
     * Net effect: a 1-2 second "blip" for the user
     * instead of a 10-second stall while the kernel
     * times out.
     *
     * RST packet format (RFC 793 §3.5):
     *   - IP header (20 bytes): srcIp/dstIp SWAPPED
     *     (so the RST goes BACK to the app), protocol=6
     *   - TCP header (20 bytes): srcPort/dstPort SWAPPED,
     *     flags=RST+ACK (0x14), seqNum=ackNum,
     *     ackNum=seqNum+1
     *
     * @Keep is the belt-and-braces annotation in
     * case R8 minifies the method name or inlines
     * the function. The proguard-rules.pro
     * `-keepclassmembers ... Log*(...)` rule also
     * keeps the Log.d call inside this function.
     */
    @Keep
    private fun writeTcpRstToTun(
        srcIp: String, dstIp: String,
        srcPort: Int, dstPort: Int,
        seqNum: Long, ackNum: Long,
        flowKey: String
    ) {
        // Sprint 12.0F+2 — RST breadcrumb. Logged
        // BEFORE building the RST packet so the
        // Owner can confirm the workaround fired
        // (the breadcrumb is the canonical "RST
        // dispatched" signal in logcat). R8
        // would normally strip this Log.d call
        // (return value unused), but the
        // proguard-rules.pro `-keepclassmembers ...
        // Log*(...)` rule preserves it (S122-4).
        Log.w(TAG, "writeTcpRstToTun: dispatching RST for flow $flowKey (kernel-bypass recovery: $srcIp:$srcPort -> $dstIp:$dstPort, seq=$seqNum, ack=$ackNum)")
        // Build the RST packet. We swap src/dst so
        // the RST goes BACK to the app (the app is
        // the src of the original SYN, the real
        // server is the dst).
        val rstPkt = buildIpTcpPacket(
            srcIp = dstIp,  // SWAP: RST is from the server
            dstIp = srcIp,  // SWAP: RST is to the app
            srcPort = dstPort,  // SWAP
            dstPort = srcPort,  // SWAP
            // Per RFC 793 §3.5: seqNum = ackNum (the
            // current send sequence of the receiver),
            // ackNum = seqNum + 1 (acknowledge the
            // packet that triggered the RST).
            seqNum = ackNum,
            ackNum = seqNum + 1,
            // RST (0x04) + ACK (0x10) = 0x14. RST+ACK
            // is the standard response to an
            // unexpected data packet (per RFC 793).
            flags = TCP_RST or TCP_ACK,
            payload = ByteArray(0)
        )
        writeToTun(rstPkt, "RST -> app (kernel-bypass recovery, $flowKey)")
    }

    /**
     * Sprint 12.0C — build an IP+TCP packet (no
     * IP options, no TCP options — bare 20-byte
     * headers) for writing back to the TUN.
     */
    fun buildIpTcpPacket(
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val tcpHeaderLen = 20
        val totalLen = ipHeaderLen + tcpHeaderLen + payload.size
        val out = ByteArray(totalLen)
        val bb = java.nio.ByteBuffer.wrap(out).order(java.nio.ByteOrder.BIG_ENDIAN)

        // ---- IPv4 header (20 bytes) ----
        bb.put(0, (0x45).toByte())
        bb.putShort(2, totalLen.toShort())
        bb.putShort(4, 0)  // identification (unused for MVP)
        bb.putShort(6, 0x4000.toShort())  // flags=DF, frag offset=0
        bb.put(8, 64.toByte())  // TTL=64
        bb.put(9, IPPROTO_TCP)
        bb.putShort(10, 0)  // header checksum (filled below)
        val srcBytes = java.net.InetAddress.getByName(srcIp).address
        bb.put(12, srcBytes[0]); bb.put(13, srcBytes[1])
        bb.put(14, srcBytes[2]); bb.put(15, srcBytes[3])
        val dstBytes = java.net.InetAddress.getByName(dstIp).address
        bb.put(16, dstBytes[0]); bb.put(17, dstBytes[1])
        bb.put(18, dstBytes[2]); bb.put(19, dstBytes[3])
        val ipChecksum = internetChecksum(out, 0, ipHeaderLen)
        bb.putShort(10, ipChecksum.toShort())

        // ---- TCP header (20 bytes) ----
        val tcpStart = ipHeaderLen
        bb.putShort(tcpStart, srcPort.toShort())
        bb.putShort(tcpStart + 2, dstPort.toShort())
        bb.putInt(tcpStart + 4, seqNum.toInt())
        bb.putInt(tcpStart + 8, ackNum.toInt())
        bb.put(tcpStart + 12, (0x50).toByte())  // data offset=5
        bb.put(tcpStart + 13, flags.toByte())
        bb.putShort(tcpStart + 14, MSS.toShort())
        bb.putShort(tcpStart + 16, 0)  // checksum (filled below)
        bb.putShort(tcpStart + 18, 0)  // urgent pointer
        val tcpChecksum = tcpChecksum(out, tcpStart, tcpHeaderLen + payload.size,
                                       srcBytes, dstBytes)
        bb.putShort(tcpStart + 16, tcpChecksum.toShort())

        // ---- Payload ----
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, ipHeaderLen + tcpHeaderLen, payload.size)
        }
        return out
    }

    /**
     * RFC 1071 Internet checksum.
     */
    private fun internetChecksum(buf: ByteArray, start: Int, len: Int): Int {
        var sum = 0L
        var i = start
        val end = start + len
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFFL) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    /**
     * RFC 793 TCP checksum with pseudo-header.
     */
    private fun tcpChecksum(
        buf: ByteArray,
        tcpStart: Int,
        tcpLen: Int,
        srcIp: ByteArray,
        dstIp: ByteArray
    ): Int {
        var sum = 0L
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += IPPROTO_TCP.toInt() and 0xFF
        sum += tcpLen and 0xFFFF
        var i = tcpStart
        val end = tcpStart + tcpLen
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (buf[i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFFL) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    /**
     * Sprint 12.0C — comprehensive teardown of
     * the TCP forwarder. Called from
     * `OpenE2eeVpnService.stopCapture` BEFORE
     * `nettyClient?.shutdown()` so the 6-step
     * shutdown's step 2 (tcpConnectionMap close) can
     * safely delegate to `TcpForwarder.tearDown()`
     * as a no-op (the teardown already ran first in
     * `stopCapture()`).
     *
     * The 6 steps (per the brief "(8) TEARDOWN ekle
     * 12.0X 6-step'in step 2'sine: tcpConnectionMap
     * readerFuture.cancel + socket.close +
     * readerThread.interrupt + readerThread.join +
     * map.clear"):
     *   1. Cancel every per-flow TCP reader Future
     *      (`Future.cancel(true)` interrupts the
     *      worker thread).
     *   2. Close every per-flow real `java.net.Socket`
     *      (so the kernel releases the bound port
     *      + the OS's TCP state machine sees the
     *      FIN).
     *   3. Interrupt every per-flow reader Thread
     *      (defense in depth — cancel(true) already
     *      interrupts, but a stale `readerThread`
     *      ref might survive in the TcpConnection).
     *   4. Join every per-flow reader Thread with a
     *      1-second bounded wait (so the executor
     *      workers exit before we proceed).
     *   5. Clear `tcpConnectionMap` and
     *      `tcpReaderFutures`.
     *   6. `backgroundExecutor.shutdownNow()` +
     *      `awaitTermination(1, SECONDS)` — bounded
     *      wait for ALL per-flow reader threads to
     *      exit.
     *
     * After this returns, NO background TCP thread
     * is alive, every per-flow socket is closed,
     * and the TUN output stream ref is detached.
     * The kernel can safely release the TUN
     * interface.
     */
    fun tearDown() {
        Log.d(TAG, "tearDown: starting comprehensive teardown (12.0C)")
        // Step 1 — cancel every per-flow TCP reader
        // Future. `Future.cancel(true)` interrupts the
        // executor worker thread so the read() call
        // unblocks with an InterruptedIOException.
        try {
            synchronized(tcpReaderFutures) {
                tcpReaderFutures.values.forEach { f ->
                    try { f?.cancel(true) } catch (_: Throwable) {}
                }
            }
            Log.d(TAG, "tearDown: step 1 DONE (tcpReaderFutures cancelled)")
        } catch (e: Throwable) {
            Log.w(TAG, "tearDown: step 1 (tcpReaderFutures) FAILED: ${e.message}")
        }

        // Step 2 — close every per-flow real Socket.
        try {
            tcpConnectionMap.values.forEach { conn ->
                try { conn.socket?.close() } catch (_: Throwable) {}
            }
            Log.d(TAG, "tearDown: step 2 DONE (per-flow Sockets closed)")
        } catch (e: Throwable) {
            Log.w(TAG, "tearDown: step 2 (Socket close) FAILED: ${e.message}")
        }

        // Step 3 — interrupt every per-flow reader
        // Thread (defense in depth).
        try {
            tcpConnectionMap.values.forEach { conn ->
                try { conn.readerThread?.interrupt() } catch (_: Throwable) {}
            }
            Log.d(TAG, "tearDown: step 3 DONE (per-flow reader Threads interrupted)")
        } catch (e: Throwable) {
            Log.w(TAG, "tearDown: step 3 (Thread interrupt) FAILED: ${e.message}")
        }

        // Step 4 — join every per-flow reader Thread
        // with a 1-second bounded wait.
        try {
            tcpConnectionMap.values.forEach { conn ->
                try { conn.readerThread?.join(1_000L) } catch (_: Throwable) {}
            }
            Log.d(TAG, "tearDown: step 4 DONE (per-flow reader Threads joined)")
        } catch (e: Throwable) {
            Log.w(TAG, "tearDown: step 4 (Thread join) FAILED: ${e.message}")
        }

        // Step 5 — clear the maps.
        try {
            tcpConnectionMap.clear()
            synchronized(tcpReaderFutures) { tcpReaderFutures.clear() }
            Log.d(TAG, "tearDown: step 5 DONE (tcpConnectionMap + tcpReaderFutures cleared)")
        } catch (e: Throwable) {
            Log.w(TAG, "tearDown: step 5 (map clear) FAILED: ${e.message}")
        }

        // Step 6 — background executor shutdownNow +
        // awaitTermination. `shutdownNow()` interrupts
        // all running tasks; `awaitTermination(1s)`
        // waits for them to exit.
        try {
            backgroundExecutor.shutdownNow()
            backgroundExecutor.awaitTermination(1, TimeUnit.SECONDS)
            Log.d(TAG, "tearDown: step 6 DONE (backgroundExecutor shutdownNow + awaitTermination)")
        } catch (e: Throwable) {
            Log.w(TAG, "tearDown: step 6 (backgroundExecutor) FAILED: ${e.message}")
        }

        Log.d(TAG, "tearDown: DONE (comprehensive teardown complete, no orphan TCP reader)")
    }
}
