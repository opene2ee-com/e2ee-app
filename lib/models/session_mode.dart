// lib/models/session_mode.dart
//
// Sprint 23 — session-mode enum for the v1 backend schema.
//
// The backend's `POST /api/v1/sessions` `mode` field is an
// enum (see `backend/internal/api/schemas/session-create.schema.json`):
//
//   - `p2p`     — direct device-to-device session (two phones,
//                 one offers, the other answers). Sprint 23
//                 default for `SessionOrchestrator.startSession`.
//   - `echobot` — single-device session that POSTs a canned
//                 text/image to a number and measures
//                 round-trip latency + delivery. No P2P
//                 negotiation. (This is what `webrtc` is
//                 not used for — it's a transport-level
//                 smoke test.)
//   - `single`  — single-device session that does NOT
//                 contact any other device (just records
//                 the local TLS handshake entropy for the
//                 transparency matrix).
//
// Wire format: the `name` is what we send + receive over
// JSON (matches the Dart `Protocol.name` pattern in
// `services/packet_parser.dart`).
//
// Stability: the set of values is part of the public
// contract with the backend. Adding a new value requires
// a schema bump; removing one is a breaking change.

enum SessionMode {
  /// Direct device-to-device session (default).
  p2p,

  /// Single-device echo/loopback session.
  echobot,

  /// Single-device passive recording session.
  single;

  /// The wire name the backend's v1 schema
  /// (`session-create.schema.json`) validates against.
  /// Coincidentally identical to `name` (lowercase enum
  /// identifier) but kept as an explicit getter so a
  /// future rename does not silently break the wire.
  String get wireName => name;

  /// Inverse of [wireName] — `mode` value → [SessionMode]
  /// enum. Returns `null` for unknown values
  /// (forward-compat).
  static SessionMode? fromWireName(String? wire) {
    if (wire == null) return null;
    switch (wire) {
      case 'p2p':
        return SessionMode.p2p;
      case 'echobot':
        return SessionMode.echobot;
      case 'single':
        return SessionMode.single;
      default:
        return null;
    }
  }
}
