// mobile/test/mobile/vpn/method_channel_test.dart
//
// PR-22b (Sprint 3) — Unit tests for the VPN MethodChannel bridge.
//
// Coverage matrix
// ---------------
// 1. start / stop / status round-trip via MethodChannelVpnBridge.
// 2. Per-app VPN: setAllowedApplications / setDisallowedApplications
//    forward bundle IDs to native (iOS 14+ NEAppRules).
// 3. Permission flow: ensurePermission() / isPermissionGranted()
//    round-trip through `opene2ee/vpn_permissions`.
// 4. Telemetry dispatch: native → Dart `onTelemetry` routes into the
//    broadcast stream.
// 5. Error dispatch: `onError` → `errors` stream.
// 6. NoopVpnBridge: throws VpnPermissionDeniedError on start()
//    (mirrors the production contract for unsupported platforms).
// 7. Privacy invariant: no payload field on VpnPacketMetadata.
// 8. VpnStatusSnapshot.fromMap round-trip with all state values
//    (idle / sampling / draining / stopped / error / unavailable).
// 9. iOS-specific: per-app VPN with reverse-DNS bundle IDs (e.g.
//    `com.opene2ee.app`).

import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:opene2ee/mobile/vpn/method_channel.dart';

/// Register a mock handler on the VPN MethodChannel. The tests use the
/// closure to inspect captured calls.
void _setVpnHandler(
  Future<dynamic> Function(MethodCall call) handler,
) {
  TestWidgetsFlutterBinding.ensureInitialized();
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(
        const MethodChannel(kVpnMethodChannel),
        handler,
      );
}

void _setPermHandler(
  Future<dynamic> Function(MethodCall call) handler,
) {
  TestWidgetsFlutterBinding.ensureInitialized();
  TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      .setMockMethodCallHandler(
        const MethodChannel(kVpnPermissionsChannel),
        handler,
      );
}

