import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:opene2ee/services/vpn_service.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('real VPN traffic is drained as bounded privacy-safe metadata', (
    _,
  ) async {
    final vpn = VpnService.instance;
    RawDatagramSocket? socket;
    addTearDown(() async {
      socket?.close();
      await vpn.stop();
    });

    await vpn.getSampledPackets();
    expect(await vpn.requestAndStart(), isTrue);
    await _waitForActive(vpn);

    socket = await RawDatagramSocket.bind(InternetAddress.anyIPv4, 0);
    final dnsQuery = _dnsQueryFor('api-test.opene2ee.com');
    final samples = await _emitControlledTrafficAndDrain(vpn, socket, dnsQuery);
    expect(
      samples,
      hasLength(10),
      reason: 'the native ring must evict entries beyond its cap',
    );
    expect(
      samples,
      everyElement(
        allOf(
          containsPair('version', 'IPv4'),
          isNot(contains('payload')),
          isNot(contains('data')),
          allOf(
            isNot(contains('bytes')),
            isNot(contains('srcIp')),
            isNot(contains('dstIp')),
          ),
        ),
      ),
    );
    expect(
      samples,
      contains(
        allOf(
          containsPair('protocol', 'udp'),
          containsPair('dstPort', 53053),
          containsPair('dstIpMasked', '198.51.100.0'),
          containsPair('packetLength', 67),
        ),
      ),
      reason:
          'traffic emitted by this test must cross the native metadata ring',
    );
    expect(
      samples.every(
        (sample) =>
            sample['srcIpMasked'].toString().endsWith('.0') &&
            sample['dstIpMasked'].toString().endsWith('.0'),
      ),
      isTrue,
      reason: 'full IP addresses must not cross the MethodChannel',
    );
    expect(
      await vpn.getSampledPackets(),
      isEmpty,
      reason: 'drain must consume the ring atomically',
    );
  });
}

Future<List<Map<String, Object?>>> _emitControlledTrafficAndDrain(
  VpnService vpn,
  RawDatagramSocket socket,
  Uint8List payload,
) async {
  final destination = InternetAddress('198.51.100.42');
  for (var attempt = 0; attempt < 10; attempt += 1) {
    for (var index = 0; index < 20; index += 1) {
      expect(socket.send(payload, destination, 53053), payload.length);
    }
    await Future<void>.delayed(const Duration(milliseconds: 100));
    final samples = await vpn.getSampledPackets();
    if (samples.any(
      (sample) =>
          sample['protocol'] == 'udp' &&
          sample['dstPort'] == 53053 &&
          sample['dstIpMasked'] == '198.51.100.0',
    )) {
      return samples;
    }
  }
  fail('controlled UDP traffic did not reach getSampledPackets');
}

Future<void> _waitForActive(VpnService vpn) async {
  for (var attempt = 0; attempt < 50; attempt += 1) {
    final status = await vpn.status();
    if (status['status'] == 'ACTIVE') return;
    await Future<void>.delayed(const Duration(milliseconds: 100));
  }
  fail('VPN did not become ACTIVE within 5 seconds');
}

Uint8List _dnsQueryFor(String hostname) {
  final bytes = BytesBuilder(copy: false)
    ..add(const <int>[
      0x4f, 0x45, // transaction id
      0x01, 0x00, // recursion desired
      0x00, 0x01, // one question
      0x00, 0x00, // answers
      0x00, 0x00, // authority
      0x00, 0x00, // additional
    ]);
  for (final label in hostname.split('.')) {
    final encoded = label.codeUnits;
    bytes
      ..addByte(encoded.length)
      ..add(encoded);
  }
  bytes.add(const <int>[
    0x00,
    0x00, 0x01, // A record
    0x00, 0x01, // IN class
  ]);
  return bytes.takeBytes();
}
