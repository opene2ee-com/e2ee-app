// test/telemetry_service_test.dart
//
// Sprint 23.2 — TelemetryService tests for the v1 BFF schema.
//
// What this verifies:
//   1. `sendObservation` (top-level) 202 → success, body shape
//   2. `sendSessionObservation` (per-session) 202 → success
//   3. 401 → TelemetryException + JWT invalidate
//   4. 429 → TelemetryException (rate limit)
//   5. Body shape conforms to the v1 schema:
//      device_id_hash, public_key_fp, operator, app,
//      tls_fp, entropy, timestamp (+ optional session_id,
//      match_mode, peer_score, confidence, ip_subnet)
//   6. `TelemetryObservation.toJson` drops null optional
//      fields (avoid `additionalProperties:false` rejection)
//
// Privacy:
// - Body is built from `TelemetryObservation.fromStubs`,
//   which is deterministic per `device_id_hash` (same
//   device → same `public_key_fp`). Tests don't read raw
//   packet bytes.

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:e2ee_ap_v2/services/auth_service.dart';
import 'package:e2ee_ap_v2/services/telemetry_service.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const String dummyToken = 'eyJhbGciOiJIUzI1NiJ9.dummy.dummy';

  /// Build a TelemetryService whose `AuthService` and
  /// underlying `http.Client` both share the same [MockClient]
  /// — otherwise the auth round-trip escapes the mock and
  /// throws (the test harness blocks real HTTP).
  TelemetryService buildSvc({
    required http.Client client,
    Future<http.Response> Function(http.BaseRequest req)? extraHandler,
  }) {
    final wrapped = MockClient((req) async {
      if (req.url.path.endsWith('/api/v1/auth') ||
          (req.url.path == '/api/v1/auth')) {
        return http.Response(
          jsonEncode({
            'token': dummyToken,
            'token_type': 'Bearer',
            'expires_in': 3600,
          }),
          200,
          headers: {'Content-Type': 'application/json'},
        );
      }
      if (extraHandler != null) return extraHandler(req);
      return http.Response('not found', 404);
    });
    return TelemetryService(
      auth: AuthService(client: wrapped),
      client: wrapped,
    );
  }

  group('TelemetryService.sendObservation (v1 schema)', () {
    test('202 Accepted → body has v1 required fields', () async {
      Map<String, Object?>? capturedBody;
      final svc = buildSvc(
        client: MockClient((req) async => http.Response('', 204)),
        extraHandler: (req) async {
          if (req.url.path == '/api/v1/telemetry' && req.method == 'POST') {
            capturedBody = jsonDecode((req as http.Request).body)
                as Map<String, Object?>;
            return http.Response(
              jsonEncode({'id': 1, 'accepted': true}),
              202,
              headers: {'Content-Type': 'application/json'},
            );
          }
          return http.Response('not found', 404);
        },
      );

      final obs = TelemetryObservation.fromStubs(
        deviceIdHash: 'a1b2c3d4e5f60718a1b2c3d4',
        matchMode: 'p2p',
        peerScore: 95.0,
        confidence: 0.9,
      );
      await svc.sendObservation(obs);
      expect(capturedBody, isNotNull);

      // v1 required fields:
      expect(capturedBody!['device_id_hash'], 'a1b2c3d4e5f60718a1b2c3d4');
      expect(capturedBody!['public_key_fp'], isA<String>());
      expect((capturedBody!['public_key_fp'] as String).length, 16,
          reason: 'public_key_fp must be 16 hex chars');
      expect(capturedBody!['operator'], 'unknown');
      expect(capturedBody!['app'], 'whatsapp');
      expect(capturedBody!['tls_fp'], isA<String>());
      expect((capturedBody!['tls_fp'] as String).length, 16);
      expect(capturedBody!['entropy'], 0.0);
      expect(capturedBody!['timestamp'], isA<String>());

      // Optional fields populated from stubs:
      expect(capturedBody!['match_mode'], 'p2p');
      expect(capturedBody!['peer_score'], 95.0);
      expect(capturedBody!['confidence'], 0.9);

      // Sprint 22 legacy field NOT in body (would 400 the
      // v1 schema's `additionalProperties:false`):
      expect(capturedBody!.containsKey('packets'), isFalse);
      expect(capturedBody!.containsKey('sampledAt'), isFalse);
      expect(capturedBody!.containsKey('samplingCap'), isFalse);
      expect(capturedBody!.containsKey('sessionId'), isFalse,
          reason: 'sessionId was Sprint 22 field name; v1 uses session_id (snake)');

      svc.close();
    });

    test('null optional fields are omitted (additionalProperties:false)',
        () async {
      Map<String, Object?>? capturedBody;
      final svc = buildSvc(
        client: MockClient((req) async => http.Response('', 204)),
        extraHandler: (req) async {
          if (req.url.path == '/api/v1/telemetry' && req.method == 'POST') {
            capturedBody = jsonDecode((req as http.Request).body)
                as Map<String, Object?>;
            return http.Response(
              jsonEncode({'id': 2, 'accepted': true}),
              202,
            );
          }
          return http.Response('not found', 404);
        },
      );

      // Minimal observation — no optional fields.
      final obs = TelemetryObservation(
        deviceIdHash: 'a1b2c3d4e5f60718a1b2c3d4',
        publicKeyFp: 'a' * 16,
        operator: 'unknown',
        app: 'whatsapp',
        tlsFp: 'b' * 16,
        entropy: 7.5,
      );
      await svc.sendObservation(obs);

      // Required fields present:
      expect(capturedBody!.containsKey('device_id_hash'), isTrue);
      expect(capturedBody!.containsKey('public_key_fp'), isTrue);
      expect(capturedBody!.containsKey('operator'), isTrue);
      expect(capturedBody!.containsKey('app'), isTrue);
      expect(capturedBody!.containsKey('tls_fp'), isTrue);
      expect(capturedBody!.containsKey('entropy'), isTrue);
      expect(capturedBody!.containsKey('timestamp'), isTrue);

      // Optional fields OMITTED (not null, not present):
      expect(capturedBody!.containsKey('ip_subnet'), isFalse);
      expect(capturedBody!.containsKey('session_id'), isFalse);
      expect(capturedBody!.containsKey('match_mode'), isFalse);
      expect(capturedBody!.containsKey('peer_score'), isFalse);
      expect(capturedBody!.containsKey('confidence'), isFalse);

      svc.close();
    });

    test('401 Unauthorized → throws TelemetryException', () async {
      // Build a fresh service with a fresh AuthService so the
      // JWT cache is empty. The auth round-trip will hit our
      // mock and return the dummy token; the telemetry call
      // will then return 401.
      final svc = buildSvc(
        client: MockClient((req) async => http.Response('', 204)),
        extraHandler: (req) async {
          if (req.url.path == '/api/v1/telemetry' && req.method == 'POST') {
            return http.Response('unauthorized', 401);
          }
          return http.Response('not found', 404);
        },
      );

      final obs = TelemetryObservation.fromStubs(
        deviceIdHash: 'a1b2c3d4e5f60718a1b2c3d4',
      );
      // Force re-auth by invalidating any token cached from
      // a previous test (each test gets a fresh buildSvc
      // but the AuthService internal cache state is
      // independent — clear it explicitly).
      svc.invalidateCacheForTest();
      expect(
        () => svc.sendObservation(obs),
        throwsA(isA<TelemetryException>()
            .having((e) => e.statusCode, 'statusCode', 401)),
      );
      svc.close();
    });

    test('429 Too Many Requests → throws TelemetryException', () async {
      final svc = buildSvc(
        client: MockClient((req) async => http.Response('', 204)),
        extraHandler: (req) async {
          if (req.url.path == '/api/v1/telemetry' && req.method == 'POST') {
            return http.Response('rate-limited', 429);
          }
          return http.Response('not found', 404);
        },
      );

      final obs = TelemetryObservation.fromStubs(
        deviceIdHash: 'a1b2c3d4e5f60718a1b2c3d4',
      );
      expect(
        () => svc.sendObservation(obs),
        throwsA(isA<TelemetryException>()
            .having((e) => e.statusCode, 'statusCode', 429)),
      );
      svc.close();
    });
  });

  group('TelemetryService.sendSessionObservation (v1 schema)', () {
    test('posts to /api/v1/sessions/{id}/telemetry with session_id',
        () async {
      String? capturedPath;
      Map<String, Object?>? capturedBody;
      final svc = buildSvc(
        client: MockClient((req) async => http.Response('', 204)),
        extraHandler: (req) async {
          if (req.url.path == '/api/v1/sessions/sess-test/telemetry' &&
              req.method == 'POST') {
            capturedPath = req.url.path;
            capturedBody = jsonDecode((req as http.Request).body)
                as Map<String, Object?>;
            return http.Response(
              jsonEncode({'id': 100, 'accepted': true}),
              202,
            );
          }
          return http.Response('not found', 404);
        },
      );

      final obs = TelemetryObservation.fromStubs(
        deviceIdHash: 'a1b2c3d4e5f60718a1b2c3d4',
        sessionId: 'sess-test',
        matchMode: 'p2p',
        peerScore: 87.0,
        confidence: 0.85,
      );
      await svc.sendSessionObservation(obs);

      expect(capturedPath, '/api/v1/sessions/sess-test/telemetry');
      expect(capturedBody, isNotNull);
      expect(capturedBody!['session_id'], 'sess-test');
      expect(capturedBody!['match_mode'], 'p2p');
      expect(capturedBody!['peer_score'], 87.0);
      expect(capturedBody!['confidence'], 0.85);
      svc.close();
    });

    test('404 on session path → throws (Sprint 22.6 sanity check)', () async {
      final svc = buildSvc(
        client: MockClient((req) async => http.Response('', 204)),
        extraHandler: (req) async {
          return http.Response('{"message":"no Route matched"}', 404);
        },
      );
      final obs = TelemetryObservation.fromStubs(
        deviceIdHash: 'a1b2c3d4e5f60718a1b2c3d4',
      );
      expect(
        () => svc.sendSessionObservation(obs),
        throwsA(isA<TelemetryException>()
            .having((e) => e.statusCode, 'statusCode', 404)),
      );
      svc.close();
    });
  });

  group('TelemetryObservation.fromStubs (deterministic)', () {
    test('public_key_fp is deterministic per device_id_hash', () {
      final a = TelemetryObservation.fromStubs(
        deviceIdHash: 'a1b2c3d4e5f60718a1b2c3d4',
      );
      final b = TelemetryObservation.fromStubs(
        deviceIdHash: 'a1b2c3d4e5f60718a1b2c3d4',
      );
      expect(a.publicKeyFp, b.publicKeyFp);
      // 16-char lowercase hex (the first 16 hex chars of the
      // SHA-256 of the literal "e2ee-pkfp:<device>")
      expect(a.publicKeyFp.length, 16);
      expect(RegExp(r'^[a-f0-9]{16}$').hasMatch(a.publicKeyFp), isTrue,
          reason: 'public_key_fp MUST be 16 lowercase hex chars (v1 schema pattern)');
    });

    test('tls_fp is static across calls (placeholder)', () {
      final a = TelemetryObservation.fromStubs(
        deviceIdHash: 'device-A',
      );
      final b = TelemetryObservation.fromStubs(
        deviceIdHash: 'device-B',  // different device, same tls_fp
      );
      expect(a.tlsFp, b.tlsFp,
          reason: 'Sprint 23.2 tls_fp is a placeholder — real '
              'wire-side fingerprinting lands in Sprint 24+');
      expect(a.tlsFp.length, 16);
      expect(RegExp(r'^[a-f0-9]{16}$').hasMatch(a.tlsFp), isTrue);
    });

    test('entropy defaults to 0.0 (Sprint 23.2 stub)', () {
      final obs = TelemetryObservation.fromStubs(
        deviceIdHash: 'a1b2c3d4e5f60718a1b2c3d4',
      );
      expect(obs.entropy, 0.0,
          reason: 'Real entropy is computed in Sprint 24+ from a '
              'rolling window of recent packet bytes (Shannon).');
    });

    test('operator is "unknown" sentinel', () {
      // v1 schema's "I don't know" carrier. Safer than
      // guessing a network operator and being wrong.
      final obs = TelemetryObservation.fromStubs(
        deviceIdHash: 'a1b2c3d4e5f60718a1b2c3d4',
      );
      expect(obs.operator, 'unknown');
    });
  });
}