/// Push a synthetic MethodCall into [channel] FROM the platform side
/// (so the Dart MethodChannel handler dispatches it).
Future<void> _pushPlatformCall({
  required String channel,
  required String method,
  Map<Object?, Object?>? args,
}) async {
  const codec = StandardMethodCodec();
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  await messenger.handlePlatformMessage(
    channel,
    codec.encodeMethodCall(MethodCall(method, args)),
    (_) {},
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  tearDown(() {
    TestWidgetsFlutterBinding.ensureInitialized();
    final messenger =
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(
      const MethodChannel(kVpnMethodChannel),
      null,
    );
    messenger.setMockMethodCallHandler(
      const MethodChannel(kVpnPermissionsChannel),
      null,
    );
  });

  group('MethodChannelVpnBridge.transport', () {
    test('start invokes the vpn channel with permission granted', () async {
      _setVpnHandler((call) async {
        if (call.method == 'start') {
          return {
            'state': 'sampling',
            'packetsObserved': 0,
            'ringSize': 0,
            'samplingCap': 10,
            'lastError': null,
          };
        }
        return null;
      });
      _setPermHandler((call) async => true);

      final bridge = MethodChannelVpnBridge();
      final snap = await bridge.start();
      expect(snap.state, VpnLifecycleState.sampling);
      expect(snap.samplingCap, 10);
      expect(snap.packetsObserved, 0);

      bridge.dispose();
    });

    test('stop invokes the vpn channel and forwards the response', () async {
      _setVpnHandler((call) async {
        if (call.method == 'stop') {
          return {
            'state': 'stopped',
            'packetsObserved': 5,
            'ringSize': 0,
            'samplingCap': 10,
            'lastError': null,
          };
        }
        return null;
      });
      _setPermHandler((call) async => true);

      final bridge = MethodChannelVpnBridge();
      final snap = await bridge.stop();
      expect(snap.state, VpnLifecycleState.stopped);
      expect(snap.packetsObserved, 5);

      bridge.dispose();
    });

    test('status returns the native snapshot with all fields populated',
        () async {
      _setVpnHandler((call) async {
        if (call.method == 'status') {
          return {
            'state': 'sampling',
            'packetsObserved': 7,
            'ringSize': 7,
            'samplingCap': 10,
            'lastError': null,
            'allowedApplications': ['com.opene2ee.app'],
            'disallowedApplications': null,
          };
        }
        return null;
      });
      _setPermHandler((call) async => true);

      final bridge = MethodChannelVpnBridge();
      final snap = await bridge.status();
      expect(snap.state, VpnLifecycleState.sampling);
      expect(snap.packetsObserved, 7);
      expect(snap.ringSize, 7);
      expect(snap.samplingCap, 10);
      expect(snap.allowedApplications, ['com.opene2ee.app']);
      expect(snap.disallowedApplications, isNull);

      bridge.dispose();
    });

    test('start without permission throws VpnPermissionDeniedError',
        () async {
      _setVpnHandler((call) async => null);
      _setPermHandler((call) async => false);

      final bridge = MethodChannelVpnBridge();
      expect(
        () => bridge.start(),
        throwsA(isA<VpnPermissionDeniedError>()),
      );

      bridge.dispose();
    });

    test('start with state=error from native surfaces error', () async {
      _setVpnHandler((call) async {
        if (call.method == 'start') {
          return {
            'state': 'error',
            'packetsObserved': 0,
            'ringSize': 0,
            'samplingCap': 10,
            'lastError': 'VPN_START_FAILED: setTunnelNetworkSettings invalid',
          };
        }
        return null;
      });
      _setPermHandler((call) async => true);

      final bridge = MethodChannelVpnBridge();
      final snap = await bridge.start();
      expect(snap.state, VpnLifecycleState.error);
      expect(snap.lastError, contains('setTunnelNetworkSettings'));

      bridge.dispose();
    });
  });

  group('MethodChannelVpnBridge.per-app VPN (iOS 14+)', () {
    test('setAllowedApplications forwards iOS bundle IDs to native',
        () async {
      final captured = <MethodCall>[];
      _setVpnHandler((call) async {
        captured.add(call);
        return true;
      });
      _setPermHandler((call) async => true);

      final bridge = MethodChannelVpnBridge();
      await bridge.setAllowedApplications(const <String>[
        'com.opene2ee.app',
        'com.example.a',
      ]);

      final capturedCall = captured.firstWhere((c) => c.method == 'setAllowedApplications');
      expect(capturedCall.arguments, isA<Map>());
      final args = capturedCall.arguments as Map;
      expect(args['packages'], ['com.opene2ee.app', 'com.example.a']);

      bridge.dispose();
    });

    test('setDisallowedApplications completes without throwing', () async {
      _setVpnHandler((call) async => true);
      _setPermHandler((call) async => true);

      final bridge = MethodChannelVpnBridge();
      await bridge.setDisallowedApplications(const <String>[
        'com.example.bypass',
      ]);

      bridge.dispose();
    });

    test('empty per-app list is accepted (clears any prior allowlist)',
        () async {
      _setVpnHandler((call) async => true);
      _setPermHandler((call) async => true);

      final bridge = MethodChannelVpnBridge();
      await bridge.setAllowedApplications(const <String>[]);
      await bridge.setDisallowedApplications(const <String>[]);

      bridge.dispose();
    });
  });

  group('MethodChannelVpnBridge.permission flow', () {
    test('ensurePermission returns true when the activity grants consent',
        () async {
      _setVpnHandler((call) async => null);
      _setPermHandler((call) async {
        if (call.method == 'requestVpnPermission') return true;
        return null;
      });

      final bridge = MethodChannelVpnBridge();
      expect(await bridge.ensurePermission(), isTrue);

      bridge.dispose();
    });

    test('ensurePermission returns false when the user declines', () async {
      _setVpnHandler((call) async => null);
      _setPermHandler((call) async {
        if (call.method == 'requestVpnPermission') return false;
        return null;
      });

      final bridge = MethodChannelVpnBridge();
      expect(await bridge.ensurePermission(), isFalse);

      bridge.dispose();
    });

    test('isPermissionGranted returns the cached answer', () async {
      _setVpnHandler((call) async => null);
      _setPermHandler((call) async {
        if (call.method == 'isVpnPrepared') return true;
        return null;
      });

      final bridge = MethodChannelVpnBridge();
      expect(await bridge.isPermissionGranted(), isTrue);

      bridge.dispose();
    });
  });

  group('MethodChannelVpnBridge.telemetry dispatch', () {
    test('onTelemetry from native routes into the broadcast stream',
        () async {
      _setVpnHandler((call) async => null);
      _setPermHandler((call) async => true);

      final bridge = MethodChannelVpnBridge();
      final completer = Completer<VpnTelemetry>();
      final sub = bridge.telemetry.listen(completer.complete);

      await _pushPlatformCall(
        channel: kVpnMethodChannel,
        method: 'onTelemetry',
        args: {
          'sessionId': 'test-1',
          'packets': [
            {
              'version': 4,
              'protocol': 6,
              'packetLength': 60,
              'srcIpMasked': '10.0.0.0',
              'dstIpMasked': '142.250.0.0',
              'srcPort': 54321,
              'dstPort': 443,
              'tcpFlags': 24,
              'tlsClientHelloFingerprint': 'abcd',
            },
          ],
          'capturedAt': 1700000000000,
        },
      );

      final telemetry =
          await completer.future.timeout(const Duration(seconds: 2));
      expect(telemetry.sessionId, 'test-1');
      expect(telemetry.packets, hasLength(1));
      final packet = telemetry.packets.single;
      expect(packet.version, 4);
      expect(packet.protocol, 6);
      expect(packet.srcIpMasked, '10.0.0.0');
      expect(packet.dstPort, 443);

      await sub.cancel();
      bridge.dispose();
    });

    test('onError routes into the errors stream', () async {
      _setVpnHandler((call) async => null);
      _setPermHandler((call) async => true);

      final bridge = MethodChannelVpnBridge();
      final completer = Completer<VpnBridgeError>();
      final sub = bridge.errors.listen(completer.complete);

      await _pushPlatformCall(
        channel: kVpnMethodChannel,
        method: 'onError',
        args: {
          'code': 'NEProviderError',
          'message': 'configuration invalid',
        },
      );

      final err =
          await completer.future.timeout(const Duration(seconds: 2));
      expect(err.code, 'NEProviderError');
      expect(err.message, 'configuration invalid');

      await sub.cancel();
      bridge.dispose();
    });

    test('multiple listeners all receive telemetry events', () async {
      _setVpnHandler((call) async => null);
      _setPermHandler((call) async => true);

      final bridge = MethodChannelVpnBridge();
      final c1 = Completer<VpnTelemetry>();
      final c2 = Completer<VpnTelemetry>();
      final s1 = bridge.telemetry.listen(c1.complete);
      final s2 = bridge.telemetry.listen(c2.complete);

      await _pushPlatformCall(
        channel: kVpnMethodChannel,
        method: 'onTelemetry',
        args: {
          'sessionId': 'fan-out-1',
          'packets': <Map<Object?, Object?>>[],
          'capturedAt': 1700000000001,
        },
      );

      final t1 = await c1.future.timeout(const Duration(seconds: 2));
      final t2 = await c2.future.timeout(const Duration(seconds: 2));
      expect(t1.sessionId, 'fan-out-1');
      expect(t2.sessionId, 'fan-out-1');

      await s1.cancel();
      await s2.cancel();
      bridge.dispose();
    });
  });

  group('NoopVpnBridge', () {
    test('status returns the unavailable sentinel', () async {
      const bridge = NoopVpnBridge();
      final snap = await bridge.status();
      expect(snap.state, VpnLifecycleState.unavailable);
      expect(snap.packetsObserved, 0);
      expect(snap.samplingCap, 0);
    });

    test('start throws VpnPermissionDeniedError (no permission on web)',
        () async {
      const bridge = NoopVpnBridge();
      expect(
        () => bridge.start(),
        throwsA(isA<VpnPermissionDeniedError>()),
      );
    });

    test('stop returns the unavailable sentinel', () async {
      const bridge = NoopVpnBridge();
      final snap = await bridge.stop();
      expect(snap.state, VpnLifecycleState.unavailable);
    });

    test('per-app methods are silent no-ops', () async {
      const bridge = NoopVpnBridge();
      await bridge.setAllowedApplications(const ['a', 'b']);
      await bridge.setDisallowedApplications(const ['c']);
      expect(await bridge.ensurePermission(), isFalse);
      expect(await bridge.isPermissionGranted(), isFalse);
    });

    test('telemetry + errors streams are empty', () async {
      const bridge = NoopVpnBridge();
      expect(bridge.telemetry, isA<Stream<VpnTelemetry>>());
      expect(bridge.errors, isA<Stream<VpnBridgeError>>());
      // Empty streams complete immediately on listen; just verify they
      // do not hang.
      await bridge.telemetry.toList();
      await bridge.errors.toList();
    });
  });

  group('Privacy invariants (ADR-0006)', () {
    test('VpnPacketMetadata has no payload field', () {
      const m = VpnPacketMetadata(
        version: 4,
        protocol: 6,
        packetLength: 60,
      );
      expect(m.toString().toLowerCase(), isNot(contains('payload')),
          reason: 'VpnPacketMetadata.toString must not mention payload');
    });

    test('VpnTelemetry does not propagate any payload-shaped fields', () {
      final t = VpnTelemetry.fromMap({
        'sessionId': 's',
        'packets': <Map<Object?, Object?>>[
          {
            'version': 4,
            'protocol': 6,
            'packetLength': 90,
            'srcIpMasked': '10.0.0.0',
            'dstIpMasked': null,
            'srcPort': null,
            'dstPort': null,
            'tcpFlags': null,
            'tlsClientHelloFingerprint': null,
          },
        ],
        'capturedAt': 1700000000000,
      });
      expect(t.packets.single.srcIpMasked, '10.0.0.0');
      expect(t.toString().toLowerCase(), isNot(contains('payload')));
    });
  });

  group('VpnStatusSnapshot.fromMap round-trip', () {
    test('parses all known state values', () {
      final expected = <String, VpnLifecycleState>{
        'idle': VpnLifecycleState.idle,
        'sampling': VpnLifecycleState.sampling,
        'draining': VpnLifecycleState.draining,
        'stopped': VpnLifecycleState.stopped,
        'error': VpnLifecycleState.error,
      };
      expected.forEach((raw, expectedState) {
        final snap = VpnStatusSnapshot.fromMap({
          'state': raw,
          'packetsObserved': 0,
          'ringSize': 0,
          'samplingCap': 10,
        });
        expect(snap.state, expectedState,
            reason: 'state $raw should map to $expectedState');
      });
    });

    test('unknown state falls back to idle', () {
      final snap = VpnStatusSnapshot.fromMap({
        'state': 'weird',
        'packetsObserved': 0,
        'ringSize': 0,
        'samplingCap': 10,
      });
      expect(snap.state, VpnLifecycleState.idle);
    });

    test('unavailable sentinel is consistent', () {
      const a = VpnStatusSnapshot.unavailable;
      const b = VpnStatusSnapshot.unavailable;
      expect(identical(a, b), isTrue);
      expect(a.state, VpnLifecycleState.unavailable);
    });

    test('omitted allowedApplications is null', () {
      final snap = VpnStatusSnapshot.fromMap({
        'state': 'idle',
        'packetsObserved': 0,
        'ringSize': 0,
        'samplingCap': 10,
      });
      expect(snap.allowedApplications, isNull);
      expect(snap.disallowedApplications, isNull);
      expect(snap.lastError, isNull);
    });
  });
}
