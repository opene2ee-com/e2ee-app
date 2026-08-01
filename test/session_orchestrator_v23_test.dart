// test/session_orchestrator_v23_test.dart
//
// Sprint 23.0 — SessionOrchestrator v1 schema migration tests.
//
// Verifies the breaking changes from Sprint 22.4 → Sprint 23:
//   1. startSession body shape (mode + task_type, not role)
//   2. startSession response shape (id, not session_id)
//   3. closeSession still works and returns summary_stats
//   4. tearDown skips the DELETE round-trip (route missing
//      in Kong on the test environment as of Sprint 23.0)
//
// We use a MockClient (package:http/testing.dart) so the
// tests are hermetic — no network. The mock matches the
// probe_v7 response shapes byte-for-byte so a future
// regression at the wire boundary will be caught here.

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:e2ee_ap_v2/models/session_mode.dart';
import 'package:e2ee_ap_v2/models/task_type.dart';
import 'package:e2ee_ap_v2/services/auth_service.dart';
import 'package:e2ee_ap_v2/services/session_orchestrator.dart';

// Test JWT-shaped dummy token — the MockClient doesn't
// validate it; we just need a non-empty bearer to pass
// the orchestrator's `authHeaders()` path.
const String _dummyToken = 'eyJhbGciOiJIUzI1NiJ9.dummy.dummy';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  /// Build an orchestrator whose `AuthService` AND underlying
  /// `http.Client` both share the same [MockClient] — otherwise
  /// the auth round-trip escapes the mock (the
  /// `TestWidgetsFlutterBinding` blocks real HTTP, so
  /// `authHeaders()` throws `AuthException(400)`).
  ///
  /// Pass [extraHandler] to record / override specific paths
  /// on top of the auth stub.
  SessionOrchestrator buildOrch({
    required http.Client client,
    Future<http.Response> Function(http.BaseRequest req)? extraHandler,
  }) {
    final wrapped = MockClient((req) async {
      // Default: handle auth + delegate to extraHandler for
      // any other path the test wants to record/override.
      if (req.url.path.endsWith('/api/v1/auth') ||
          (req.url.path == '/api/v1/auth')) {
        return http.Response(
          jsonEncode({
            'token': _dummyToken,
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
    return SessionOrchestrator(
      auth: AuthService(client: wrapped),
      client: wrapped,
    );
  }

  group('startSession v1 schema (Sprint 23.0)', () {
    test('body carries mode + task_type in wire format (not role)',
        () async {
      Map<String, Object?>? capturedBody;
      final orch = buildOrch(
        client: MockClient((req) async => http.Response('', 204)),
        extraHandler: (req) async {
          if (req.url.path == '/api/v1/sessions' && req.method == 'POST') {
            capturedBody = jsonDecode((req as http.Request).body)
                as Map<String, Object?>;
            return http.Response(
              jsonEncode({
                'id': '0c993e03-73a3-4d46-a91a-c1ea8bd978ae',
                'device_id_hash': 'mavis-probe',
                'mode': 'echobot',
                'task_type': 'whatsapp_text',
                'status': 'pending',
                'created_at': '0001-01-01T00:00:00Z',
                'started_at': '2026-08-01T20:30:00Z',
              }),
              201,
              headers: {'Content-Type': 'application/json'},
            );
          }
          return http.Response('not found', 404);
        },
      );

      final id = await orch.startSession(
        mode: SessionMode.echobot,
        taskType: TaskType.whatsappText,
        testText: 'hello probe',
      );
      expect(id, '0c993e03-73a3-4d46-a91a-c1ea8bd978ae');
      expect(capturedBody, isNotNull);
      // v1 schema invariants:
      expect(capturedBody!['device_id_hash'], isA<String>());
      expect(capturedBody!['mode'], 'echobot',
          reason: 'mode is the wire name, NOT enum.name');
      expect(capturedBody!['task_type'], 'whatsapp_text',
          reason: 'task_type is snake_case, NOT enum.name camelCase');
      expect(capturedBody!.containsKey('role'), isFalse,
          reason: 'role field is REMOVED in v1 schema');
      expect(capturedBody!['test_text'], 'hello probe');
      orch.close();
    });

    test('response parsing reads `id` (NOT `session_id`)', () async {
      final orch = buildOrch(
        client: MockClient((req) async => http.Response('', 204)),
        extraHandler: (req) async {
          if (req.url.path == '/api/v1/sessions' && req.method == 'POST') {
            // Server returns ONLY `id` (no `session_id`)
            return http.Response(
              jsonEncode({
                'id': 'b841b8b6-ece6-413b-ab8c-b5db78cca445',
                'device_id_hash': 'mavis-probe',
                'mode': 'p2p',
                'task_type': 'whatsapp_voice',
                'status': 'pending',
              }),
              201,
              headers: {'Content-Type': 'application/json'},
            );
          }
          return http.Response('not found', 404);
        },
      );

      final id = await orch.startSession(
        mode: SessionMode.p2p,
        taskType: TaskType.whatsappVoice,
      );
      expect(id, 'b841b8b6-ece6-413b-ab8c-b5db78cca445');
      expect(orch.sessionId, id);
      expect(orch.mode, SessionMode.p2p);
      expect(orch.taskType, TaskType.whatsappVoice);
      orch.close();
    });

    test('missing `id` in response throws OrchestratorException', () async {
      final orch = buildOrch(
        client: MockClient((req) async => http.Response('', 204)),
        extraHandler: (req) async {
          if (req.url.path == '/api/v1/sessions' && req.method == 'POST') {
            // Legacy response with session_id (pre-Sprint 23).
            // The orchestrator MUST refuse this — backwards
            // compatibility is intentionally NOT preserved.
            return http.Response(
              jsonEncode({
                'session_id': 'old-shape-id',
                'role': 'offerer',
              }),
              200,
            );
          }
          return http.Response('not found', 404);
        },
      );

      expect(
        () => orch.startSession(
          mode: SessionMode.echobot,
          taskType: TaskType.whatsappText,
        ),
        throwsA(
          isA<OrchestratorException>().having(
            (e) => e.message,
            'message',
            contains('id field'),
          ),
        ),
      );
      orch.close();
    });

    test('optional fields are omitted from body when not provided',
        () async {
      Map<String, Object?>? capturedBody;
      final orch = buildOrch(
        client: MockClient((req) async => http.Response('', 204)),
        extraHandler: (req) async {
          if (req.url.path == '/api/v1/sessions' && req.method == 'POST') {
            capturedBody = jsonDecode((req as http.Request).body)
                as Map<String, Object?>;
            return http.Response(
              jsonEncode({
                'id': 'c4fa50ee-ec61-4b9d-8e75-76ca4b24c4ac',
                'device_id_hash': 'mavis-probe',
                'mode': 'single',
                'task_type': 'rcs_image',
                'status': 'pending',
              }),
              201,
              headers: {'Content-Type': 'application/json'},
            );
          }
          return http.Response('not found', 404);
        },
      );

      await orch.startSession(
        mode: SessionMode.single,
        taskType: TaskType.rcsImage,
        // no testText / targetPhoneHash / targetOperator
      );
      expect(capturedBody!['test_text'], isNull,
          reason: 'absent optional fields must NOT be in the body');
      expect(capturedBody!['target_phone_hash'], isNull);
      expect(capturedBody!['target_operator'], isNull);
      orch.close();
    });

    test('mode + taskType stored on instance for UI access', () async {
      final orch = buildOrch(
        client: MockClient((req) async => http.Response('', 204)),
        extraHandler: (req) async {
          if (req.url.path == '/api/v1/sessions' && req.method == 'POST') {
            return http.Response(
              jsonEncode({
                'id': 'b841b8b6-ece6-413b-ab8c-b5db78cca445',
                'mode': 'echobot', 'task_type': 'whatsapp_text',
                'status': 'pending',
              }),
              201,
              headers: {'Content-Type': 'application/json'},
            );
          }
          return http.Response('not found', 404);
        },
      );

      // Before startSession: nulls
      expect(orch.mode, isNull);
      expect(orch.taskType, isNull);

      await orch.startSession(
        mode: SessionMode.echobot,
        taskType: TaskType.whatsappText,
      );
      expect(orch.mode, SessionMode.echobot);
      expect(orch.taskType, TaskType.whatsappText);
      orch.close();
    });
  });

  group('closeSession v1 schema', () {
    test('returns summary_stats block from /close endpoint', () async {
      final orch = buildOrch(
        client: MockClient((req) async => http.Response('', 204)),
        extraHandler: (req) async {
          if (req.url.path == '/api/v1/sessions' && req.method == 'POST') {
            return http.Response(
              jsonEncode({
                'id': 'b841b8b6-ece6-413b-ab8c-b5db78cca445',
                'mode': 'echobot', 'task_type': 'whatsapp_text',
                'status': 'pending',
              }),
              201,
              headers: {'Content-Type': 'application/json'},
            );
          }
          if (req.url.path.endsWith('/close') && req.method == 'POST') {
            return http.Response(
              jsonEncode({
                'closed_at': '2026-08-01T20:30:00Z',
                'session_id': 'b841b8b6-ece6-413b-ab8c-b5db78cca445',
                'status': 'completed',
                'summary_stats': {
                  'captured_at': '2026-08-01T20:30:00Z',
                  'encrypted_packets': 100,
                  'encryption_integrity_pct': 99.5,
                  'jitter_ms': 3.2,
                  'mean_latency_ms': 12.7,
                  'packet_loss_pct': 0.5,
                  'total_packets': 102,
                },
              }),
              200,
              headers: {'Content-Type': 'application/json'},
            );
          }
          return http.Response('not found', 404);
        },
      );

      await orch.startSession(
        mode: SessionMode.echobot,
        taskType: TaskType.whatsappText,
      );
      final summary = await orch.closeSession();
      expect(summary, isNotNull);
      expect(summary!['total_packets'], 102);
      expect(summary['encrypted_packets'], 100);
      expect(summary['encryption_integrity_pct'], 99.5);
      expect(orch.lastSummaryStats, summary);
      expect(orch.sessionId, isNull,
          reason: 'closeSession clears the session state');
      orch.close();
    });
  });

  group('tearDown (Sprint 23.0)', () {
    test('does NOT call DELETE /api/v1/sessions/{id} (route missing)',
        () async {
      var deleteCalled = false;
      final orch = buildOrch(
        client: MockClient((req) async => http.Response('', 204)),
        extraHandler: (req) async {
          if (req.method == 'DELETE' &&
              req.url.path.contains('/api/v1/sessions/')) {
            deleteCalled = true;
            return http.Response('', 204);
          }
          return http.Response('not found', 404);
        },
      );

      await orch.tearDown();
      expect(deleteCalled, isFalse,
          reason: 'Sprint 23.0: DELETE /api/v1/sessions/{id} is NOT '
              'in Kong on the test environment — tearDown must NOT '
              'attempt the round-trip. Re-enable in Sprint 24+ when '
              'the route is added back.');
      orch.close();
    });

    test('clears session state (id, mode, taskType, summaryStats)',
        () async {
      final orch = buildOrch(
        client: MockClient((req) async => http.Response('', 204)),
        extraHandler: (req) async {
          if (req.url.path == '/api/v1/sessions' && req.method == 'POST') {
            return http.Response(
              jsonEncode({
                'id': 'b841b8b6-ece6-413b-ab8c-b5db78cca445',
                'mode': 'p2p', 'task_type': 'whatsapp_image',
                'status': 'pending',
              }),
              201,
              headers: {'Content-Type': 'application/json'},
            );
          }
          return http.Response('not found', 404);
        },
      );

      await orch.startSession(
        mode: SessionMode.p2p,
        taskType: TaskType.whatsappImage,
      );
      expect(orch.sessionId, isNotNull);
      expect(orch.mode, isNotNull);
      expect(orch.taskType, isNotNull);

      await orch.tearDown();
      expect(orch.sessionId, isNull);
      expect(orch.mode, isNull);
      expect(orch.taskType, isNull);
      expect(orch.lastSummaryStats, isNull);
      orch.close();
    });
  });
}
