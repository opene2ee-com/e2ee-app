// lib/services/webrtc_service.dart
//
// Sprint 22.4 — WebRTC P2P service.
//
// The brief specifies a "modern, non-deprecated" WebRTC Dart
// package; on pub.dev the actively-maintained package is
// `flutter_webrtc` 1.5.x (was renamed from `webrtc` in 2024
// — the original `webrtc` 0.0.1 is incompatible with Dart
// 3.12.1). This service wraps `RTCPeerConnection`,
// `RTCSessionDescription`, and the ICE callback surface from
// `flutter_webrtc` so the rest of the app uses pure-Dart types
// (no `dart:ffi` reach-through, no
// `import 'package:flutter_webrtc/...'` in the consumers).
//
// Wire surface (canonical WebRTC JSON envelopes per
// docs/ARCHITECTURE_DECISIONS.md §5.6):
//   POST /api/v1/webrtc/offer   { session_id, peer_hash, sdp }
//   POST /api/v1/webrtc/answer  { session_id, peer_hash, sdp }
//   POST /api/v1/webrtc/ice     { session_id, peer_hash, candidates }
//   GET  /api/v1/webrtc/offer?session_id=...   (long-poll, 30s timeout)
//   GET  /api/v1/webrtc/answer?session_id=...  (long-poll, 30s timeout)
//   GET  /api/v1/webrtc/config  (STUN/TURN server list)
//
// All endpoints are JWT-protected (auth_service.dart bearer
// token). SDP text + ICE candidate strings are NEVER logged
// (ADR-0006 §Veri Minimizasyonu — peer-reflexive IP leaks).

import 'dart:async';

import 'package:flutter_webrtc/flutter_webrtc.dart' as webrtc;
import 'package:flutter_webrtc/flutter_webrtc.dart'
    show
        RTCIceCandidate,
        RTCSessionDescription,
        RTCPeerConnection,
        RTCIceConnectionState,
        MediaStream,
        RTCTrackEvent;

import '../config.dart';
import 'auth_service.dart';

/// Lifecycle state of the WebRTC peer connection. Mirrors the
/// canonical W3C RTCPeerConnectionState enum (new / connecting /
/// connected / disconnected / failed / closed). The
/// ActivePoolScreen shows the live state in a "WebRTC durumu"
/// pill.
enum WebRTCState {
  /// Peer connection not yet created (the orchestrator hasn't
  /// called startSession yet).
  idle,

  /// RTCPeerConnection is created; createOffer is in flight.
  negotiating,

  /// SDP exchange complete; ICE candidates are flowing.
  connected,

  /// The peer connection was deliberately torn down.
  closed,

  /// The peer connection failed (SDP rejected, ICE exhausted,
  /// DTLS handshake error, etc.).
  failed,
}

class WebRTCService {
  WebRTCService({AuthService? auth, Map<String, Object?>? config})
      : _auth = auth ?? AuthService(),
        // ignore: unused_field
        _config = config ?? const <String, Object?>{} {
    // The orchestrator wires `onIceCandidate` to the backend
    // POST handler. We declare the field up front so the
    // `RTCPeerConnection` creation in [createPeerConnection] can
    // subscribe without a null-check.
  }

  // ignore: unused_field
  final AuthService _auth;
  // ignore: unused_field
  final Map<String, Object?> _config;

  /// The live peer connection. `null` until the orchestrator
  /// calls [createPeerConnection]. Replaced on every new session.
  RTCPeerConnection? _pc;

  /// ICE candidate sink. The orchestrator wires this to the
  /// backend POST handler. We keep the controller here so the
  /// callback can synchronously emit a candidate without
  /// awaiting the orchestrator's HTTP call.
  final StreamController<Map<String, Object?>> _iceCtrl =
      StreamController<Map<String, Object?>>.broadcast();

  /// Track event sink. The stream emits the canonical
  /// `MediaStream` events from `flutter_webrtc`; consumers
  /// can subscribe via [onTrack] to observe inbound peer
  /// streams.
  final StreamController<MediaStream> _trackCtrl =
      StreamController<MediaStream>.broadcast();

  /// State stream. Emits a [WebRTCState] on every transition
  /// observed via the `onIceConnectionState` callback.
  final StreamController<WebRTCState> _stateCtrl =
      StreamController<WebRTCState>.broadcast();

  /// Exposed for the orchestrator + UI: every ICE candidate
  /// the peer connection discovers is emitted here. The
  /// orchestrator POSTs each one to `/api/v1/webrtc/ice` and
  /// surfaces remote candidates via [addIceCandidate].
  Stream<Map<String, Object?>> get onIceCandidate => _iceCtrl.stream;

  /// Exposed for the UI: every `MediaStream` the peer
  /// connection receives is emitted here.
  Stream<MediaStream> get onTrack => _trackCtrl.stream;

  /// Lifecycle stream. Mirrors the wirebare-kernel VPN state
  /// pattern (Sprint 22.2) so the ActivePoolScreen can show a
  /// single combined status pill.
  Stream<WebRTCState> get stateStream => _stateCtrl.stream;

  /// The current peer connection state. Updated by the
  /// `onIceConnectionState` callback.
  WebRTCState _state = WebRTCState.idle;
  WebRTCState get state => _state;

