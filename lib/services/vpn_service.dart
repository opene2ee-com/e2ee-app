// lib/services/vpn_service.dart
//
// Sprint 22 — VPN service bridge to the wirebare-kernel service.
//
// Architecture (Sprint 21 + 22.0–22.2 + 22.10–22.12):
//   lib/main.dart
//     └─ MethodChannel("com.opene2ee.e2ee_ap_v2/vpn")
//          └─ MainActivity.kt
//               ├─ WireBare.startProxy { addRoutes + addDnsServers }
//               │     └─ SimpleWireBareProxyService (wirebare-kernel)
//               │           └─ PacketDispatcher
//               │                └─ PacketCapture.observe()  ← 22.10
//               └─ PacketCapture (5s drain coroutine)
//                    └─ onPacketsSampled event   ──→  Dart side packetStream
//
// This Dart-side wrapper is the single entry point for all VPN
// start/stop/status calls AND for the inbound packet sample
// stream. It enforces the **singleton pattern** (Sprint 11.0F
// + 11.0G regression guard) — see the long comment on
// `VpnService._` for why a fresh-instance call site would
// silently break UI subscriptions.
//
// Sprint 22.10–22.12 surface:
//   - `start()`                       (calls WireBare.startProxy
//                                       with routes + DNS so
//                                       traffic actually flows)
//   - `stop({graceful})`              (flushes the Kotlin ring
//                                       + stops the proxy)
//   - `status()`                      (wirebare ProxyStatus
//                                       enum as String)
//   - `getSampledPackets()`           (drain the Kotlin ring
//                                       on demand — for the
//                                       Dart-side 5s poll in
//                                       pool_provider.dart)
//   - `packetStream`                  (broadcast Stream of
//                                       SampledPacket lists —
//                                       pushed every 5s by the
//                                       Kotlin drain coroutine
//                                       via the onPacketsSampled
//                                       MethodChannel event)
//   - `captureStats()`                (debug card — ring size,
//                                       observed/dropped totals)

import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../config.dart';
import 'packet_parser.dart';

/// Singleton accessor. All app code MUST use this — see `VpnService._`.
final vpnServiceProvider = Provider<VpnService>((ref) => VpnService.instance);

/// Stream of packet batches pushed by the Kotlin
/// `PacketCapture` drain coroutine (Sprint 22.10+). Consumers
/// should use the `packetStreamProvider` below rather than
/// touching `VpnService.packetStream` directly — Riverpod
/// handles the subscription lifecycle.
final packetStreamProvider = StreamProvider<List<SampledPacket>>((ref) {
  final vpn = ref.watch(vpnServiceProvider);
  return vpn.packetStream;
});

/// Dart-side VPN lifecycle states. Mirrors the Kotlin
/// `com.opene2ee.e2ee_ap_v2.vpn.common.ProxyStatus` enum so the
/// UI can render the same labels in Turkish + English. The
/// source of truth is the Kotlin `WireBare.proxyStatus`, polled
/// on demand via `status()` and on lifecycle resume.
enum VpnLifecycleState {
  /// No service is alive (or service is in DEAD).
  idle,

  /// `start()` is in flight — WireBare is dispatching the intent
  /// to `SimpleWireBareProxyService`.
  starting,

  /// Service is ACTIVE — TUN interface is up, packets are
  /// flowing through wirebare's TCP/UDP proxies.
  running,

  /// Service is being torn down (WireBare.stopProxy invoked, the
  /// foreground notification is being removed, TUN is being
  /// closed). Maps from `DYING`.
  stopping,

  /// Service has been fully stopped. Maps from `DEAD`.
  stopped,

  /// Service is in ERROR — wirebare's PacketDispatcher reported
  /// a failure that is not recoverable (rare; usually surfaced
  /// via `ImportantEvent` rather than the status enum).
  error,
}

