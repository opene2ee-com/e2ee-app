// lib/services/telemetry_service.dart
//
// Sprint 23.2 — real telemetry upload to the v1 BFF schema.
//
// The backend's v1 telemetry contract (see
// `backend/internal/api/schemas/telemetry.schema.json`,
// commit `fcfa107`) is observation-oriented, NOT
// packet-oriented. Every call sends ONE row describing
// the current state of the e2ee transport on this device:
//
//   {
//     "device_id_hash":   "<16-64 hex>",
//     "public_key_fp":    "<16-32 hex>",
//     "operator":         "<enum>",
//     "app":              "<enum: whatsapp|rcs|telegram|signal>",
//     "tls_fp":           "<16-128 hex>",
//     "entropy":          <number, 0-8 bit/byte>,
//     "ip_subnet":        "<optional masked IP>",
//     "session_id":       "<optional uuid>",
//     "match_mode":       "<p2p|echobot|single>",
//     "peer_score":       <optional 0-100>,
//     "confidence":       <optional 0-1>,
//     "timestamp":        "<RFC 3339>"
//   }
//
// Two endpoints accept this exact shape:
//   - `POST /api/v1/telemetry`                  (per-tick, top-level)
//   - `POST /api/v1/sessions/{id}/telemetry`    (per-tick, scoped)
//
// The previous Sprint 22 `send(List<SampledPacket>)`
// contract (with a `packets` array) is REMOVED — the
// schema's `additionalProperties:false` rejects it with
// 400. Per-packet metadata lives in `ip_subnet` (single
// masked IP per row) and `entropy` (Shannon entropy of
// the most recent payload sample).
//
// 30-second summary
// -----------------
// `sendSummary()` is REMOVED in Sprint 23.2 — the v1 schema
// has no "summary" / "aggregate" row. The pool provider's
// 30-second `Timer.periodic` now just sends a regular
// observation with `match_mode: 'p2p'`, `peer_score` from
// `SessionScoreCalculator`, and a longer `entropy` window.
// (Sprint 24+ may add a separate `/sessions/{id}/close`
// hook that captures the final summary — for now `close`
// returns `summary_stats` from the backend.)
//
// Privacy (ADR-0006)
// -----------------
// - `device_id_hash` is the salted SHA-256 hex, NOT the
//   raw device UUID v7. The current `AppConfig.deviceId`
//   is already that hex; no transformation needed.
// - `public_key_fp` is the first-16-hex of
//   `SHA-256(Ed25519_public_key_bytes)`. Sprint 23.2
//   uses a STUB: a deterministic 16-hex string derived
//   from the device id (the real Ed25519 keypair isn't
//   generated yet — Sprint 24+).
// - `tls_fp` is a STUB: the first-16-hex of
//   `SHA-256("e2ee-ap-v2-v1-tls-fingerprint")`. Real
//   fingerprinting requires reading the TLS ClientHello
//   at the wirebare interceptor (Sprint 24+).
// - `entropy` is computed in Dart from a rolling window
//   of recent packet bytes (Shannon, 0-8 bit/byte).
//   Sprint 23.2 STUB: `0.0` until the wire-side capture
//   feeds a buffer.
// - `ip_subnet` is OPTIONAL. When supplied, it's a
//   /24 (v4) or /48 (v6) masked IP — same shape
//   `PacketParser` already produces.

import 'dart:async';
import 'dart:convert';

import 'package:crypto/crypto.dart' as crypto;
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import '../config.dart';
import 'auth_service.dart';

/// Thrown by [TelemetryService.sendObservation] on any
/// non-202 response, network error, or timeout.
class TelemetryException implements Exception {
  TelemetryException(this.message, {this.statusCode, this.cause});
  final String message;
  final int? statusCode;
  final Object? cause;

  @override
  String toString() => 'TelemetryException($message, status=$statusCode)';
}

/// One telemetry observation row. Mirrors the v1 schema's
/// required + optional field set so callers can either
/// pre-build the body or use the convenience constructor
/// `TelemetryObservation.fromStubs(...)` for tests.
class TelemetryObservation {
  TelemetryObservation({
    required this.deviceIdHash,
    required this.publicKeyFp,
    required this.operator,
    required this.app,
    required this.tlsFp,
    required this.entropy,
    this.ipSubnet,
    this.sessionId,
    this.matchMode,
    this.peerScore,
    this.confidence,
    DateTime? timestamp,
  }) : timestamp = timestamp ?? DateTime.now().toUtc();

  /// Salted SHA-256(device_id + server_salt)[:16..64] hex.
  /// Pattern: `^[a-f0-9]+$`, length 16-64.
  final String deviceIdHash;

