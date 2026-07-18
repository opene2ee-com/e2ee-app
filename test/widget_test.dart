// Minimal smoke test for e2ee_ap_v2 VpnApp.
// Sprint 21: VpnApp has VpnTogglePage with a single on/off button.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:e2ee_ap_v2/main.dart';

void main() {
  testWidgets('VpnApp builds and shows toggle button', (WidgetTester tester) async {
    await tester.pumpWidget(const VpnApp());
    await tester.pump();

    // AppBar title present
    expect(find.text('e2ee-ap-v2 VPN'), findsWidgets);
    // Default state: button label is "VPN AÇ"
    expect(find.text('VPN AÇ'), findsOneWidget);
  });
}
