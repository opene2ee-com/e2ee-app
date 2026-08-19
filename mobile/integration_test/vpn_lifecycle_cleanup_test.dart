import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:opene2ee/services/vpn_service.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('stopping the VPN clears sampled metadata and reaches DEAD', (
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
    await _waitForStatus(vpn, 'ACTIVE');
    await vpn.start();
    await _waitForStatus(vpn, 'ACTIVE');

    socket = await RawDatagramSocket.bind(InternetAddress.anyIPv4, 0);
    await _fillRing(vpn, socket);
    expect(await vpn.getSampledPackets(), isNotEmpty);
    await _fillRing(vpn, socket);

    await vpn.stop();
    await vpn.stop();
    await _waitForStatus(vpn, 'DEAD');

    expect(await vpn.getSampledPackets(), isEmpty);
    // The external runner uses this bounded window to inspect tun0, the
    // foreground service, and the dispatcher thread through adb.
    // ignore: avoid_print
    print('VPN_CLEANUP_READY');
    await Future<void>.delayed(const Duration(seconds: 5));
  });
}

Future<void> _fillRing(VpnService vpn, RawDatagramSocket socket) async {
  final destination = InternetAddress('198.51.100.42');
  for (var attempt = 0; attempt < 10; attempt += 1) {
    for (var index = 0; index < 20; index += 1) {
      socket.send(const <int>[0x4f, 0x45, 0x05], destination, 53054);
    }
    await Future<void>.delayed(const Duration(milliseconds: 100));
    final samples = await vpn.getSampledPackets();
    if (samples.any((sample) => sample['dstPort'] == 53054)) {
      for (var refill = 0; refill < 20; refill += 1) {
        socket.send(const <int>[0x4f, 0x45, 0x05], destination, 53054);
      }
      await Future<void>.delayed(const Duration(milliseconds: 100));
      return;
    }
  }
  fail('controlled UDP traffic did not reach the metadata ring');
}

Future<void> _waitForStatus(VpnService vpn, String expected) async {
  for (var attempt = 0; attempt < 50; attempt += 1) {
    final status = await vpn.status();
    if (status['status'] == expected) return;
    await Future<void>.delayed(const Duration(milliseconds: 100));
  }
  fail('VPN did not reach $expected within 5 seconds');
}