class VpnService {
  /// Sprint 11.0F + 11.0G — the ONLY constructor. The `_` prefix
  /// makes the ctor private to this library so external code
  /// CANNOT call `VpnService()` to construct a fresh instance.
  /// All app code MUST use [VpnService.instance] (singleton
  /// accessor) or [VpnService.forTesting] (test-only factory).
  ///
  /// Pre-11.0F, each call site constructed a fresh [VpnService],
  /// which:
  ///   (a) replaced the previous `_channel.setMethodCallHandler`
  ///       (so events landed on whichever instance was
  ///       constructed LAST — typically `PoolNotifier` in the
  ///       Riverpod provider graph, not the `active_pool_screen`),
  ///   (b) created a fresh `_packetCtrl` StreamController, so UI
  ///       subscribers to the OLD instance's `packetStream`
  ///       never saw updates.
  ///
  /// The singleton is the regression guard. If a future refactor
  /// accidentally writes `_vpn = VpnService()` somewhere, it MUST
  /// fail to compile.
  VpnService._({MethodChannel? channel})
      : _channel = channel ?? const MethodChannel(AppConfig.vpnChannelName) {
    _installChannelHandler();
  }

  /// The canonical singleton accessor. All app code uses this.
  static final VpnService _instance = VpnService._();

  /// THE canonical singleton accessor. All app code MUST use
  /// `VpnService.instance` (NOT `VpnService()`).
  static VpnService get instance => _instance;

  /// Test-only factory for injecting a custom [MethodChannel]
  /// (e.g. `TestDefaultBinaryMessengerBinding` mock). Returns a
  /// fresh instance — do NOT use from app code, use
  /// [VpnService.instance] instead. The new instance wires up
  /// its own inbound-event handler on the test channel.
  @visibleForTesting
  factory VpnService.forTesting({MethodChannel? channel}) =>
      VpnService._(channel: channel);

  final MethodChannel _channel;

  /// Sprint 22.10+ — inbound events from the Kotlin side. The
  /// `onPacketsSampled` event is pushed every 5 seconds by the
  /// `PacketCapture` drain coroutine with the buffered
  /// `SampledPacket` list. Lazily created so the broadcast
  /// controller's resources (and the [packetStream] listener
  /// set) only exist when at least one consumer is interested.
  StreamController<List<SampledPacket>>? _packetController;

  /// The Kotlin-pushed packet stream. Broadcast — multiple
  /// subscribers are allowed (the active pool screen and the
  /// telemetry service can both listen without one starving the
  /// other). Returns an empty stream until the controller is
  /// touched for the first time.
  Stream<List<SampledPacket>> get packetStream =>
      _packetCtrl.stream;

  StreamController<List<SampledPacket>> get _packetCtrl =>
      _packetController ??= StreamController<List<SampledPacket>>.broadcast();

