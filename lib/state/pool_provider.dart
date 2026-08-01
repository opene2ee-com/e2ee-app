// lib/state/pool_provider.dart
//
// Sprint 22.3 + 22.10–22.12 — Active pool (Aktif Nöbet) state
// + Riverpod notifier.
//
// Two complementary data sources feed the pool screen:
//
//   A. PUSH — `VpnService.packetStream` (Sprint 22.10+)
//      The Kotlin `PacketCapture` drain coroutine pushes a
//      batch every 5 seconds via the `onPacketsSampled`
//      MethodChannel event. We subscribe in `_start()` and
//      bump `paketSayisi` + `paketGecmisi` reactively — no
//      latency between the Kotlin drain tick and the UI.
//
//   B. PULL — 5-second polling tick for:
//        1. `P2PMatcher.findActiveReceivers` — real peer
//           count from `GET /api/v1/sessions` (mobile-side
//           filter, see `p2p_matcher.dart`).
//        2. `VpnService.getSampledPackets` — belt-and-
//           suspenders pull: catches any packets the push
//           missed (app backgrounded, activity destroyed,
//           push event dropped by Android IPC).
//        3. `TelemetryService.send` — pushes the sampled
//           packet metadata to `<apiBase>/api/v1/telemetry`.
//
// No double counting: both the push and the pull drain the
// Kotlin ring (destructive). Whichever fires first wins for
// that 5-second window; the other finds an empty ring and
// no-ops.
//
// Debug fields (lastError / lastSuccess / isLoading / lastUpdate /
// apiCallCount) are surfaced on the active pool screen so the
// Owner can see what the API is doing from the UI
// (`docs/SPRINT-10.1C-SCOPE.md` for the original brief).
//
// Sprint 11.0O invariant: NO `Timer.periodic` mock ticker that
// bumps counts without a real API call. The 5s poll above is
// the only ticker; all numbers in `PoolState` are derived from
// real API responses or the Kotlin push.

import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../config.dart';
import '../services/auth_service.dart';
import '../services/p2p_matcher.dart';
import '../services/packet_parser.dart';
import '../services/telemetry_service.dart';
import '../services/vpn_service.dart';

/// Pool state — surfaced to the ActivePoolScreen. The 10.1C
/// debug fields (lastError / lastSuccess / isLoading /
/// lastUpdate / apiCallCount) are kept verbatim so the audit
/// invariants S33 + S86 still pass.
class PoolState {
  const PoolState({
    required this.isAlici,
    required this.paketSayisi,
    required this.gonulluSayisi,
    required this.testEdilenler,
    required this.paketGecmisi,
    required this.sonGuncelleme,
    required this.lastError,
    required this.lastSuccess,
    required this.isLoading,
    required this.lastUpdate,
    required this.apiCallCount,
  });

  /// Whether the user is currently flagged as a receiver in
  /// the pool. Gated by the "Alıcı Ol" toggle on the screen.
  final bool isAlici;

  /// Cumulative number of packets observed in this session.
  final int paketSayisi;

  /// Number of pool volunteers currently connected (real
  /// count from the matcher, or 0 when the matcher hasn't
  /// returned a value yet — NO mock 2-5 fallback).
  final int gonulluSayisi;

  /// Subset of `{rcs, whatsapp}` representing transports that
  /// have completed a smoke test in this session.
  final Set<String> testEdilenler;

  /// Last 10 per-tick packet deltas. Used by the `fl_chart`
  /// LineChart on the active pool screen.
  final List<int> paketGecmisi;

  /// Wall-clock time of the most recent tick.
  final DateTime? sonGuncelleme;

  /// Last API error string. `null` when the most recent tick
  /// was clean.
  final String? lastError;

  /// Human-readable summary of the last successful API call.
  /// `null` when no call has succeeded yet, or the most recent
  /// call was an error.
  final String? lastSuccess;

  /// `true` while an API call is in flight. Drives the
  /// CircularProgressIndicator on each stat card.
  final bool isLoading;