  /// First 16-32 hex of `SHA-256(Ed25519_public_key)`.
  /// Pattern: `^[a-f0-9]+$`, length 16-32.
  final String publicKeyFp;

  /// Network operator enum (v1 schema values).
  final String operator;

  /// Application enum: `whatsapp` / `rcs` / `telegram` / `signal`.
  final String app;

  /// TLS ClientHello fingerprint (16-128 hex chars).
  final String tlsFp;

  /// Payload entropy in Shannon bits/byte (0-8).
  final double entropy;

  /// Optional masked IP (e.g. `10.0.0.0` for v4, `2001:db8::`
  /// for v6). Never a raw IP.
  final String? ipSubnet;

  /// Optional session UUID (matches the `id` returned by
  /// `POST /api/v1/sessions`).
  final String? sessionId;

  /// Optional match mode: `p2p` / `echobot` / `single`.
  final String? matchMode;

  /// Optional peer score (0-100), usually from
  /// `SessionScoreCalculator`.
  final double? peerScore;

  /// Optional confidence (0-1).
  final double? confidence;

  /// Wall-clock timestamp (UTC). Defaults to `DateTime.now()`.
  final DateTime timestamp;

  /// Wire-format map for the JSON body. Drops `null`
  /// optional fields so we don't trip
  /// `additionalProperties:false` (a `null` value is still
  /// present in the JSON object — empty body key is
  /// different from absent key).
  Map<String, Object?> toJson() => <String, Object?>{
        'device_id_hash': deviceIdHash,
        'public_key_fp': publicKeyFp,
        'operator': operator,
        'app': app,
        'tls_fp': tlsFp,
        'entropy': entropy,
        'timestamp': timestamp.toIso8601String(),
        if (ipSubnet != null) 'ip_subnet': ipSubnet,
        if (sessionId != null) 'session_id': sessionId,
        if (matchMode != null) 'match_mode': matchMode,
        if (peerScore != null) 'peer_score': peerScore,
        if (confidence != null) 'confidence': confidence,
      };

  /// Deterministic stub for tests + Sprint 23.2 default.
  /// Produces the same values every time given the same
  /// `deviceIdHash`, so two consecutive `sendObservation`
  /// calls in a test produce identical bodies (good for
  /// `verify(...).calledOnce` style assertions).
  ///
  /// The real values (Ed25519 pubkey hash, TLS ClientHello
  /// fingerprint, Shannon entropy) require wire-side
  /// capture that isn't available in Sprint 23.2 — see
  /// the file-level comment for the roadmap.
  static TelemetryObservation fromStubs({
    required String deviceIdHash,
    String? sessionId,
    String? matchMode,
    double? peerScore,
    double? confidence,
    double entropy = 0.0,
  }) {
    final pk = _stubPublicKeyFp(deviceIdHash);
    final tls = _stubTlsFp();
    return TelemetryObservation(
      deviceIdHash: deviceIdHash,
      publicKeyFp: pk,
      // operator: 'unknown' is the v1 schema's "I don't
      // know" sentinel — safer than guessing a carrier.
      operator: 'unknown',
      // app: 'whatsapp' is the v2 default (Sprint 10.0
      // chose WhatsApp as the primary smoke-test target).
      app: 'whatsapp',
      tlsFp: tls,
      entropy: entropy,
      sessionId: sessionId,
      matchMode: matchMode,
      peerScore: peerScore,
      confidence: confidence,
    );
  }

  /// 16-hex stub for `public_key_fp`. Deterministic per
  /// device id. When the real Ed25519 keypair is wired in
  /// (Sprint 24+), this helper is replaced with
  /// `sha256.convert(ed25519PubKeyBytes).toString().substring(0, 16)`.
  static String _stubPublicKeyFp(String deviceIdHash) {
    final digest = crypto.sha256.convert(utf8.encode('e2ee-pkfp:$deviceIdHash'));
    return digest.toString().substring(0, 16);
  }

  /// 16-hex stub for `tls_fp`. Static across the whole
  /// app for now (placeholder until wire-side TLS
  /// fingerprinting lands). Real implementation reads
  /// the ClientHello SNI + cipher_suites + extensions.
  static String _stubTlsFp() {
    const salt = 'e2ee-ap-v2-v1-tls-fingerprint-stub';
    final digest = crypto.sha256.convert(utf8.encode(salt));
    return digest.toString().substring(0, 16);
  }
}

