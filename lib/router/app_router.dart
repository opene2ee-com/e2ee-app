// lib/router/app_router.dart
//
// Sprint 22 — go_router config.
//
// Routes:
//   /                                  → redirect to /bilgilendirme or /home
//                                       (driven by isAcceptedProvider)
//   /bilgilendirme                     → BilgilendirmeScreen (initial consent)
//   /home/gorevler                     → HomeScreen (Görevler tab + VPN toggle)
//   /home/gorevler/whatsapp            → WhatsAppTaskDetailScreen
//   /home                              → redirect to /home/gorevler
//   /home/aktif-nobet                  → ActivePoolScreen
//   /home/skorlar                      → SkorlarScreen

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../screens/active_pool_screen.dart';
import '../screens/bilgilendirme_screen.dart';
import '../screens/home_screen.dart';
import '../screens/skorlar_screen.dart';
import '../screens/whatsapp_task_detail_screen.dart';
import '../state/is_accepted_provider.dart';

class AppRouter {
  AppRouter._();

  static final GoRouter config = GoRouter(
    initialLocation: '/',
    redirect: (context, state) {
      final container = ProviderScope.containerOf(context);
      final accepted = container.read(isAcceptedProvider);
      final goingToBilgi = state.matchedLocation == '/bilgilendirme';
      if (!accepted && !goingToBilgi) {
        return '/bilgilendirme';
      }
      if (accepted && goingToBilgi) {
        return '/home/gorevler';
      }
      return null;
    },
    routes: [
      GoRoute(
        path: '/',
        redirect: (context, state) => '/bilgilendirme',
      ),
      GoRoute(
        path: '/bilgilendirme',
        builder: (context, state) => const BilgilendirmeScreen(),
      ),
      GoRoute(
        path: '/home/gorevler',
        builder: (context, state) => const HomeScreen(),
        routes: [
          GoRoute(
            path: 'whatsapp',
            builder: (context, state) => const WhatsAppTaskDetailScreen(),
          ),
        ],
      ),
      GoRoute(
        path: '/home',
        redirect: (context, state) => '/home/gorevler',
      ),
      GoRoute(
        path: '/home/aktif-nobet',
        builder: (context, state) => const ActivePoolScreen(),
      ),
      GoRoute(
        path: '/home/skorlar',
        builder: (context, state) => const SkorlarScreen(),
      ),
    ],
  );
}
