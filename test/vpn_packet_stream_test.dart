// test/vpn_packet_stream_test.dart
//
// Sprint 22.10 — Dart-side `packetStream` + `captureStats` + `poolProvider`
// reaction tests.
//
// Verifies the new Dart-side plumbing of the wirebare-kernel
// push pipeline:
//
//   1. `VpnService.captureStats()` invokes the
//      `captureStats` MethodChannel and round-trips the
//      Kotlin `CaptureStats` map.
//   2. `VpnService.packetStream` is a broadcast stream
//      that survives multiple subscribers (the pool
//      notifier and a hypothetical second consumer).
//   3. The `PoolNotifier` reacts to a push by
//      incrementing `paketSayisi` and appending the
//      batch size to `paketGecmisi` — without waiting
//      for the 5-second poll.

import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:e2ee_ap_v2/services/packet_parser.dart';
import 'package:e2ee_ap_v2/services/vpn_service.dart';
import 'package:e2ee_ap_v2/state/pool_provider.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Sprint 22.10 — VpnService.captureStats()', () {
    test('invokes the `captureStats` MethodChannel and returns the map',
        () async {
      final vpn = VpnService.forTesting();

      const mockedStats = <String, Object?>{
        'ringSize': 7,
        'totalObserved': 42,
        'totalDropped': 3,
        'drainRunning': true,
      };

      TestDefaultBinaryMessengerBinding
          .instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
        const MethodChannel('com.opene2ee.e2ee_ap_v2/vpn'),
        (MethodCall call) async {
          if (call.method == 'captureStats') return mockedStats;
          return null;
        },
      );

      try {
        final stats = await vpn.captureStats();
        expect(stats, isNotNull);
        expect(stats!['ringSize'], 7);
        expect(stats['totalObserved'], 42);
        expect(stats['totalDropped'], 3);
        expect(stats['drainRunning'], true);
      } finally {
        TestDefaultBinaryMessengerBinding
            .instance.defaultBinaryMessenger
            .setMockMethodCallHandler(
          const MethodChannel('com.opene2ee.e2ee_ap_v2/vpn'),
          null,
        );
        await vpn.disposeForTest();
      }
    });

    test('returns null when the channel is not wired (test stub path)',
        () async {
      final vpn = VpnService.forTesting();

      TestDefaultBinaryMessengerBinding
          .instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
        const MethodChannel('com.opene2ee.e2ee_ap_v2/vpn'),
        (MethodCall call) async => null,
      );

      try {
        final stats = await vpn.captureStats();
        // captureStats() catches all exceptions and
        // returns null on the test path (matches
        // getSampledPackets()'s no-throw behaviour).
        expect(stats, isNull);
      } finally {
        TestDefaultBinaryMessengerBinding
            .instance.defaultBinaryMessenger
            .setMockMethodCallHandler(
          const MethodChannel('com.opene2ee.e2ee_ap_v2/vpn'),
          null,
        );
        await vpn.disposeForTest();
      }
    });
  });

  group('Sprint 22.10 — VpnService.packetStream is broadcast', () {
    test('two subscribers both receive the same onPacketsSampled batch',
        () async {
      final vpn = VpnService.forTesting();

      final mockedBatch = <Map<String, Object?>>[
        {
          'version': 4,
          'protocol': 'tcp',
          'protocolNumber': 6,
          'packetLength': 64,
          'srcIpMasked': '10.0.0.0',
          'dstIpMasked': '1.1.1.0',
          'srcPort': 12345,
          'dstPort': 443,
          'tcpFlags': 0x18,
        },
      ];

      TestDefaultBinaryMessengerBinding
          .instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
        const MethodChannel('com.opene2ee.e2ee_ap_v2/vpn'),
        (MethodCall call) async => null,
      );

      try {
        final firstSub = <List<SampledPacket>>[];
        final secondSub = <List<SampledPacket>>[];
        final s1 = vpn.packetStream.listen(firstSub.add);
        final s2 = vpn.packetStream.listen(secondSub.add);

        await Future<void>.delayed(Duration.zero);

        const channelName = 'com.opene2ee.e2ee_ap_v2/vpn';
        const codec = StandardMethodCodec();
        final encoded = codec.encodeMethodCall(
          MethodCall('onPacketsSampled', mockedBatch),
        );
        await TestDefaultBinaryMessengerBinding
            .instance.defaultBinaryMessenger
            .handlePlatformMessage(
          channelName,
          encoded,
          (ByteData? reply) {},
        );

        await Future<void>.delayed(Duration.zero);
        expect(firstSub.length, 1,
            reason: 'First subscriber should receive the batch');
        expect(secondSub.length, 1,
            reason: 'Broadcast — second subscriber also '
                'receives the batch');
        expect(firstSub.first.length, 1);
        expect(firstSub.first.first.srcPort, 12345);
        expect(secondSub.first.first.dstPort, 443);

        await s1.cancel();
        await s2.cancel();
      } finally {
        TestDefaultBinaryMessengerBinding
            .instance.defaultBinaryMessenger
            .setMockMethodCallHandler(
          const MethodChannel('com.opene2ee.e2ee_ap_v2/vpn'),
          null,
        );
        await vpn.disposeForTest();
      }
    });
  });

  group('Sprint 22.10 — PoolNotifier reacts to packetStream push', () {
    test(
        'a single batch updates paketSayisi and paketGecmisi without waiting '
        'for the 5s poll', () async {
      // We deliberately use the VpnService singleton (matches
      // production wiring) and push to it via the binary
      // messenger — the same path the Kotlin drain coroutine
      // will use at runtime. The notifier subscribes in
      // `_start()` and bumps the counters reactively.
      const channelName = 'com.opene2ee.e2ee_ap_v2/vpn';
      TestDefaultBinaryMessengerBinding
          .instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
        const MethodChannel(channelName),
        (MethodCall call) async {
          // Return a deterministic no-peer matcher poll so
          // the 5s tick doesn't fight the test. P2PMatcher
          // uses http under the hood; we never get there
          // because the test asserts BEFORE the first tick.
          if (call.method == 'status') return 'DEAD';
          if (call.method == 'getSampledPackets') return <Object?>[];
          return null;
        },
      );

      final container = ProviderContainer();
      addTearDown(container.dispose);
      try {
        // Wait one microtask so the notifier's constructor
        // completes (the _start() subscription is set up
        // synchronously in the ctor, but the broadcast
        // controller's listener registration is async).
        await Future<void>.delayed(Duration.zero);
        final notifier = container.read(poolProvider.notifier);

        // Initial state — no packets.
        var state = container.read(poolProvider);
        expect(state.paketSayisi, 0);
        expect(state.paketGecmisi, isEmpty);

        // Push 2 batches of 3 packets each via the
        // onPacketsSampled event.
        const codec = StandardMethodCodec();
        Future<void> push(List<Map<String, Object?>> batch) async {
          final encoded = codec.encodeMethodCall(
            MethodCall('onPacketsSampled', batch),
          );
          await TestDefaultBinaryMessengerBinding
              .instance.defaultBinaryMessenger
              .handlePlatformMessage(
            channelName,
            encoded,
            (ByteData? reply) {},
          );
        }

        final firstBatch = List<Map<String, Object?>>.generate(
          3,
          (i) => {
            'version': 4,
            'protocol': 'tcp',
            'protocolNumber': 6,
            'packetLength': 100 + i,
            'srcIpMasked': '10.0.0.0',
            'dstIpMasked': '1.1.1.0',
            'srcPort': 50000 + i,
            'dstPort': 443,
            'tcpFlags': 0x18,
          },
        );
        final secondBatch = List<Map<String, Object?>>.generate(
          3,
          (i) => {
            'version': 4,
            'protocol': 'udp',
            'protocolNumber': 17,
            'packetLength': 64,
            'srcIpMasked': '10.0.0.0',
            'dstIpMasked': '8.8.8.0',
            'srcPort': 53,
            'dstPort': 53,
          },
        );

        await push(firstBatch);
        await Future<void>.delayed(Duration.zero);
        state = container.read(poolProvider);
        expect(state.paketSayisi, 3,
            reason: 'After first batch of 3, paketSayisi=3');
        expect(state.paketGecmisi.length, 1);
        expect(state.paketGecmisi.last, 3);

        await push(secondBatch);
        await Future<void>.delayed(Duration.zero);
        state = container.read(poolProvider);
        expect(state.paketSayisi, 6,
            reason: 'After second batch of 3, paketSayisi=6');
        expect(state.paketGecmisi.length, 2);
        expect(state.paketGecmisi.last, 3);
        expect(state.paketGecmisi.first, 3);

        // The notifier is wired to the same singleton; we
        // never invoke methods on it directly. The
        // assertion above proves the subscription path
        // works end-to-end.
        expect(notifier, isNotNull);
      } finally {
        TestDefaultBinaryMessengerBinding
            .instance.defaultBinaryMessenger
            .setMockMethodCallHandler(
          const MethodChannel(channelName),
          null,
        );
      }
    });
  });
}
