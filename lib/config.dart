// lib/config.dart
//
// Sprint 22 — e2ee-ap-v2 config surface.
//
// Build-time injectable constants. The defaults keep an out-of-the-box
// `flutter build apk --debug` working; production builds override via
// `--dart-define` flags.
//
//   flutter build apk --debug \
//     --dart-define DEVICE_ID=<real-device-id> \
//     --dart-define API_KEY=<real-key> \
//     --dart-define API_BASE=https://api.opene2ee.com

import 'package:flutter/foundation.dart';

/// Sprint 22 — `AppConfig` consolidates all build-time injectable
/// constants. The previous OpenE2EE build (Sprint 9.6.6 → 19) had
/// `kXxx` aliases for back-compat; we drop them in v2 because no
/// prior call sites exist (fresh branch).
class AppConfig {
  AppConfig._();

  /// Semantic version tag (e.g., `22.0`). Injected at build time
  /// via `--dart-define VERSION_NAME=22.0`. Default is the current
  /// sprint so an out-of-the-box build shows a recent version.
  static const String versionName = String.fromEnvironment(
    'VERSION_NAME',
    defaultValue: '22.0',
  );

  /// 7-char git commit SHA of the build. Injected via
  /// `--dart-define VERSION_CODE=<sha>`. The Coder pipeline reads
  /// `git rev-parse --short=7 HEAD` and injects the result.
  static const String versionCode = String.fromEnvironment(
    'VERSION_CODE',
    defaultValue: '0000000',
  );

  /// Build-time device identity. The BFF aggregator uses this to
  /// stitch telemetry from the same physical device together.
  /// NOT a user identifier (no IMEI / MSISDN / phone number).
  static const String deviceId = String.fromEnvironment(
    'DEVICE_ID',
    defaultValue: 'a1b2c3d4e5f60718a1b2c3d4',
  );

  /// API key. The Sprint 22 JWT auth flow uses this to mint a
  /// short-lived JWT (HS256, 1h TTL) via POST /api/v1/auth.
  static const String apiKey = String.fromEnvironment(
    'API_KEY',
    defaultValue: 'test_key_placeholder',
  );

  /// Base URL for the BFF aggregator. `auth_service`,
  /// `telemetry_service`, `p2p_matcher` all prefix their paths
  /// with this value. Defaults to test environment.
  static const String apiBase = String.fromEnvironment(
    'API_BASE',
    defaultValue: 'https://api-test.opene2ee.com',
  );

  /// API version (bare integer per the BFF's X-API-Version
  /// validator). Hard-coded for now; promoted to a build-time
  /// injectable when the backend ships v2.
  static const String apiVersion = '1';

  /// MethodChannel name for the VPN service bridge. The Kotlin
  /// MainActivity registers a handler on this channel. The
  /// previous OpenE2EE build used `opene2ee/vpn`; v2 uses
  /// `com.opene2ee.e2ee_ap_v2/vpn` to align with the package
  /// rename (Sprint 21 Gate 7 fix).
  static const String vpnChannelName =
      'com.opene2ee.e2ee_ap_v2/vpn';

  /// WireBare service start/stop intent actions. Defined in
  /// the manifest's `<intent-filter>` and dispatched by
  /// `WireBare.startProxy` / `WireBare.stopProxy`.
  static const String vpnActionStart =
      'com.opene2ee.e2ee_ap_v2.vpn.core.action.Start';
  static const String vpnActionStop =
      'com.opene2ee.e2ee_ap_v2.vpn.core.action.Stop';
}

/// True when the build is a debug build. Production builds hide
/// debug-only UI elements (counter overlays, breadcrumb prints).
bool get isDebugBuild => kDebugMode;

/// Active pool polling cadence (seconds). The pool provider polls
/// the matcher on this interval. Sprint 22 default matches the
/// 10.1B brief (5s) but exposed as a config for fine-tuning.
const int kPoolPollSeconds = 5;

/// Sprint 22.3 — build-time device identity alias. Kept for
/// callers that still expect the `kDeviceId` top-level
/// identifier (the Sprint 10.1B / 10.1D contract). The BFF
/// aggregator uses this to stitch telemetry from the same
/// physical device together.
const String kDeviceId = AppConfig.deviceId;

/// Sprint 22.3 — build-time API key alias. Kept for callers
/// that still expect the `kApiKey` top-level identifier (the
/// Sprint 10.1B / 10.1D contract). The JWT auth flow does NOT
/// consume this directly; the JWT replaces it. Read by
/// `P2PMatcher` as a fallback when no `AuthService` is wired.
const String kApiKey = AppConfig.apiKey;
