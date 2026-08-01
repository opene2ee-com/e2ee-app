// test/sprint110d_handler_test.dart
//
// Sprint 22 + 22.10 — runtime handler-registration probe.
//
// Regression test for the OnePlus 9 Pro error
// `MissingPluginException(No implementation found for method
// getSampledPackets on channel com.opene2ee.e2ee_ap_v2/vpn)`.
//
// Sprint 22.10 changes:
//   - `getSampledPackets()` now returns `List<SampledPacket>`
//     (typed, parsed). The test asserts on the typed fields
//     instead of the raw map keys.
//
// What this test verifies (and what it does NOT):
//   ✓ Dart-side contract: `VpnService.instance` constructs the
//     same `com.opene2ee.e2ee_ap_v2/vpn` MethodChannel the
//     Kotlin MainActivity owns, and `getSampledPackets()`
//     actually calls `invokeMethod` on that channel.
//   ✓ The mock handler is invoked with the literal method name
//     `getSampledPackets` and returns a controlled payload that
//     the Dart side successfully deserialises into
//     `SampledPacket` instances.
//   ✓ Sprint 22.10+ — `VpnService.packetStream` correctly
//     receives the `onPacketsSampled` MethodChannel event
//     payload as a `List<SampledPacket>`.
//   ✗ The Kotlin-side handler being present at runtime is NOT
//     directly testable from the Dart unit-test harness — that
//     requires the Android emulator + a real wirebare
//     SimpleWireBareProxyService instance, which is out of scope
//     for `flutter test`.

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:e2ee_ap_v2/services/packet_parser.dart';
import 'package:e2ee_ap_v2/services/vpn_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Sprint 22 + 22.10 — com.opene2ee.e2ee_ap_v2/vpn MethodChannel probe',
      () {
    test(
      'getSampledPackets() invokes the channel with method name '
      '`getSampledPackets` and deserialises the mocked response '
      'into SampledPacket instances '
      '(regression: OnePlus 9 Pro `MissingPluginException`)',
      () async {
        final vpn = VpnService.forTesting();

        var invokeCount = 0;
        String? lastMethod;
        final mockedPackets = <Map<String, Object?>>[
          {
            'version': 4,
            'protocol': 'tcp',
            'protocolNumber': 6,
            'packetLength': 1500,
            'srcIpMasked': '203.0.113.0',
            'dstIpMasked': '198.51.100.0',
            'srcPort': 54321,
            'dstPort': 443,
            'tcpFlags': 0x18,
          },
        ];

        TestDefaultBinaryMessengerBinding
            .instance.defaultBinaryMessenger
            .setMockMethodCallHandler(
          const MethodChannel('com.opene2ee.e2ee_ap_v2/vpn'),
          (MethodCall call) async {
            invokeCount += 1;
            lastMethod = call.method;
            if (call.method == 'getSampledPackets') {
              return mockedPackets;
            }
            if (call.method == 'status') {
              return 'DEAD';
            }
            if (call.method == 'startVpn' || call.method == 'stopVpn') {
              return null;
            }
            return null;
          },
        );

        try {
          final packets = await vpn.getSampledPackets();

          // 1. The mock handler was invoked exactly once for
          //    `getSampledPackets`.
          expect(invokeCount, 1,
              reason:
                  'getSampledPackets() must invoke the platform '
                  'channel exactly once. invokeCount=$invokeCount '
                  'lastMethod=$lastMethod.');
          expect(lastMethod, 'getSampledPackets',
              reason:
                  'The Dart-side call must use the literal method '
                  'name `getSampledPackets` that the Kotlin side '
                  'routes. lastMethod=$lastMethod.');

          // 2. The response is deserialised into SampledPacket
          //    instances (Sprint 22.10 typed return).
          expect(packets, isA<List<SampledPacket>>());
          expect(packets, isNotEmpty);
          expect(packets.length, 1);
          expect(packets.first.srcPort, 54321);
          expect(packets.first.dstPort, 443);
          expect(packets.first.protocol, 'tcp');
          expect(packets.first.protocolNumber, 6);
          expect(packets.first.packetLength, 1500);
          expect(packets.first.srcIpMasked, '203.0.113.0');
          expect(packets.first.dstIpMasked, '198.51.100.0');
          expect(packets.first.tcpFlags, 0x18);

          // 3. status() reuses the same channel.
          final status = await vpn.status();
          expect(invokeCount, 2,
              reason: 'status() should fire a SECOND invoke on '
                  'the same channel.');
          expect(status, 'DEAD');
        } finally {
          TestDefaultBinaryMessengerBinding
              .instance.defaultBinaryMessenger
              .setMockMethodCallHandler(
            const MethodChannel('com.opene2ee.e2ee_ap_v2/vpn'),
            null,
          );
          await vpn.disposeForTest();
        }
      },
    );

    test(
      'packetStream receives the onPacketsSampled MethodChannel '
      'event as a List<SampledPacket> '
      '(Sprint 22.10 — push-based packet observation)',
      () async {
        final vpn = VpnService.forTesting();

        final mockedBatch = <Map<String, Object?>>[
          {
            'version': 4,
            'protocol': 'udp',
            'protocolNumber': 17,
            'packetLength': 256,
            'srcIpMasked': '10.0.0.0',
            'dstIpMasked': '8.8.8.0',
            'srcPort': 5353,
            'dstPort': 53,
          },
          {
            'version': 6,
            'protocol': 'tcp',
            'protocolNumber': 6,
            'packetLength': 1280,
            'srcIpMasked': '2001:db8:0:0:0:0:0:0',
            'dstIpMasked': '2606:4700:0:0:0:0:0:0',
            'srcPort': 44444,
            'dstPort': 443,
            'tcpFlags': 0x10,
          },
        ];

        TestDefaultBinaryMessengerBinding
            .instance.defaultBinaryMessenger
            .setMockMethodCallHandler(
          const MethodChannel('com.opene2ee.e2ee_ap_v2/vpn'),
          (MethodCall call) async {
            // Sprint 22.10 — Kotlin pushes this event to Dart.
            if (call.method == 'onPacketsSampled') {
              return null;
            }
            return null;
          },
        );

        try {
          // `packetStream` emits a `List<SampledPacket>` (one
          // batch per `onPacketsSampled` event), so the
          // collected list is `List<List<SampledPacket>>`.
          final emittedBatches = <List<SampledPacket>>[];
          final sub = vpn.packetStream.listen(emittedBatches.add);

          // Allow the stream subscription to register.
          await Future<void>.delayed(Duration.zero);

          // Simulate the Kotlin `onPacketsSampled` push.
          // `setMethodCallHandler` listens on the RECEIVE
          // side (platform → Dart); `invokeMethod` sends on
          // the SEND side (Dart → platform). To mimic a
          // platform push we have to use the lower-level
          // `handlePlatformMessage` with the binary
          // messenger, encoding the MethodCall ourselves.
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

          // The stream should have received the batch.
          await Future<void>.delayed(Duration.zero);
          expect(emittedBatches, isNotEmpty,
              reason: 'packetStream should receive the '
                  'onPacketsSampled event');
          expect(emittedBatches.length, 1,
              reason: 'Exactly one batch should be emitted');
          final batch = emittedBatches.first;
          expect(batch.length, 2,
              reason: 'Batch should contain both packets');
          expect(batch[0].protocol, 'udp');
          expect(batch[0].srcPort, 5353);
          expect(batch[0].dstPort, 53);
          expect(batch[0].packetLength, 256);
          expect(batch[1].protocol, 'tcp');
          expect(batch[1].version, 6);
          expect(batch[1].srcPort, 44444);
          expect(batch[1].tcpFlags, 0x10);

          await sub.cancel();
        } finally {
          TestDefaultBinaryMessengerBinding
              .instance.defaultBinaryMessenger
              .setMockMethodCallHandler(
            const MethodChannel('com.opene2ee.e2ee_ap_v2/vpn'),
            null,
          );
          await vpn.disposeForTest();
        }
      },
    );
  });
}
