// lib/screens/bilgilendirme_screen.dart
//
// Sprint 22 — placeholder for the consent / bilgilendirme screen.
//
// Sprint 22.6 ships a minimal version (hero + accept button). The
// real screen with the full "Ağ Güvenliği Aracı" copy + privacy
// guarantees + the deep-teal hero arrives in Sprint 22.8.

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../state/is_accepted_provider.dart';
import '../theme/app_theme.dart';

class BilgilendirmeScreen extends ConsumerWidget {
  const BilgilendirmeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      backgroundColor: AppTheme.surface,
      body: SafeArea(
        child: Column(
          children: [
            // Hero — gradient + shield + tag + heading.
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(vertical: 32, horizontal: 24),
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  colors: [AppTheme.primary, AppTheme.primaryDark],
                ),
              ),
              child: Column(
                children: [
                  Container(
                    width: 64,
                    height: 64,
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.15),
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: const Icon(
                      Icons.shield_outlined,
                      size: 32,
                      color: Colors.white,
                    ),
                  ),
                  const SizedBox(height: 16),
                  const Text(
                    'AĞ GÜVENLİĞİ ARACI',
                    style: TextStyle(
                      fontSize: 12,
                      color: Colors.white,
                      letterSpacing: 1.5,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 8),
                  const Text(
                    'Cihazındaki ağ trafiğini\nkontrol altına al',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 22,
                      color: Colors.white,
                      fontWeight: FontWeight.w700,
                      height: 1.3,
                    ),
                  ),
                ],
              ),
            ),

            // Body — privacy guarantee cards + accept button.
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const _PrivacyRow(
                      icon: Icons.lock_outline,
                      title: 'Şifreleme bütünlüğü testi',
                      subtitle:
                          'Cihazdan çıkan paketlerin şifreleme başlıklarını doğrularız. İçerik kaydedilmez.',
                    ),
                    const SizedBox(height: 12),
                    const _PrivacyRow(
                      icon: Icons.phone_disabled_outlined,
                      title: 'Telefon numarası veya kişi listen YOK',
                      subtitle:
                          'IMEI, MSISDN, rehber veya konum bilgisi toplanmaz, gönderilmez.',
                    ),
                    const SizedBox(height: 12),
                    const _PrivacyRow(
                      icon: Icons.wifi_tethering_off,
                      title: 'Ham IP adresin maskelenir',
                      subtitle:
                          'IP adreslerin /24 (IPv4) veya /48 (IPv6) maskelenerek işlenir.',
                    ),
                    const SizedBox(height: 24),
                    ElevatedButton(
                      onPressed: () {
                        ref.read(isAcceptedProvider.notifier).state = true;
                        if (context.mounted) {
                          context.go('/home');
                        }
                      },
                      child: const Text('Anladım, Devam Et'),
                    ),
                    const SizedBox(height: 8),
                    Center(
                      child: Text(
                        'Sprint 22.6 — tam bilgilendirme metni Sprint 22.8 ile gelecek',
                        style: TextStyle(
                          fontSize: 11,
                          color: AppTheme.muted,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _PrivacyRow extends StatelessWidget {
  const _PrivacyRow({
    required this.icon,
    required this.title,
    required this.subtitle,
  });

  final IconData icon;
  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 40,
          height: 40,
          decoration: BoxDecoration(
            color: AppTheme.primary.withValues(alpha: 0.10),
            borderRadius: BorderRadius.circular(10),
          ),
          child: Icon(icon, color: AppTheme.primary, size: 22),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                title,
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: AppTheme.text,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                subtitle,
                style: const TextStyle(
                  fontSize: 13,
                  color: AppTheme.muted,
                  height: 1.4,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
