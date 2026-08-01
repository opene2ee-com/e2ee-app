// lib/main.dart
//
// Sprint 22 — e2ee-ap-v2 root app.
//
// Wires `ProviderScope` (Riverpod) at the root and `MaterialApp.router`
// (go_router) for declarative navigation. The `themeMode` is
// `system` so the OS-level dark mode switch is honored when we
// ship a real dark theme; for now both modes resolve to the
// light theme (Sprint 22.0).
//
// The VPN toggle UI moved out of main.dart and into the
// `HomeScreen` widget (Sprint 22.6) — the bottom nav lives there,
// not in the root. The root only sets up routing + theming.

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'router/app_router.dart';
import 'theme/app_theme.dart';

void main() {
  runApp(const ProviderScope(child: E2eeApV2App()));
}

class E2eeApV2App extends StatelessWidget {
  const E2eeApV2App({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: 'e2ee-ap-v2 VPN',
      theme: AppTheme.light(),
      darkTheme: AppTheme.dark(),
      themeMode: ThemeMode.system,
      routerConfig: AppRouter.config,
      debugShowCheckedModeBanner: false,
    );
  }
}