  /// Wall-clock timestamp of the last completed tick
  /// (success OR error). Mirrors `sonGuncelleme` for
  /// backwards-compat with the original 10.1A screen caption.
  final DateTime? lastUpdate;

  /// Monotonically-increasing counter of every API call
  /// attempted in this session. Surfaced in debug builds.
  final int apiCallCount;

  PoolState copyWith({
    bool? isAlici,
    int? paketSayisi,
    int? gonulluSayisi,
    Set<String>? testEdilenler,
    List<int>? paketGecmisi,
    DateTime? sonGuncelleme,
    String? lastError,
    String? lastSuccess,
    bool? isLoading,
    DateTime? lastUpdate,
    int? apiCallCount,
    bool clearLastError = false,
    bool clearLastSuccess = false,
  }) {
    return PoolState(
      isAlici: isAlici ?? this.isAlici,
      paketSayisi: paketSayisi ?? this.paketSayisi,
      gonulluSayisi: gonulluSayisi ?? this.gonulluSayisi,
      testEdilenler: testEdilenler ?? this.testEdilenler,
      paketGecmisi: paketGecmisi ?? this.paketGecmisi,
      sonGuncelleme: sonGuncelleme ?? this.sonGuncelleme,
      lastError: clearLastError ? null : (lastError ?? this.lastError),
      lastSuccess:
          clearLastSuccess ? null : (lastSuccess ?? this.lastSuccess),
      isLoading: isLoading ?? this.isLoading,
      lastUpdate: lastUpdate ?? this.lastUpdate,
      apiCallCount: apiCallCount ?? this.apiCallCount,
    );
  }

  /// History capacity for the `paketGecmisi` ring buffer.
  static const int tarihceKapasite = 10;

  /// Factory: initial state. All-zero initial values —
  /// pre-11.0O mock numbers (247 paket, 3 gönüllü) were the
  /// source of the Owner 13:20 "numbers animate without VPN"
  /// symptom. Sprint 22.3 keeps the zero-only initial state.
  factory PoolState.initial() {
    return const PoolState(
      isAlici: true,
      paketSayisi: 0,
      gonulluSayisi: 0,
      testEdilenler: <String>{},
      paketGecmisi: <int>[],
      sonGuncelleme: null,
      lastError: null,
      lastSuccess: null,
      isLoading: false,
      lastUpdate: null,
      apiCallCount: 0,
    );
  }
}

class PoolNotifier extends StateNotifier<PoolState> {
  PoolNotifier({
    P2PMatcher? matcher,
    VpnService? vpnService,
    TelemetryService? telemetry,
    AuthService? auth,
  })  : _matcher = matcher ??
            P2PMatcher(apiKey: kApiKey, auth: auth),
        // Sprint 22.2 — `_vpn` uses the canonical singleton
        // accessor (Sprint 11.0G invariant). Pre-11.0G, the
        // `VpnService()` call site was indistinguishable from
        // a fresh-instance constructor. The 22.2 fix removes
        // the public factory entirely; only `VpnService.instance`
        // (singleton) and `VpnService.forTesting(...)` (test
        // override) remain callable. The DI surface for
        // production code is [vpnServiceProvider] — tests
        // can override it via
        // `ProviderScope(overrides: [vpnServiceProvider.overrideWithValue(mock)])`.
        _vpn = vpnService ?? VpnService.instance,
        // Use the build-time DEVICE_ID as the session id so
        // the backend BFF correlates every telemetry +
        // matcher poll with the same device record. Falls
        // back to TelemetryService's per-process random id
        // when DEVICE_ID is empty.
        _telemetry = telemetry ??
            TelemetryService(
              apiKey: kApiKey,
              sessionId: kDeviceId.isNotEmpty ? kDeviceId : null,
              auth: auth,
            ),
        super(PoolState.initial()) {
    _sessionId = kDeviceId.isNotEmpty ? kDeviceId : _telemetry.sessionId;
    _start();
  }

