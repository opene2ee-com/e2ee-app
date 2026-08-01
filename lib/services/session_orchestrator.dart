// lib/services/session_orchestrator.dart
//
// Sprint 23.0 — Session Orchestrator (v1 backend schema).
//
// Drives the WebRTC P2P negotiation:
//   1. startSession()    — POST /api/v1/sessions → session id
//                          (v1 schema: device_id_hash, mode,
//                          task_type; backend returns
//                          {id, ...} — NOT {session_id, ...})
//   2. pollForOffer()    — GET /api/v1/webrtc/offer?session_id=...
//                          long-poll (30s timeout) until the
//                          peer posts an offer
//   3. negotiate()       — create local offer (or answer),
//                          POST, long-poll for the remote
//                          counterpart, setRemoteDescription
//   4. closeSession()    — POST /api/v1/sessions/{id}/close
//                          → returns summary_stats
//   5. tearDown()        — close the peer connection (no
//                          DELETE round-trip; the route
//                          isn't in Kong on the test
//                          environment as of Sprint 23.0
//                          — deferred)
//
// Sprint 23 schema migration (v1 contract):
//   - Request body now requires `mode` (SessionMode) +
//     `task_type` (TaskType). The old `role` field is gone.
//   - Response uses `id` (UUID), not `session_id`.
//   - `receiver_session_id` is no longer in the response
//     — receivers are discovered via the WebSocket
//     signalling channel (Sprint 24+); the
//     `_receiverSessionId` field is removed.
//   - Optional fields: `test_text`, `target_phone_hash`,
//     `target_operator`.
//
// Wire surface (Sprint 23.0):
//   POST /api/v1/sessions                              → start
//   GET  /api/v1/webrtc/offer?session_id=...           → poll
//   GET  /api/v1/webrtc/answer?session_id=...          → poll
//   POST /api/v1/webrtc/offer                          → push (DEFERRED)
//   POST /api/v1/webrtc/answer                         → push (DEFERRED)
//   POST /api/v1/webrtc/ice                            → push (DEFERRED)
//   POST /api/v1/sessions/{id}/close                   → close
//   POST /api/v1/sessions/{id}/telemetry               → 30s summary

import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../config.dart';
import '../models/session_mode.dart';
import '../models/task_type.dart';
import 'auth_service.dart';
import 'webrtc_service.dart';

class SessionOrchestrator {
  SessionOrchestrator({
    AuthService? auth,
    WebRTCService? webrtc,
    http.Client? client,
    this._pollTimeout = const Duration(seconds: 30),
  })  : _auth = auth ?? AuthService(),
        _webrtc = webrtc ?? WebRTCService(),
        _client = client ?? http.Client();

  final AuthService _auth;
  final WebRTCService _webrtc;
  final http.Client _client;
  final Duration _pollTimeout;

  /// The session id returned by `startSession()`. Other methods
  /// read this; the orchestrator owns the lifecycle.
  String? _sessionId;

  /// The current session's `mode` (echoed back from the
  /// backend on `startSession`). Defaults to [SessionMode.p2p]
  /// when the orchestrator is freshly constructed.
  SessionMode? _mode;

  /// The current session's `task_type`. Defaults to
  /// [TaskType.whatsappText] when the orchestrator is freshly
  /// constructed.
  TaskType? _taskType;

  /// Tear-down flag. Set by [tearDown] to short-circuit any
  /// in-flight long-poll + createOffer paths.
  bool _tornDown = false;

  /// `summary_stats` cache. Populated by [closeSession] when
  /// the backend returns the canonical
  /// `{summary_stats: {...}}` block. The Skorlar screen reads
  /// this on the next frame so the user sees the new score
  /// immediately after "Oturumu Bitir" returns.
  Map<String, Object?>? _lastSummaryStats;

  String? get sessionId => _sessionId;
  SessionMode? get mode => _mode;
  TaskType? get taskType => _taskType;
  WebRTCService get webrtc => _webrtc;
  WebRTCState get webrtcState => _webrtc.state;
  Map<String, Object?>? get remoteOffer => null; // Sprint 24+ (signalling)
  Map<String, Object?>? get remoteAnswer => null; // Sprint 24+ (signalling)
  Map<String, Object?>? get lastSummaryStats => _lastSummaryStats;