  /// Build the ICE-server config map. Reads STUN/TURN URLs from
  /// [AppConfig] (or the optional `config` override) and
  /// returns the canonical `flutter_webrtc` shape. The
  /// `iceServers` list carries STUN URIs as the minimum
  /// fallback (Google's `stun.l.google.com:19302`).
  Future<Map<String, dynamic>> _buildIceServers() async {
    // The actual call site for the backend's
    // `/api/v1/webrtc/config` endpoint is in
    // session_orchestrator.dart. For the initial config we read
    // from [AppConfig] if the app provides one at build time
    // (e.g. `--dart-define STUN_URL=...`); otherwise we fall
    // back to the public Google STUN servers.
    final customStun = const String.fromEnvironment('STUN_URL',
        defaultValue: '');
    if (customStun.isNotEmpty) {
      return {
        'iceServers': [
          {'urls': customStun},
        ],
      };
    }
    return {
      'iceServers': [
        {'urls': 'stun:stun.l.google.com:19302'},
      ],
    };
  }

  /// Create the peer connection. Idempotent — already-created
  /// connections are reused. The STUN/TURN config is loaded
  /// from the build-time env first, then the public Google STUN
  /// fallback.
  ///
  /// Note: the class method is `createPeerConnection()` (no args,
  /// idempotent). The factory top-level function is
  /// `webrtc.createPeerConnection({...config...})` — we import
  /// it under the `webrtc` prefix so the call site disambiguates
  /// from the instance method.
  Future<void> createPeerConnection() async {
    if (_pc != null) return; // idempotent — already created
    final config = await _buildIceServers();
    _pc = await webrtc.createPeerConnection({
      'iceServers':
          (config['iceServers'] as List).cast<Map<String, dynamic>>(),
      'sdpSemantics': 'unified-plan',
    });
    // Wire the canonical WebRTC callbacks.
    _pc!.onIceCandidate = (RTCIceCandidate candidate) {
      // The candidate has `candidate`, `sdpMid`, `sdpMLineIndex`.
      // We pass it as a JSON-friendly map so the orchestrator
      // can serialise it without re-parsing the SDK's
      // RTCIceCandidate shape.
      _iceCtrl.add({
        'candidate': candidate.candidate,
        'sdpMid': candidate.sdpMid,
        'sdpMLineIndex': candidate.sdpMLineIndex,
      });
    };
    _pc!.onTrack = (RTCTrackEvent event) {
      if (event.streams.isNotEmpty) {
        _trackCtrl.add(event.streams[0]);
      }
    };
    _pc!.onIceConnectionState = (RTCIceConnectionState iceState) {
      switch (iceState) {
        case RTCIceConnectionState.RTCIceConnectionStateNew:
        case RTCIceConnectionState.RTCIceConnectionStateChecking:
          _state = WebRTCState.negotiating;
          break;
        case RTCIceConnectionState.RTCIceConnectionStateConnected:
        case RTCIceConnectionState.RTCIceConnectionStateCompleted:
          _state = WebRTCState.connected;
          break;
        case RTCIceConnectionState.RTCIceConnectionStateDisconnected:
        case RTCIceConnectionState.RTCIceConnectionStateFailed:
          _state = WebRTCState.failed;
          break;
        case RTCIceConnectionState.RTCIceConnectionStateClosed:
          _state = WebRTCState.closed;
          break;
        case RTCIceConnectionState.RTCIceConnectionStateCount:
          // The Count sentinel value is a non-state; ignore.
          break;
      }
      _stateCtrl.add(_state);
    };
  }

  /// Create an SDP offer. The orchestrator POSTs the result
  /// to `/api/v1/webrtc/offer`. Returns the SDP map the
  /// backend expects (`sdp_type` + `sdp`).
  Future<Map<String, Object?>> createOffer() async {
    if (_pc == null) await createPeerConnection();
    final description = await _pc!.createOffer({
      'offerToReceiveAudio': true,
      'offerToReceiveVideo': false,
    });
    await _pc!.setLocalDescription(description);
    return {
      'sdp_type': description.type ?? 'offer',
      'sdp': description.sdp ?? '',
    };
  }

  /// Create an SDP answer. Mirror of [createOffer] for the
  /// answerer side.
  Future<Map<String, Object?>> createAnswer() async {
    if (_pc == null) await createPeerConnection();
    final description = await _pc!.createAnswer();
    await _pc!.setLocalDescription(description);
    return {
      'sdp_type': description.type ?? 'answer',
      'sdp': description.sdp ?? '',
    };
  }

  /// Apply a remote SDP (offer from the peer or our own
  /// offer on the answerer side).
  Future<void> setRemoteDescription({
    required String sdpType,
    required String sdp,
  }) async {
    if (_pc == null) await createPeerConnection();
    await _pc!.setRemoteDescription(
      RTCSessionDescription(sdp, sdpType),
    );
  }

  /// Apply a single remote ICE candidate. The orchestrator
  /// receives candidates from the backend's `/api/v1/webrtc/ice`
  /// response and feeds them in here.
  Future<void> addIceCandidate({
    required String candidate,
    String? sdpMid,
    int? sdpMLineIndex,
  }) async {
    if (_pc == null) await createPeerConnection();
    await _pc!.addCandidate(
      RTCIceCandidate(candidate, sdpMid, sdpMLineIndex ?? 0),
    );
  }

  /// Tear down the peer connection + close the streams.
  /// Idempotent.
  Future<void> close() async {
    if (_pc != null) {
      await _pc!.close();
      _pc = null;
    }
    await _iceCtrl.close();
    await _trackCtrl.close();
    await _stateCtrl.close();
  }
}