  /// Polling cadence — 5 seconds per the 10.1B brief.
  static const Duration _pollPeriod =
      Duration(seconds: kPoolPollSeconds);

  final P2PMatcher _matcher;
  final VpnService _vpn;
  final TelemetryService _telemetry;
  late final String _sessionId;
  Timer? _pollTimer;
  StreamSubscription<List<SampledPacket>>? _packetSub;

  void _start() {
    _pollTimer?.cancel();
    // Only the REAL 5s API poll. NO mock ticker.
    _pollTimer = Timer.periodic(_pollPeriod, (_) => _apiTick());

    // Sprint 22.10+ — subscribe to the Kotlin push stream.
    // Each emitted batch is a non-empty `List<SampledPacket>`
    // drained from the wirebare-kernel `PacketCapture` ring.
    // We update `paketSayisi` + `paketGecmisi` synchronously
    // here (the 5s poll still runs the API calls + telemetry
    // push; the pull in `_apiTick` is a safety net for
    // backgrounded apps that missed the push).
    _packetSub?.cancel();
    _packetSub = _vpn.packetStream.listen(
      _onPacketsSampled,
      onError: (Object e) {
        // Defensive — a stream error must not crash the
        // pool notifier. The next batch will retry.
        state = state.copyWith(lastError: 'packetStream: $e');
      },
    );
  }

  /// Push-based update: every batch the Kotlin drain emits
  /// lands here. We accumulate into `paketSayisi` + the
  /// 10-tick `paketGecmisi` ring (FIFO).
  void _onPacketsSampled(List<SampledPacket> batch) {
    if (batch.isEmpty) return;
    final yeniPaketSayisi = state.paketSayisi + batch.length;
    final yeniTarihce = List<int>.from(state.paketGecmisi)..add(batch.length);
    while (yeniTarihce.length > PoolState.tarihceKapasite) {
      yeniTarihce.removeAt(0);
    }
    final ts = DateTime.now();
    state = state.copyWith(
      paketSayisi: yeniPaketSayisi,
      paketGecmisi: yeniTarihce,
      sonGuncelleme: ts,
      lastUpdate: ts,
      clearLastError: true,
      lastSuccess: 'paketStream: +${batch.length} paket',
    );
  }

