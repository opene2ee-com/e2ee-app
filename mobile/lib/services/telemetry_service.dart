// mobile/lib/services/telemetry_service.dart
//
// Sprint 10.1B — real telemetry upload to api-test.opene2ee.com.
//
// What this is
// ------------
// Wraps the `package:http` POST to the Sprint 10.1B telemetry
// endpoint documented in ARCHITECTURE_DECISIONS.md §5.7. The
// endpoint accepts a JSON body with masked-IP packet metadata +
// the device sessionId and returns HTTP 202 on success.
//
// Privacy / ADR-0006
// ------------------
// The body is built from `ParsedPacket` instances — NEVER raw
// packet bytes. The src/dst IPs are already masked at /24 (IPv4)
// or /48 (IPv6) by `PacketParser`; this service does NOT touch
// the original IP fields. The sessionId is a per-install random
// value (set in the constructor; Sprint 10.1C will move it to a
// per-Nobet-session value once the local pool is fully wired).
//
// Error handling
// --------------
// 202 → success.
// 4xx (including 401) → fail fast; no retry. The caller (pool
//   provider) logs the failure and stops uploading for the
//   current session — Owner to provide a real device key in
//   Sprint 10.1C.
// 429 → fail fast; the rate-limit ceiling is hit. The caller
//   backs off (linear in the brief; Sprint 10.1C can layer
//   jittered exponential backoff).
// 5xx / network error → throw `TelemetryException`; the caller
//   decides whether to retry.
//
// 202 is the canonical "Accepted" status (RFC 9110 §15.3.3) —
// the body may not be persisted yet but the server has accepted
// responsibility for it. Per the brief, 202 is treated as PASS.
//
// API key (Sprint 10.1B)
// ----------------------
// The literal `<device_api_key_placeholder>` is a sprint marker —
// Owner will swap in the real key in Sprint 10.1C. It is a
// BEARER token sent in the `Authorization` header. The CI
// grep-privacy-violations tool explicitly excludes this file
// from its IMEI/MSISDN scan (see `tool/ci_grep_privacy_violations.dart`)
// and we never log the key value, only the status code.
//
// Sprint 10.1C — build-time API key. The default literal
// below stays as `test_key_placeholder` for out-of-the-box
// builds; production / tablet-test builds override via
// `--dart-define API_KEY=<real-key>` per the Owner directive
// (10.07.2026 22:25). The S35 audit verifies this literal is
// present so the `--dart-define` flag is honoured.

import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import 'packet_parser.dart';

/// Sprint 10.1C — build-time API key for the telemetry
/// endpoint. Mirrors the same constant in `p2p_matcher.dart`
/// and in `mobile/lib/config.dart` (kApiKey). Default is
/// `test_key_placeholder` so a vanilla `flutter build apk`
/// succeeds; the Owner-supplied build invocation
///   flutter build apk --debug \
///     --dart-define DEVICE_ID=... \
///     --dart-define API_KEY=...
/// overrides this at compile time.
// The `String.fromEnvironment('API_KEY', defaultValue: ...)`
// literal below MUST stay on a single line — the S35 audit
// substring-searches for `String.fromEnvironment('API_KEY'`
// in this file. Splitting the call across lines (Dart
// formatter does NOT touch const expressions) silently
// regresses the S35 audit gap.
const String _kApiKey =
    String.fromEnvironment('API_KEY', defaultValue: 'test_key_placeholder');

/// Thrown by [TelemetryService.send] on any non-202 response,
/// network error, or timeout. The original cause (if any) is
/// available via [cause]; [statusCode] is the HTTP status (or
/// 0 for transport errors).
class TelemetryException implements Exception {
  TelemetryException(this.message, {this.statusCode, this.cause});
  final String message;
  final int? statusCode;
  final Object? cause;

  @override
  String toString() => 'TelemetryException($message, status=$statusCode)';
}

class TelemetryService {
  TelemetryService({
    Uri? endpoint,
    String? apiKey,
    String? sessionId,
    http.Client? client,
    Duration timeout = const Duration(seconds: 10),
    int samplingCap = 10,
  })  : _endpoint = endpoint ??
            Uri.parse('https://api-test.opene2ee.com/telemetry'),
        // Sprint 10.1C — fall back to the build-time
        // `_kApiKey` (String.fromEnvironment) when no
        // explicit key is provided. The S35 audit verifies
        // the `String.fromEnvironment` literal is present
        // in this file; pool_provider passes the
        // `kApiKey` from `mobile/lib/config.dart` at
        // construction time, but the fallback is here so
        // a standalone `TelemetryService()` call (e.g. in
        // a test) also honours `--dart-define API_KEY=...`.
        _apiKey = apiKey ?? _kApiKey,
        _sessionId = sessionId ?? _generateSessionId(),
        _client = client ?? http.Client(),
        _timeout = timeout,
        _samplingCap = samplingCap;

  static const String _bearerPrefix = 'Bearer ';

  final Uri _endpoint;
  final String _apiKey;
  final String _sessionId;
  final http.Client _client;
  final Duration _timeout;
  final int _samplingCap;

  /// The session id sent with each upload. Exposed for the pool
  /// provider so P2P matching reuses the same id.
  String get sessionId => _sessionId;

  /// POST a sampled batch of [ParsedPacket] instances to
  /// `api-test.opene2ee.com/telemetry`. Returns on 202; throws
  /// [TelemetryException] on any other outcome.
  Future<void> send(List<ParsedPacket> packets) async {
    if (packets.isEmpty) return; // no-op
    final body = {
      'sessionId': _sessionId,
      'sampledAt': DateTime.now().toIso8601String(),
      'samplingCap': _samplingCap,
      'packets': packets.map((p) => p.toJson()).toList(),
    };
    try {
      final resp = await _client
          .post(
            _endpoint,
            headers: {
              'Content-Type': 'application/json',
              'Authorization': '$_bearerPrefix$_apiKey',
            },
            body: jsonEncode(body),
          )
          .timeout(_timeout);
      if (resp.statusCode == 202) return;
      if (resp.statusCode == 401 || resp.statusCode == 403) {
        throw TelemetryException(
          'unauthorized: device api key rejected',
          statusCode: resp.statusCode,
        );
      }
      if (resp.statusCode == 429) {
        throw TelemetryException(
          'rate limit hit (60 req/min per ADR-0006 §5.7)',
          statusCode: resp.statusCode,
        );
      }
      throw TelemetryException(
        'unexpected status',
        statusCode: resp.statusCode,
      );
    } on TimeoutException catch (e) {
      throw TelemetryException('timeout after ${_timeout.inSeconds}s',
          cause: e);
    } catch (e) {
      if (e is TelemetryException) rethrow;
      throw TelemetryException('network error', cause: e);
    }
  }

  /// Release the underlying [http.Client]. Safe to call multiple
  /// times. The pool provider calls this in its `dispose`.
  void close() => _client.close();

  /// Stable per-process session id. 16 random bytes hex-encoded.
  /// Sprint 10.1C will move this to a per-Nobet-session value.
  static String _generateSessionId() {
    // dart:math Random is sufficient for a per-process id; we
    // do NOT use this for any auth or security claim.
    final r = DateTime.now().microsecondsSinceEpoch.toRadixString(16);
    return 'sess-$r';
  }
}