  /// POST /api/v1/sessions → returns `{ id, ... }` (v1 schema).
  ///
  /// Required: [mode] + [taskType]. Optional: [testText],
  /// [targetPhoneHash], [targetOperator]. The response uses
  /// the canonical `id` field for the session UUID (NOT
  /// `session_id` — that's the old 10.1B field that the v1
  /// backend removed).
  Future<String> startSession({
    required SessionMode mode,
    required TaskType taskType,
    String? testText,
    String? targetPhoneHash,
    String? targetOperator,
  }) async {
    final headers = await _auth.authHeaders();
    headers['Content-Type'] = 'application/json';

    final body = <String, Object?>{
      'device_id_hash': AppConfig.deviceId,
      'mode': mode.wireName,
      'task_type': taskType.wireName,
    };
    if (testText != null && testText.isNotEmpty) {
      body['test_text'] = testText;
    }
    if (targetPhoneHash != null && targetPhoneHash.isNotEmpty) {
      body['target_phone_hash'] = targetPhoneHash;
    }
    if (targetOperator != null && targetOperator.isNotEmpty) {
      body['target_operator'] = targetOperator;
    }

    final resp = await _client
        .post(
          Uri.parse('${AppConfig.apiBase}/api/v1/sessions'),
          headers: headers,
          body: jsonEncode(body),
        )
        .timeout(const Duration(seconds: 10));
    if (resp.statusCode != 201 && resp.statusCode != 200) {
      throw OrchestratorException(
        'startSession failed (${resp.statusCode}): ${resp.body}',
        statusCode: resp.statusCode,
      );
    }
    final responseBody = jsonDecode(resp.body) as Map<String, Object?>;
    // Sprint 23 schema change: backend returns `id`, not
    // `session_id`. The old field name is no longer in the
    // response — failing to find `id` means the backend is
    // still on the pre-23 contract.
    final id = responseBody['id'] as String?;
    if (id == null) {
      throw const OrchestratorException(
        'startSession: response missing id field '
        '(backend may be on pre-Sprint-23 contract)',
      );
    }
    _sessionId = id;
    _mode = mode;
    _taskType = taskType;
    return _sessionId!;
  }

  /// GET /api/v1/webrtc/offer?session_id=... with 30s long-poll.
  ///
  /// The backend's GET handler holds the request open for up
  /// to 30s, returning either the remote offer SDP (200 + JSON
  /// body) or an empty body (204 + no offer yet). The Dart side
  /// cancels the request at 30s and retries.
  ///
  /// Sprint 24+ — the answerer side polls this; the offerer
  /// side posts its offer and long-polls `/api/v1/webrtc/answer`.
  /// For Sprint 23 this method is wired but the upstream
  /// WebSocket signalling migration is deferred.
  Future<Map<String, Object?>?> pollForOffer() async {
    if (_sessionId == null) {
      throw const OrchestratorException('pollForOffer: no session_id');
    }
    if (_tornDown) return null;
    final headers = await _auth.authHeaders();
    final url = Uri.parse(
      '${AppConfig.apiBase}/api/v1/webrtc/offer?session_id=$_sessionId',
    );
    try {
      final resp = await _client.get(url, headers: headers).timeout(
            _pollTimeout,
            onTimeout: () {
              return http.Response('', 204);
            },
          );
      if (resp.statusCode == 204 || resp.body.isEmpty) {
        return null;
      }
      if (resp.statusCode != 200) {
        throw OrchestratorException(
          'pollForOffer failed (${resp.statusCode}): ${resp.body}',
          statusCode: resp.statusCode,
        );
      }
      final body = jsonDecode(resp.body) as Map<String, Object?>;
      return body['sdp'] as Map<String, Object?>?;
    } on TimeoutException {
      return null;
    }
  }

  /// GET /api/v1/webrtc/answer?session_id=... with 30s long-poll.
  /// Mirror of [pollForOffer] for the answerer side.
  Future<Map<String, Object?>?> pollForAnswer() async {
    if (_sessionId == null) {
      throw const OrchestratorException('pollForAnswer: no session_id');
    }
    if (_tornDown) return null;
    final headers = await _auth.authHeaders();
    final url = Uri.parse(
      '${AppConfig.apiBase}/api/v1/webrtc/answer?session_id=$_sessionId',
    );
    try {
      final resp = await _client.get(url, headers: headers).timeout(
            _pollTimeout,
            onTimeout: () => http.Response('', 204),
          );
      if (resp.statusCode == 204 || resp.body.isEmpty) {
        return null;
      }
      if (resp.statusCode != 200) {
        throw OrchestratorException(
          'pollForAnswer failed (${resp.statusCode}): ${resp.body}',
          statusCode: resp.statusCode,
        );
      }
      final body = jsonDecode(resp.body) as Map<String, Object?>;
      return body['sdp'] as Map<String, Object?>?;
    } on TimeoutException {
      return null;
    }
  }

