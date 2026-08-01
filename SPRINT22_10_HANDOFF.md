# Sprint 22.10–22.12 — Packet capture handoff

**Branch:** `e2ee-ap-v2`
**Build SHA-256:** `9210F354625400E549074E20447E636E1F233DA00B9D64D148CFAB5998DED3F8`
**APK size:** 200.6 MB (`build/app/outputs/flutter-apk/app-debug.apk`)

## What landed

End-to-end packet-capture pipeline: wirebare-kernel TUN → Kotlin
`PacketCapture` (5s drain) → MethodChannel push → Dart
`VpnService.packetStream` → `PoolNotifier` reactive updates.

### 22.10 — Kotlin observer (wirebare extension-point pattern)

- `android/.../vpn/capture/SampledPacket.kt` — wire-format data
  class matching the Dart `SampledPacket.toJson()` keys exactly
  (ADR-0006 /24 for IPv4, /48 for IPv6, no payload bytes).
- `android/.../vpn/capture/PacketCapture.kt` — process-wide
  singleton:
  - `ConcurrentLinkedDeque` ring buffer (capacity 100, FIFO
    drop-oldest)
  - 5-second drain coroutine (`SupervisorJob + Dispatchers.IO`,
    self-managed scope)
  - `MutableSharedFlow<List<SampledPacket>>` for in-JVM consumers
  - External sink hook for the Flutter MethodChannel
  - `observe` / `drain` / `reset` / `start` / `stop(flush=true)` /
    `stats`
- `vpn/service/PacketDispatcher.kt` modified: after every
  `IPHeader.parse()` succeeds, the dispatcher calls
  `extractSampled(ipHeader, packet)` and pushes the result to
  `PacketCapture.observe()`. This sits BEFORE the protocol-
  specific interceptor — so we capture unknown protocols too.

### 22.11 — Dart `VpnService.packetStream`

- `lib/services/vpn_service.dart`:
  - New `Stream<List<SampledPacket>> packetStream` (broadcast).
  - New `packetStreamProvider` (`StreamProvider<List<SampledPacket>>`).
  - `setMethodCallHandler` in the constructor listens for the
    Kotlin `onPacketsSampled` event and pushes the parsed batch
    to the stream.
  - `getSampledPackets()` now returns `List<SampledPacket>`
    (typed, parsed) — was `List<Map<String, Object?>>` in
    Sprint 22.6. Test `sprint110d_handler_test.dart` updated to
    match.
  - `captureStats()` new — surfaces the Kotlin `CaptureStats` map
    for the debug card.
  - `disposeForTest()` for test teardown.

### 22.12 — Wirebare config + main bridge

- `android/.../MainActivity.kt` rewritten:
  - `startVpn` now passes a real `WireBareConfiguration`:
    `addRoutes("0.0.0.0", 0)` + `addDnsServers("8.8.8.8",
    "1.1.1.1")` so traffic actually flows through the TUN.
  - MethodChannel handlers added: `status`, `getSampledPackets`,
    `resetPacketCapture`, `captureStats`.
  - `PacketCapture.registerSink` wired in
    `configureFlutterEngine` — the 5-second drain pushes each
    batch to Dart via `onPacketsSampled` (marshalled to main
    thread before `MethodChannel.invokeMethod`).
  - `PacketCapture.start()` invoked after `WireBare.startProxy`
    in `launchProxy()`; `PacketCapture.stop(flush=true)` invoked
    before `WireBare.stopProxy` on graceful stop.

### 22.10–22.12 — Dart-side wiring

- `lib/state/pool_provider.dart`:
  - New `_packetSub` `StreamSubscription<List<SampledPacket>>` —
    each Kotlin push bumps `paketSayisi` + appends batch size to
    `paketGecmisi` (10-tick ring, FIFO).
  - `_apiTick` still does the 5-second poll for
    `findActiveReceivers` + `telemetry.send` (belt-and-suspenders
    pull fallback for backgrounded apps).
  - Removed the now-unnecessary `_mapToSampledPacket` helper —
    `getSampledPackets()` returns typed `List<SampledPacket>`.

## Tests

43 tests pass (`flutter test`):
- Sprint 22 base: 38 tests (unchanged)
- Sprint 22.10 new in `sprint110d_handler_test.dart`:
  `packetStream` push from `onPacketsSampled` event → broadcast
  List<SampledPacket>.
- Sprint 22.10 new `test/vpn_packet_stream_test.dart` (4 cases):
  - `captureStats()` happy path
  - `captureStats()` returns null on missing channel
  - `packetStream` is broadcast (two subscribers both receive)
  - `PoolNotifier` reacts to push (counter + ring updated
    without waiting for 5s tick)

## How to verify on the OnePlus 9 Pro

1. Install the APK (`adb install -r build/app/outputs/flutter-apk/app-debug.apk`)
2. Open the app, accept bilgilendirme
3. Home → bottom nav → **Aktif Nöbet**
4. Toggle **"Alıcı Ol"** ON (it was already ON in 22.8 but the
   VPN toggle there is what matters)
5. Approve the VPN consent dialog (first time only)
6. **Expected within 5–10s**: `paketSayisi` increments,
   `paketGecmisi` chart shows new bar — every TCP/UDP packet
   the OS routes through the TUN is sampled
7. Toggle **"Alıcı Ol"** OFF → counters freeze
8. Toggle ON again → counters resume (new session, ring reset)

## Wire format key alignment (Kotlin ↔ Dart)

Both `SampledPacket.toMap()` and `SampledPacket.toJson()` emit:
```
version: Int
protocol: String  ('tcp'|'udp'|'icmp'|'other')
protocolNumber: Int
packetLength: Int
srcIpMasked: String  (IPv4: a.b.c.0, IPv6: hextet1:hextet2:hextet3:0:0:0:0:0)
dstIpMasked: String
srcPort: Int?  (TCP/UDP only)
dstPort: Int?  (TCP/UDP only)
tcpFlags: Int?  (TCP only)
tlsClientHelloFingerprint: String?  (reserved — always null in 22.10)
```

Dart side round-trips via `SampledPacket.fromJson()` with
sensible defaults for missing optional fields.

## Known limitations (deferred to later sprints)

- `tlsClientHelloFingerprint` always null — TLS SNI hashing is
  Sprint 23+ work
- `getSampledPackets` does not deduplicate with `packetStream` —
  the ring is drained destructively, but the pull path's
  push to `telemetry.send` runs every 5s while the push path's
  listener is reactive. Net result: telemetry sees a sample
  at most once (the destructive drain guarantees it), but the
  on-screen counter updates the moment a push arrives instead
  of waiting for the 5s tick
- No Kotlin-side unit tests — `flutter test` covers the wire
  format + the Dart-side plumbing; the Kotlin code is exercised
  by the build (compiles + links) and by the manual device test
  above
