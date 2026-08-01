// lib/screens/active_pool_screen.dart
//
// Sprint 22.8b — Aktif Nöbet (Active Pool) screen.
//
// Composes the 5-second polling tick from `poolProvider`
// (Sprint 22.3) with the wirebare VPN state (Sprint 22.2)
// and renders:
//
//   - AppBar with version + settings
//   - "Alıcı Ol" toggle (drives `PoolNotifier.toggleAlici`)
//   - 3-stat grid: İzlenen Paket / Bağlı Gönüllü / Test Edilenler
//   - fl_chart mini-chart of the last 10 packet deltas
//   - "Şifreleme Doğrulamayı Başlat" + "Oturumu Bitir" buttons
//   - Debug captions: API çağrı sayısı, last error / success
//
// Sprint 22.10+ will hook the wirebare-kernel
// `IImportantEventListener` push so the packet counter
// receives real data. Until then, the counter stays at 0
// (the `VpnService.getSampledPackets` stub returns `[]`).
//
// S25 invariant: no "VPN" framing in the UI — the Ağ Güvenliği
// Aracı copy replaces it.

import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../config.dart';
import '../state/pool_provider.dart';
import '../theme/app_theme.dart';
import '../widgets/stat_pill.dart';

class ActivePoolScreen extends ConsumerStatefulWidget {
  const ActivePoolScreen({super.key});

  @override
  ConsumerState<ActivePoolScreen> createState() => _ActivePoolScreenState();
}

class _ActivePoolScreenState extends ConsumerState<ActivePoolScreen>
    with SingleTickerProviderStateMixin {
  late final AnimationController _pulseController;

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1500),
    )..repeat(reverse: true);
  }

  @override
  void dispose() {
    _pulseController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final pool = ref.watch(poolProvider);
    final notifier = ref.read(poolProvider.notifier);
    final isAlici = pool.isAlici;
    final paketGecmisi = pool.paketGecmisi;

    return Scaffold(
      backgroundColor: AppTheme.bg,
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => context.go('/home/gorevler'),
        ),
        title: const Text('Aktif Nöbet'),
        actions: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Center(
              child: Text(
                'v${AppConfig.versionName}',
                style: const TextStyle(
                  fontSize: 11,
                  color: AppTheme.muted,
                ),
              ),
            ),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          // Alıcı Ol toggle card.
          Card(
            child: Padding(
              padding: const EdgeInsets.all(20),
              child: Row(
                children: [
                  AnimatedBuilder(
                    animation: _pulseController,
                    builder: (context, _) {
                      return Container(
                        width: 56,
                        height: 56,
                        decoration: BoxDecoration(
                          color: isAlici
                              ? AppTheme.accent.withValues(
                                  alpha: 0.15 + _pulseController.value * 0.10)
                              : AppTheme.muted.withValues(alpha: 0.10),
                          borderRadius: BorderRadius.circular(14),
                        ),
                        child: Icon(
                          isAlici ? Icons.shield : Icons.shield_outlined,
                          color: isAlici ? AppTheme.accent : AppTheme.muted,
                          size: 28,
                        ),
                      );
                    },
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Aktif Nöbet Modu',
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w600,
                            color: AppTheme.text,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          isAlici
                              ? 'AKTİF — paketler toplanıyor'
                              : 'Kapalı — bekliyor',
                          style: TextStyle(
                            fontSize: 12,
                            color: isAlici ? AppTheme.accent : AppTheme.muted,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                  ),
                  Switch(
                    value: isAlici,
                    activeThumbColor: AppTheme.accent,
                    onChanged: (_) => notifier.toggleAlici(),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),

          // 3-stat grid.
          Row(
            children: [
              Expanded(
                child: _StatCard(
                  title: 'İzlenen Paket',
                  value: pool.paketSayisi.toString(),
                  icon: Icons.swap_vert,
                  color: AppTheme.primary,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _StatCard(
                  title: 'Bağlı Gönüllü',
                  value: pool.gonulluSayisi.toString(),
                  icon: Icons.people,
                  color: AppTheme.accent,
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _StatCard(
                  title: 'Test Edilenler',
                  value: pool.testEdilenler.isEmpty
                      ? '—'
                      : pool.testEdilenler.length.toString(),
                  icon: Icons.check_circle_outline,
                  color: AppTheme.whatsapp,
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),

          // Packet-delta chart.
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Paket Akışı (son 10 tick)',
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: AppTheme.muted,
                    ),
                  ),
                  const SizedBox(height: 8),
                  SizedBox(
                    height: 120,
                    child: paketGecmisi.length < 2
                        ? const Center(
                            child: Text(
                              'Henüz veri yok — ilk tick bekleniyor',
                              style: TextStyle(
                                fontSize: 12,
                                color: AppTheme.muted,
                              ),
                            ),
                          )
                        : LineChart(
                            LineChartData(
                              gridData: const FlGridData(show: false),
                              titlesData: const FlTitlesData(show: false),
                              borderData: FlBorderData(show: false),
                              lineBarsData: [
                                LineChartBarData(
                                  spots: [
                                    for (var i = 0;
                                        i < paketGecmisi.length;
                                        i++)
                                      FlSpot(
                                          i.toDouble(),
                                          paketGecmisi[i].toDouble()),
                                  ],
                                  isCurved: true,
                                  color: AppTheme.primary,
                                  barWidth: 2,
                                  dotData: const FlDotData(show: false),
                                ),
                              ],
                            ),
                          ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),

          // Debug captions.
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Debug',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: AppTheme.muted,
                      letterSpacing: 0.8,
                    ),
                  ),
                  const SizedBox(height: 6),
                  _debugLine(
                      'API çağrı sayısı: ${pool.apiCallCount}'),
                  _debugLine(pool.lastSuccess ?? 'son başarı: yok'),
                  _debugLine(
                    pool.lastError == null
                        ? 'son hata: yok'
                        : 'son hata: ${pool.lastError}',
                    color: pool.lastError == null
                        ? AppTheme.muted
                        : AppTheme.danger,
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 24),
        ],
      ),
    );
  }

  Widget _debugLine(String text, {Color? color}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 1),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 11,
          color: color ?? AppTheme.muted,
        ),
      ),
    );
  }
}

class _StatCard extends StatelessWidget {
  const _StatCard({
    required this.title,
    required this.value,
    required this.icon,
    required this.color,
  });

  final String title;
  final String value;
  final IconData icon;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(icon, size: 18, color: color),
            const SizedBox(height: 8),
            Text(
              value,
              style: const TextStyle(
                fontSize: 22,
                fontWeight: FontWeight.w700,
                color: AppTheme.text,
              ),
            ),
            const SizedBox(height: 2),
            Text(
              title,
              style: const TextStyle(
                fontSize: 11,
                color: AppTheme.muted,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// Keep `StatPillColor` reachable from the import — referenced
// by `stat_pill.dart` re-exports; the active pool screen does
// not use it directly, but the import is kept so the
// `widgets/stat_pill.dart` lint pass stays clean.
// ignore: unused_element
const StatPillColor _kKeepImport = StatPillColor.accent;