  /// Drive the offerer-side negotiation:
  ///   1. createOffer on the local peer connection
  ///   2. POST /api/v1/webrtc/offer (with the local SDP)
  ///   3. long-poll /api/v1/webrtc/answer for the remote answer
  ///   4. setRemoteDescription on the local peer connection
  ///
  /// Sprint 24+ — the WebRTC routes are still in the
  /// "planned" bucket per the test-environment status
  /// (Sprint 23.0 backlog). This method is wired but
  /// untested on a real device in Sprint 23.
  Future<void> negotiateAsOfferer() async {
    if (_sessionId == null) {
      throw const OrchestratorException(
        'negotiateAsOfferer: no session_id (call startSession first)',
      );
    }
    if (_tornDown) return;
    await _webrtc.createPeerConnection();
    final localOffer = await _webrtc.createOffer();
    final headers = await _auth.authHeaders();
    headers['Content-Type'] = 'application/json';
    final offerResp = await _client
        .post(
          Uri.parse('${AppConfig.apiBase}/api/v1/webrtc/offer'),
          headers: headers,
          body: jsonEncode({
            'session_id': _sessionId,
            'peer_hash': AppConfig.deviceId,
            'sdp': localOffer,
          }),
        )
        .timeout(const Duration(seconds: 10));
    if (offerResp.statusCode != 201 && offerResp.statusCode != 200) {
      throw OrchestratorException(
        'POST offer failed (${offerResp.statusCode}): ${offerResp.body}',
        statusCode: offerResp.statusCode,
      );
    }
    final answerSdp = await pollForAnswer();
    if (answerSdp == null) {
      throw const OrchestratorException('no answer received in 30s');
    }
    await _webrtc.setRemoteDescription(
      sdpType: (answerSdp['sdp_type'] as String?) ?? 'answer',
      sdp: (answerSdp['sdp'] as String?) ?? '',
    );
  }

  /// Drive the answerer-side negotiation. Sprint 24+ —
  /// WebRTC routes still in the planned bucket.
  Future<void> negotiateAsAnswerer() async {
    if (_sessionId == null) {
      throw const OrchestratorException(
        'negotiateAsAnswerer: no session_id',
      );
    }
    if (_tornDown) return;
    final offerSdp = await pollForOffer();
    if (offerSdp == null) {
      throw const OrchestratorException('no offer received in 30s');
    }
    await _webrtc.createPeerConnection();
    await _webrtc.setRemoteDescription(
      sdpType: (offerSdp['sdp_type'] as String?) ?? 'offer',
      sdp: (offerSdp['sdp'] as String?) ?? '',
    );
    final localAnswer = await _webrtc.createAnswer();
    final headers = await _auth.authHeaders();
    headers['Content-Type'] = 'application/json';
    final answerResp = await _client
        .post(
          Uri.parse('${AppConfig.apiBase}/api/v1/webrtc/answer'),
          headers: headers,
          body: jsonEncode({
            'session_id': _sessionId,
            'peer_hash': AppConfig.deviceId,
            'sdp': localAnswer,
          }),
        )
        .timeout(const Duration(seconds: 10));
    if (answerResp.statusCode != 200 && answerResp.statusCode != 201) {
      throw OrchestratorException(
        'POST answer failed (${answerResp.statusCode}): ${answerResp.body}',
        statusCode: answerResp.statusCode,
      );
    }
  }

  /// Tear down: close the peer connection. The
  /// `DELETE /api/v1/sessions/{id}` round-trip is skipped —
  /// the route isn't in Kong on the test environment as of
  /// Sprint 23.0 (the server-side 15-minute TTL is the
  /// safety net). Idempotent.
  Future<void> tearDown() async {
    _tornDown = true;
    await _webrtc.close();
    _sessionId = null;
    _mode = null;
    _taskType = null;
    _lastSummaryStats = null;
  }

  /// Close the active session and capture the backend's
  /// `summary_stats` block. Returns the canonical summary
  /// shape (or `null` if the session had no packets).
  Future<Map<String, Object?>?> closeSession({String? sessionId}) async {
    final id = sessionId ?? _sessionId;
    if (id == null) {
      throw const OrchestratorException(
        'closeSession: no session_id (call startSession first)',
      );
    }
    final headers = await _auth.authHeaders();
    headers['Content-Type'] = 'application/json';
    final resp = await _client
        .post(
          Uri.parse('${AppConfig.apiBase}/api/v1/sessions/$id/close'),
          headers: headers,
          body: jsonEncode({
            'closed_at': DateTime.now().toUtc().toIso8601String(),
          }),
        )
        .timeout(const Duration(seconds: 10));
    if (resp.statusCode != 200 && resp.statusCode != 201) {
      throw OrchestratorException(
        'closeSession failed (${resp.statusCode}): ${resp.body}',
        statusCode: resp.statusCode,
      );
    }
    final body = jsonDecode(resp.body) as Map<String, Object?>;
    _lastSummaryStats = body['summary_stats'] as Map<String, Object?>?;
    // Tear down the local WebRTC + state — the session is
    // "completed" on the backend; we no longer need the
    // peer connection.
    await _webrtc.close();
    _tornDown = true;
    _sessionId = null;
    _mode = null;
    _taskType = null;
    return _lastSummaryStats;
  }

  /// Release the underlying [http.Client].
  void close() => _client.close();
}

class OrchestratorException implements Exception {
  const OrchestratorException(this.message, {this.statusCode});
  final String message;
  final int? statusCode;

  @override
  String toString() => 'OrchestratorException($message, status=$statusCode)';
}
