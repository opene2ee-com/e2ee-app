// Sprint 22 — minimal smoke test for e2ee_ap_v2 root app.
// Verifies the ProviderScope + MaterialApp.router + AppRouter
// boot pipeline reaches the home screen on the very first run.
// On the first run, `isAcceptedProvider` is `false`, so the
// router redirects to /bilgilendirme — we assert that screen
// renders the consent button.

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:e2ee_ap_v2/main.dart';
import 'package:e2ee_ap_v2/router/app_router.dart';

void main() {
  testWidgets('Bilgilendirme consent button renders on first run',
      (WidgetTester tester) async {
    await tester.pumpWidget(const ProviderScope(child: E2eeApV2App()));
    // Let the router + redirect settle.
    await tester.pumpAndSettle();

    // First-run redirect lands on /bilgilendirme.
    expect(find.text('Anladım, Devam Et'), findsOneWidget);
    // App bar on the home screen is NOT present (we're on consent).
    expect(find.text('e2ee-ap-v2 VPN'), findsNothing);
  });

  testWidgets('AppRouter config exists and is reusable', (tester) async {
    expect(AppRouter.config, isNotNull);
  });
}