  /// Install the inbound-event handler. The `MethodChannel` is
  /// bidirectional: Dart sends RPCs via `invokeMethod` (used by
  /// `start()` / `stop()` / `status()` / `getSampledPackets()`)
  /// AND receives push events via this handler. We currently
  /// listen for a single event: `onPacketsSampled`. Idempotent
  /// — safe to call from the constructor (singleton path) and
  /// from `VpnService.forTesting` (each test instance has its
  /// own channel).
  void _installChannelHandler() {
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onPacketsSampled':
          _handlePacketsSampled(call.arguments);
          return null;
        default:
          // Unknown inbound event — ignore. The Dart side
          // owns the protocol version; the Kotlin side will
          // not push anything we don't recognise.
          return null;
      }
    });
  }

  /// Decode the `onPacketsSampled` event payload and push it to
  /// the [packetStream]. Tolerant of:
  ///   - `null` arguments (no-op),
  ///   - non-list arguments (no-op, log in debug),
  ///   - missing optional fields inside each map (SampledPacket
  ///     `fromJson` defaults to `null` / `0` / `'other'`).
  void _handlePacketsSampled(Object? arguments) {
    if (arguments is! List) return;
    final packets = arguments
        .whereType<Map>()
        .map((m) => SampledPacket.fromJson(m.cast<String, Object?>()))
        .toList(growable: false);
    if (packets.isEmpty) return;
    _packetCtrl.add(packets);
  }

  /// Start the local TUN capture via wirebare-kernel. The
  /// caller must already hold RESULT_OK from `VpnService.prepare`;
  /// if not, the call returns `'consent_required'` and the
  /// Kotlin side waits for `onActivityResult` before
  /// dispatching `WireBare.startProxy`.
  ///
  /// The Kotlin-side `WireBare.startProxy` block (Sprint 22.12)
  /// adds `0.0.0.0/0` route + `8.8.8.8` / `1.1.1.1` DNS so
  /// traffic actually flows through the proxy. Without those
  /// the TUN is up but the dispatcher loop is idle (default
  /// `WireBareConfiguration.routes` is empty).
  Future<String> start() async {
    // ignore: avoid_print
    print('vpn_service.dart: start() ENTERED, invoking MethodChannel(startVpn)');
    try {
      final r = await _channel.invokeMethod<String>('startVpn');
      // ignore: avoid_print
      print('vpn_service.dart: start() invokeMethod returned $r');
      return r ?? 'started';
    } catch (e, st) {
      // ignore: avoid_print
      print('vpn_service.dart: start() THREW: $e\n$st');
      rethrow;
    }
  }

  /// Stop capture. When [graceful] is true, the Kotlin side
  /// flushes the ring buffer (the Dart side receives a final
  /// `onPacketsSampled` batch covering the tail of the session)
  /// and then tears down; when false, the ring is dropped
  /// immediately (used on user-cancel to avoid leaking the
  /// partial session's metadata).
  Future<void> stop({bool graceful = true}) async {
    await _channel.invokeMethod<void>(
      'stopVpn',
      {'graceful': graceful},
    );
  }

  /// Snapshot the current service status. Returns the wirebare
  /// `ProxyStatus` enum name as a String (`ACTIVE`, `DEAD`,
  /// `STARTING`, `DYING`, `ERROR`). The caller is expected to
  /// convert it into a [VpnLifecycleState] via
  /// [vpnLifecycleFromWirebareName] before driving UI from it.
  Future<String> status() async {
    final r = await _channel.invokeMethod<String>('status');
    return r ?? 'DEAD';
  }

  /// Drain the Kotlin ring buffer of metadata snapshots on
  /// demand. The Dart-side `pool_provider.dart` 5s poll still
  /// uses this pull-based path in addition to the push-based
  /// [packetStream] — pull guarantees we catch samples even if
  /// the Flutter side missed a push (process backgrounded,
  /// activity destroyed, etc.). Returns an empty list when the
  /// channel is not yet wired (test stubs).
  Future<List<SampledPacket>> getSampledPackets() async {
    final raw = await _channel.invokeMethod<List<Object?>>('getSampledPackets');
    if (raw == null) return const <SampledPacket>[];
    return raw
        .whereType<Map<Object?, Object?>>()
        .map((m) => SampledPacket.fromJson(m.cast<String, Object?>()))
        .toList(growable: false);
  }

  /// Read the Kotlin-side capture stats. Used by the debug
  /// card on the active pool screen to show the ring fill
  /// level + the observed/dropped counters. Returns `null` on
  /// the test path (channel mock returns no value).
  Future<Map<String, Object?>?> captureStats() async {
    try {
      final r = await _channel.invokeMethod<Map<Object?, Object?>>('captureStats');
      return r?.cast<String, Object?>();
    } catch (_) {
      return null;
    }
  }

  /// Test-only teardown — closes the broadcast stream
  /// controller. Production code uses [VpnService.instance]
  /// which is intentionally never disposed (the singleton
  /// lives for the app lifetime).
  @visibleForTesting
  Future<void> disposeForTest() async {
    await _packetController?.close();
    _packetController = null;
  }
}

/// Map the wirebare ProxyStatus name (String) onto the
/// Dart-side [VpnLifecycleState]. Used by screens that want to
/// render the lifecycle pill.
VpnLifecycleState vpnLifecycleFromWirebareName(String name) {
  switch (name.toUpperCase()) {
    case 'STARTING':
      return VpnLifecycleState.starting;
    case 'ACTIVE':
      return VpnLifecycleState.running;
    case 'DYING':
      return VpnLifecycleState.stopping;
    case 'ERROR':
      return VpnLifecycleState.error;
    case 'DEAD':
    default:
      return VpnLifecycleState.idle;
  }
}

/// Sentinel for "no state observed yet" — the lifecycle is only
/// known after the first `status()` call resolves.
const VpnLifecycleState kVpnLifecycleUnknown = VpnLifecycleState.idle;
