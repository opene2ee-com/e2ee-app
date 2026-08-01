// lib/screens/home_screen.dart
//
// Sprint 22 — Home (Görevler) screen.
//
// The top-level screen reached after the user accepts the
// bilgilendirme. Composes:
//   - VPN toggle card (calls VpnService.instance.start/stop)
//   - Task list (TaskCard widget) — RCS + WhatsApp
//   - FAB → /home/aktif-nobet (the "Alıcı Ol (Nöbet)" pool view)
//   - BottomNavigationBar with 3 tabs: Görevler / Aktif Nöbet / Skorlar
//
// Sprint 22.8 ships the full Sprint 10.0 home composition; the
// VPN toggle card is the new addition (Sprint 21 + 22.6).

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../services/vpn_service.dart';
import '../state/tasks_provider.dart';
import '../theme/app_theme.dart';
import '../widgets/task_card.dart';

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final tasks = ref.watch(tasksListProvider);
    return Scaffold(
      backgroundColor: AppTheme.bg,
      appBar: AppBar(
        title: const Row(
          children: [
            Icon(Icons.shield_outlined, size: 22, color: AppTheme.primary),
            SizedBox(width: 8),
            Text('e2ee-ap-v2'),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings_outlined),
            onPressed: () {
              // Placeholder — settings is out of scope for Sprint 22.
            },
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 96),
        children: [
          const _VpnToggleCard(),
          const SizedBox(height: 16),
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 12),
            child: Text(
              'GÖREVLER',
              style: TextStyle(
                fontSize: 13,
                fontWeight: FontWeight.w600,
                color: AppTheme.muted,
                letterSpacing: 0.8,
              ),
            ),
          ),
          for (final task in tasks) ...[
            TaskCard(
              task: task,
              onStart: () => _onTaskStart(context, task.id),
            ),
            const SizedBox(height: 12),
          ],
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        backgroundColor: AppTheme.accent,
        foregroundColor: Colors.white,
        onPressed: () => context.go('/home/aktif-nobet'),
        icon: const Icon(Icons.person_add_alt_1),
        label: const Text('Alıcı Ol (Nöbet)'),
      ),
      floatingActionButtonLocation: FloatingActionButtonLocation.endFloat,
      bottomNavigationBar: const _BottomNav(activeIndex: 0),
    );
  }

  void _onTaskStart(BuildContext context, String taskId) {
    switch (taskId) {
      case 'whatsapp':
        context.go('/home/gorevler/whatsapp');
        break;
      case 'rcs':
      default:
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Sprint 22.8 — Sprint 23\'te gerçek RCS bağlantısı gelecek',
            ),
            duration: Duration(seconds: 3),
          ),
        );
        break;
    }
  }
}

class _BottomNav extends StatelessWidget {
  const _BottomNav({required this.activeIndex});

  final int activeIndex;

  @override
  Widget build(BuildContext context) {
    return BottomNavigationBar(
      currentIndex: activeIndex,
      onTap: (i) {
        switch (i) {
          case 0:
            context.go('/home/gorevler');
            break;
          case 1:
            context.go('/home/aktif-nobet');
            break;
          case 2:
            context.go('/home/skorlar');
            break;
        }
      },
      items: const [
        BottomNavigationBarItem(
          icon: Icon(Icons.task_alt_outlined),
          activeIcon: Icon(Icons.task_alt),
          label: 'Görevler',
        ),
        BottomNavigationBarItem(
          icon: Icon(Icons.people_outline),
          activeIcon: Icon(Icons.people),
          label: 'Aktif Nöbet',
        ),
        BottomNavigationBarItem(
          icon: Icon(Icons.bar_chart_outlined),
          activeIcon: Icon(Icons.bar_chart),
          label: 'Skorlar',
        ),
      ],
    );
  }
}

class _VpnToggleCard extends ConsumerStatefulWidget {
  const _VpnToggleCard();

  @override
  ConsumerState<_VpnToggleCard> createState() => _VpnToggleCardState();
}

class _VpnToggleCardState extends ConsumerState<_VpnToggleCard>
    with WidgetsBindingObserver, TickerProviderStateMixin {
  late final AnimationController _pulseController;
  bool _vpnRunning = false;
  String _lastResult = '';
  bool _busy = false;

  Future<void> _toggleVpn() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final vpn = VpnService.instance;
      if (!_vpnRunning) {
        await vpn.start();
        setState(() {
          _vpnRunning = true;
          _lastResult = 'started';
        });
      } else {
        await vpn.stop();
        setState(() {
          _vpnRunning = false;
          _lastResult = 'stopped';
        });
      }
    } on PlatformException catch (e) {
      setState(() {
        _lastResult = 'error: ${e.code} ${e.message}';
      });
      debugPrint('VPN toggle error: $e');
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  /// Query the Kotlin side for the current VPN status. Called
  /// on mount and on every `AppLifecycleState.resumed` tick
  /// (Sprint 22.3 — when the user backgrounds the app and
  /// comes back, the toggle should reflect the actual
  /// `ProxyStatus` instead of sticking at the last local
  /// state).
  Future<void> _refreshVpnState() async {
    if (!mounted) return;
    try {
      final status = await VpnService.instance.status();
      final running = status == 'ACTIVE' || status == 'STARTING';
      if (_vpnRunning != running) {
        setState(() {
          _vpnRunning = running;
        });
      }
    } catch (_) {
      // Ignore — keep the local state on status-query failure.
    }
  }

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1500),
    );
    WidgetsBinding.instance.addObserver(this);
    _refreshVpnState();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Re-query the Kotlin side on resume so the toggle
    // reflects the actual VPN status (the user might have
    // toggled it from system Settings, or the wirebare service
    // could have been torn down by the system in the
    // background).
    if (state == AppLifecycleState.resumed) {
      _refreshVpnState();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _pulseController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Container(
                  width: 48,
                  height: 48,
                  decoration: BoxDecoration(
                    color: _vpnRunning
                        ? AppTheme.primary.withValues(alpha: 0.15)
                        : AppTheme.muted.withValues(alpha: 0.10),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    _vpnRunning ? Icons.shield : Icons.shield_outlined,
                    color: _vpnRunning ? AppTheme.primary : AppTheme.muted,
                  ),
                ),
                const SizedBox(width: 12),
                const Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'wirebare VPN',
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      SizedBox(height: 2),
                      Text(
                        'Sprint 22 — wirebare-kernel SimpleWireBareProxyService',
                        style: TextStyle(
                          fontSize: 12,
                          color: AppTheme.muted,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            ElevatedButton(
              onPressed: _busy ? null : _toggleVpn,
              child: Text(_busy
                  ? 'Bekleyin…'
                  : (_vpnRunning ? 'VPN KAPAT' : 'VPN AÇ')),
            ),
            const SizedBox(height: 8),
            Text(
              'state: ${_vpnRunning ? "running" : "stopped"}'
              '${_lastResult.isNotEmpty ? "  •  $_lastResult" : ""}',
              style: const TextStyle(
                fontSize: 12,
                color: AppTheme.muted,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
