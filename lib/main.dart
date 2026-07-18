import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const VpnApp());

class VpnApp extends StatelessWidget {
  const VpnApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'e2ee-ap-v2 VPN',
      theme: ThemeData(primarySwatch: Colors.blue),
      home: const VpnTogglePage(),
    );
  }
}

class VpnTogglePage extends StatefulWidget {
  const VpnTogglePage({super.key});

  @override
  State<VpnTogglePage> createState() => _VpnTogglePageState();
}

class _VpnTogglePageState extends State<VpnTogglePage> {
  static const platform = MethodChannel('com.opene2ee.e2ee_ap_v2/vpn');
  bool _vpnRunning = false;
  String _lastResult = '';

  Future<void> _toggleVpn() async {
    try {
      if (!_vpnRunning) {
        final result = await platform.invokeMethod<String>('startVpn');
        setState(() {
          _vpnRunning = true;
          _lastResult = 'start -> $result';
        });
      } else {
        final result = await platform.invokeMethod<String>('stopVpn');
        setState(() {
          _vpnRunning = false;
          _lastResult = 'stop -> $result';
        });
      }
    } on PlatformException catch (e) {
      setState(() {
        _lastResult = 'error: ${e.code} ${e.message}';
      });
      debugPrint('VPN toggle error: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('e2ee-ap-v2 VPN')),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            ElevatedButton(
              onPressed: _toggleVpn,
              child: Text(_vpnRunning ? 'VPN KAPAT' : 'VPN AÇ'),
            ),
            const SizedBox(height: 16),
            Text(
              'state: ${_vpnRunning ? "running" : "stopped"}',
              style: const TextStyle(fontSize: 14),
            ),
            const SizedBox(height: 8),
            Text(
              _lastResult,
              style: const TextStyle(fontSize: 11, color: Colors.black54),
            ),
          ],
        ),
      ),
    );
  }
}