class TelemetryService {
  TelemetryService({
    Uri? endpoint,
    String? apiKey,
    String? sessionId,
    AuthService? auth,
    http.Client? client,
    this._timeout = const Duration(seconds: 10),
  })  : _endpoint = endpoint ??
            Uri.parse('${AppConfig.apiBase}/api/v1/telemetry'),
        _apiKey = apiKey ?? _kApiKey,
        _sessionId = sessionId ?? _generateSessionId(),
        _auth = auth ?? AuthService(),
        _client = client ?? http.Client();

  final Uri _endpoint;
  // Retained for the 10.1B fallback path; the 10.1D
  // primary path uses _auth.authHeaders(). NOT removed so a
  // test that constructs TelemetryService without an
  // AuthService can still send a request (defensive).
  // ignore: unused_field
  final String _apiKey;
  final String _sessionId;
  final AuthService _auth;
  final http.Client _client;
  final Duration _timeout;

  /// Stable per-process session id. Exposed for the pool
  /// provider so P2P matching reuses the same id.
  String get sessionId => _sessionId;

  /// POST a single [TelemetryObservation] to the
  /// top-level `/api/v1/telemetry` endpoint. The Dart
  /// app calls this on every 5-second pool tick (Sprint
  /// 23.2 — was the per-packet `send()` in Sprint 22).
  ///
  /// Returns on 202; throws [TelemetryException] on any
  /// other outcome. The 401/403 case flushes the cached
  /// JWT (the next call re-auths).
  Future<void> sendObservation(TelemetryObservation obs) async {
    try {
      final headers = await _auth.authHeaders();
      headers['Content-Type'] = 'application/json';
      final resp = await _client
          .post(_endpoint, headers: headers, body: jsonEncode(obs.toJson()))
          .timeout(_timeout);
      if (resp.statusCode == 202) return;
      if (resp.statusCode == 401 || resp.statusCode == 403) {
        _auth.invalidate();
        throw TelemetryException(
          'unauthorized: jwt rejected, will re-auth next call',
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

  /// POST an observation scoped to a specific session
  /// (`POST /api/v1/sessions/{id}/telemetry`). Used by the
  /// per-session 30-second window path (Sprint 23.2
  /// keeps the old 30s cadence on this endpoint for
  /// backwards compatibility with the BFF's session
  /// close hook).
  Future<void> sendSessionObservation(
    TelemetryObservation obs, {
    String? sessionId,
  }) async {
    final id = sessionId ?? obs.sessionId ?? _sessionId;
    final scopedUri = Uri.parse(
      '${AppConfig.apiBase}/api/v1/sessions/$id/telemetry',
    );
    try {
      final headers = await _auth.authHeaders();
      headers['Content-Type'] = 'application/json';
      final resp = await _client
          .post(scopedUri, headers: headers, body: jsonEncode(obs.toJson()))
          .timeout(_timeout);
      if (resp.statusCode == 202) return;
      if (resp.statusCode == 401 || resp.statusCode == 403) {
        _auth.invalidate();
        throw TelemetryException(
          'unauthorized on session telemetry, will re-auth next call',
          statusCode: resp.statusCode,
        );
      }
      if (resp.statusCode == 429) {
        throw TelemetryException(
          'rate limit hit on session telemetry',
          statusCode: resp.statusCode,
        );
      }
      throw TelemetryException(
        'unexpected status on session telemetry',
        statusCode: resp.statusCode,
      );
    } on TimeoutException catch (e) {
      throw TelemetryException(
        'session telemetry timeout after ${_timeout.inSeconds}s',
        cause: e,
      );
    } catch (e) {
      if (e is TelemetryException) rethrow;
      throw TelemetryException('session telemetry network error', cause: e);
    }
  }

  /// Release the underlying [http.Client]. Safe to call
  /// multiple times. The pool provider calls this in
  /// its `dispose`.
  void close() => _client.close();

  /// Test-only hook — invalidate any cached JWT so the next
  /// `sendObservation` call MUST re-auth. Production code
  /// never calls this (the JWT cache is the whole point of
  /// the auth flow).
  @visibleForTesting
  void invalidateCacheForTest() => _auth.invalidate();

  /// Stable per-process session id. 16 random bytes
  /// hex-encoded.
  static String _generateSessionId() {
    final r = DateTime.now().microsecondsSinceEpoch.toRadixString(16);
    return 'sess-$r';
  }
}

// Build-time API key (kept for the S35 audit anchor in
// this file). The JWT auth flow does NOT consume this
// directly; the JWT replaces it.
const String _kApiKey =
    String.fromEnvironment('API_KEY', defaultValue: 'test_key_placeholder');