  /// Real API tick — pings the P2P matcher, drains the VPN
  /// ring (belt-and-suspenders), and pushes ONE telemetry
  /// observation per tick (Sprint 23.2: v1 schema — single
  /// observation, no `packets` array).
  Future<void> _apiTick() async {
    if (!state.isAlici) return;
    state = state.copyWith(
      isLoading: true,
      apiCallCount: state.apiCallCount + 1,
    );
    try {
      // 1. P2P match poll. Returns a `List<String>` of
      //    active-receiver session ids other than ourselves.
      final peers = await _matcher.findActiveReceivers(_sessionId);
      // 2. Drain the VPN ring (pull fallback — most packets
      //    arrive via `packetStream` in `_onPacketsSampled`,
      //    but the 5s poll still pulls in case the push was
      //    missed: app backgrounded, activity destroyed, or
      //    a brief IPC failure). Sprint 23.2: the samples
      //    are NOT sent as `packets[]` anymore — the v1
      //    schema has no such field. We only use the COUNT
      //    (`samples.length`) to update the on-screen counter
      //    and pick a representative masked IP for the
      //    `ip_subnet` observation field.
      final samples = await _vpn.getSampledPackets();
      // 3. Push ONE telemetry observation (v1 schema).
      //    Skip when the pool is empty AND no samples — the
      //    backend's per-minute rate limit (60/min) is plenty
      //    for a 5s cadence but we'd rather not pollute the
      //    DB with zero-information rows. We always push on
      //    a non-empty samples window so the backend's
      //    per-session counters advance.
      if (samples.isNotEmpty || state.apiCallCount % 6 == 0) {
        // Send one v1 observation per tick. The Dart-side
        // `fromStubs` factory already wires the right
        // device_id_hash / public_key_fp / tls_fp /
        // operator / app; we add the per-session `sessionId`
        // + `matchMode: 'p2p'` and the entropy = 0.0
        // placeholder (real entropy lands in Sprint 24+).
        // `ip_subnet` is intentionally NOT sent — the v1
        // schema's `additionalProperties:false` rejects any
        // unmapped field (probe_v14 confirmed).
        await _telemetry.sendObservation(
          TelemetryObservation.fromStubs(
            deviceIdHash: kDeviceId,
            sessionId: _sessionId,
            matchMode: 'p2p',
          ),
        );
      }
      final ts = DateTime.now();
      // Real counts — samples.length is the per-tick packet
      // count; we add to a cumulative `paketSayisi` only when
      // the VPN is actually returning samples.
      final yeniPaketSayisi = samples.isNotEmpty
          ? state.paketSayisi + samples.length
          : state.paketSayisi;
      final yeniTarihce = samples.isNotEmpty
          ? (List<int>.from(state.paketGecmisi)..add(samples.length))
          : state.paketGecmisi;
      while (yeniTarihce.length > PoolState.tarihceKapasite) {
        yeniTarihce.removeAt(0);
      }
      state = state.copyWith(
        isLoading: false,
        sonGuncelleme: ts,
        lastUpdate: ts,
        clearLastError: true,
        lastSuccess: peers.isNotEmpty
            ? 'Eşleşme bulundu: ${peers.first.substring(0, peers.first.length.clamp(0, 8))}…'
            : 'Eşleşme kontrol edildi: yok (${samples.length} paket)',
        gonulluSayisi: peers.length,
        paketSayisi: yeniPaketSayisi,
        paketGecmisi: yeniTarihce,
      );
    } catch (e) {
      final ts = DateTime.now();
      state = state.copyWith(
        isLoading: false,
        sonGuncelleme: ts,
        lastUpdate: ts,
        clearLastSuccess: true,
        lastError: e.toString(),
      );
    }
  }

  /// "Alıcı Ol" toggle — flipping ON schedules a fresh tick;
  /// OFF freezes the numbers and pauses the API call.
  void toggleAlici() {
    final yeniAlici = !state.isAlici;
    final ts = yeniAlici ? DateTime.now() : state.sonGuncelleme;
    state = state.copyWith(
      isAlici: yeniAlici,
      sonGuncelleme: ts,
      lastUpdate: ts,
    );
  }

  /// "test tamamlandı" callback. Lets the screen surface a
  /// new transport in `testEdilenler` when an async smoke-test
  /// completes.
  void raporTestTamamlandi(String transport) {
    if (state.testEdilenler.contains(transport)) return;
    state = state.copyWith(
      testEdilenler: {...state.testEdilenler, transport},
      sonGuncelleme: DateTime.now(),
    );
  }

  @override
  void dispose() {
    _pollTimer?.cancel();
    _pollTimer = null;
    _packetSub?.cancel();
    _packetSub = null;
    _matcher.close();
    _telemetry.close();
    super.dispose();
  }
}

/// Sprint 22.10+ — `VpnService.getSampledPackets()` already
/// returns the typed `List<SampledPacket>` (parsing happens
/// inside the service), so this file no longer needs a map
/// helper. The wire format is documented in
/// `lib/services/packet_parser.dart` (`SampledPacket.fromJson`)
/// and matches the Kotlin `PacketCapture` toMap() keys.

final poolProvider = StateNotifierProvider<PoolNotifier, PoolState>(
  (ref) {
    // Read the shared `authProvider` so the matcher +
    // telemetry share ONE cached JWT (5-min pre-expiry
    // refresh window). Without this wiring, each service
    // would mint its own JWT on first call.
    final auth = ref.watch(authProvider);
    return PoolNotifier(auth: auth);
  },
);
