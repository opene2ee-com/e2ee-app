// mobile/lib/services/p2p_matcher.dart
//
// Sprint 10.1B — peer-to-peer matcher via api-test.opene2ee.com.
//
// What this is
// ------------
// Polls `GET /matches?sessionId=<our-session>` on a 5-second
// cadence. The endpoint returns either:
//   - 200 + {"peerSessionId": "...", "transport": "rcs|whatsapp"}
//     when a peer is waiting for us, OR
//   - 204 No Content when no peer is available yet.
//
// The pool provider feeds the result into the UI; on a match,
// it triggers a snackbar + haptic via Riverpod listeners.
//
// Sprint 10.1B scope
// ------------------
// This is the polling-side of the matcher only — the WebRTC
// offer/answer state machine is owned by the (separate) Sprint
// 10.1A UI sprint and is NOT modified here. We just return the
// peer's session id + transport; the rest of the call setup
// happens elsewhere.
//
// Privacy
// -------
// We send our own `sessionId` (a per-process random string from
// `TelemetryService._generateSessionId`) — not the device
// installation id, not the IMEI/MSISDN, and not the masked IP.
// The peer's session id is the only thing the matcher returns.
//
// Error handling
// --------------
// 200 → parse body, return [MatchResult].
// 204 → no peer → return `null`.
// 401 / 403 → matcher unauthenticated → throw; pool provider
//   stops polling until the device key is rotated.
// 5xx / network error → throw; pool provider logs + retries on
//   the next tick.

import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

class MatchResult {
  MatchResult({required this.peerSessionId, required this.transport});
  final String peerSessionId;
  final String transport; // "rcs" or "whatsapp"
  Map<String, Object?> toJson() => {
        'peerSessionId': peerSessionId,
        'transport': transport,
      };
}

class P2PMatcher {
  P2PMatcher({
    Uri? endpoint,
    required String apiKey,
    http.Client? client,
    Duration timeout = const Duration(seconds: 5),
  })  : _endpoint = endpoint ??
            Uri.parse('https://api-test.opene2ee.com/matches'),
        _apiKey = apiKey,
        _client = client ?? http.Client(),
        _timeout = timeout;

  final Uri _endpoint;
  final String _apiKey;
  final http.Client _client;
  final Duration _timeout;

  /// One-shot poll. Returns:
  ///   - a [MatchResult] when a peer is available,
  ///   - `null` on 204 (no peer yet),
  ///   - throws on any other status / transport error.
  Future<MatchResult?> findMatch(String sessionId) async {
    final uri = _endpoint.replace(queryParameters: {
      'sessionId': sessionId,
    });
    try {
      final resp = await _client
          .get(
            uri,
            headers: {
              'Authorization': 'Bearer $_apiKey',
              'Accept': 'application/json',
            },
          )
          .timeout(_timeout);
      if (resp.statusCode == 200) {
        final body = jsonDecode(resp.body) as Map<String, Object?>;
        final peer = body['peerSessionId'];
        final transport = body['transport'];
        if (peer is! String || peer.isEmpty) {
          throw const FormatException('peerSessionId missing or empty');
        }
        if (transport is! String || transport.isEmpty) {
          throw const FormatException('transport missing or empty');
        }
        return MatchResult(peerSessionId: peer, transport: transport);
      }
      if (resp.statusCode == 204) return null;
      if (resp.statusCode == 401 || resp.statusCode == 403) {
        throw http.ClientException(
          'unauthorized (status ${resp.statusCode})',
          uri,
        );
      }
      throw http.ClientException(
        'unexpected status ${resp.statusCode}',
        uri,
      );
    } on TimeoutException {
      rethrow;
    } catch (e) {
      if (e is http.ClientException) rethrow;
      throw http.ClientException('transport error: $e', uri);
    }
  }

  void close() => _client.close();
}
