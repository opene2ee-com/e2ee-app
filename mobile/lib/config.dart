// mobile/lib/config.dart
//
// Sprint 10.1C — build-time injectable device / API constants.
//
// Why this file exists
// --------------------
// Sprint 10.1B shipped the per-service `<device_api_key_placeholder>`
// literal as an in-source placeholder. Sprint 10.1C promotes the
// device identity and the API key to a single config surface so a
// real build can inject both via `--dart-define`:
//
//   flutter build apk --debug \
//     --dart-define DEVICE_ID=a1b2c3d4e5f60718a1b2c3d4 \
//     --dart-define API_KEY=<real-key>
//
// The defaults (`test_key_placeholder` + the Owner-supplied mock
// device id) keep an out-of-the-box `flutter build apk --debug`
// working without any extra flags; production / tablet-test
// builds must override DEVICE_ID so the backend BFF routes the
// telemetry + matcher polls to the right device record.
//
// Sprint 10.1C Owner directive (10.07.2026 22:25)
// -----------------------------------------------
// "test device ID available - a1b2c3d4e5f60718a1b2c3d4 (mock
// device in backend DB). Inject as --dart-define
// DEVICE_ID=a1b2c3d4e5f60718a1b2c3d4 during build."
//
// Privacy
// -------
// DEVICE_ID is a backend-side correlation key — it identifies a
// device record in the BFF aggregator's device table so multiple
// telemetry uploads from the same physical device stitch together.
// It is NOT a user identifier (no IMEI / MSISDN / phone number).
// The audit's privacy-grep tool excludes the DEVICE_ID default
// (mock value) from its scan, the same way it excludes the
// `<device_api_key_placeholder>` literal in telemetry_service.dart.

import 'package:flutter/foundation.dart';

/// Build-time device identity. The BFF aggregator uses this
/// to stitch telemetry / matcher polls from the same physical
/// device together. Defaults to the Owner-supplied mock device
/// id (Sprint 10.1C, 10.07.2026 22:25) so the integration APK
/// hits a real backend record on first install.
const String kDeviceId = String.fromEnvironment(
  'DEVICE_ID',
  defaultValue: 'a1b2c3d4e5f60718a1b2c3d4',
);

/// Build-time API key. The default `test_key_placeholder` keeps
/// the build working out-of-the-box. Production / tablet-test
/// builds must override via `--dart-define API_KEY=<real-key>`.
///
/// Mirrors the `<device_api_key_placeholder>` literal in
/// `telemetry_service.dart` / `p2p_matcher.dart` (the S35 audit
/// checks those files directly for the `String.fromEnvironment`
/// call — the canonical declaration stays in the service file
/// that uses the key; this alias is here so the build script
/// can list ONE place that needs overriding).
const String kApiKey = String.fromEnvironment(
  'API_KEY',
  defaultValue: 'test_key_placeholder',
);

/// True when the build is a debug build (kReleaseMode == false
/// AND kDebugMode == true). The active pool screen uses this to
/// gate the `apiCallCount` debug caption — production builds
/// hide the counter to keep the screen clean.
bool get isDebugBuild => kDebugMode;

/// Active pool polling cadence. 5 seconds per the 10.1B brief
/// and re-confirmed by Owner for 10.1C.
const Duration kPoolPollPeriod = Duration(seconds: 5);
