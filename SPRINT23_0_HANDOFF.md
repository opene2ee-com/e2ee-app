# Sprint 23.0 — v1 backend schema migration handoff

**Branch:** `e2ee-ap-v2`
**Build SHA-256:** `00BA6E9D981161E976993B0A522F251A26824261E883D2C624D5E986FC1357C7`
**APK size:** 184 MB (compressed, debug)
**Test status:** 61/61 PASS (43 Sprint 22 + 18 Sprint 23)

## What landed

The backend's `POST /api/v1/sessions` schema changed in
Sprint 22+ (Drop `role`, add `mode` + `task_type` + several
optional fields, rename `session_id` → `id`). Sprint 23.0
migrates the Dart client to match.

### Files added
- `lib/models/session_mode.dart` — `SessionMode` enum
  (`p2p` / `echobot` / `single`) with `wireName` and
  `fromWireName` getters.
- `lib/models/task_type.dart` — `TaskType` enum
  (`whatsappText` / `whatsappImage` / `whatsappVoice` /
  `rcsText` / `rcsImage`) with `wireName` snake_case
  mapping. **Critical:** the Dart `enum.name` is camelCase
  (`whatsappText`); the schema needs snake_case
  (`whatsapp_text`). `wireName` is the only safe path
  to the wire.
- `test/models_test.dart` — 12 cases pinning the wire
  contract (Sprint 23.0 enum surface + round-trips +
  forward-compat nulls for unknown values).
- `test/session_orchestrator_v23_test.dart` — 8 cases
  using `MockClient` to verify the v1 wire format
  (body shape, response parsing, optional field
  omission, close→summary_stats round-trip, tearDown
  no-DELETE).
- `tools/integration_v23.py` — integration probe against
  the live test backend.
- `tools/.gitignore` — keeps `probe_v[2-8]*.py` debug
  scripts out of the repo (those were one-off
  investigations for the Kong credential/missing-route
  discovery in Sprint 22.x; not production tools).

### Files modified
- `lib/services/session_orchestrator.dart` — full rewrite
  of `startSession` to use `mode` + `taskType` (required)
  + optional `testText` / `targetPhoneHash` /
  `targetOperator`. Response parsing reads `id` (not
  `session_id`). Removed `_receiverSessionId` (receivers
  come via WebSocket signalling in Sprint 24+).
  `tearDown` no longer calls `DELETE /api/v1/sessions/{id}`
  (route missing in Kong on the test env as of Sprint 23.0;
  re-enable in Sprint 24+).
- `test/vpn_packet_stream_test.dart` — removed redundant
  `dart:async` import (lint fix).

## Wire contract (canonical)

**Request body** (`POST /api/v1/sessions`):
```json
{
  "device_id_hash": "<16-64 hex chars>",
  "mode": "p2p" | "echobot" | "single",        // required
  "task_type": "whatsapp_text" | "whatsapp_image"
             | "whatsapp_voice" | "rcs_text"
             | "rcs_image",                  // required
  "test_text":        "<optional, max 256>",
  "target_phone_hash":"<optional, 16-64 hex>",
  "target_operator":  "turkcell" | "vodafone_tr"
                     | "turk_telekom" | "att"
                     | "verizon" | "unknown"
}
```

**Response** (201 Created):
```json
{
  "id": "<uuid>",            // ← was session_id in pre-v1
  "device_id_hash": "...",
  "mode": "...",
  "task_type": "...",
  "status": "pending",
  "created_at": "...",
  "started_at": "..."
}
```

**Close response** (200 OK, `POST /sessions/{id}/close`):
```json
{
  "closed_at": "...",
  "session_id": "...",      // the close endpoint still
                              // echoes session_id (it's
                              // the request URL param),
                              // not the same field that
                              // was renamed in startSession
  "status": "completed",
  "summary_stats": {
    "captured_at": "...",
    "encrypted_packets": 100,
    "encryption_integrity_pct": 99.5,
    "jitter_ms": 3.2,
    "mean_latency_ms": 12.7,
    "packet_loss_pct": 0.5,
    "total_packets": 102
  }
}
```

## Build note — Dart tree-shaking

The new code IS in the source (61/61 tests pass against it),
but the current debug APK does NOT include `SessionMode` /
`TaskType` / `SessionOrchestrator` symbol strings. The
reason: **no `lib/` screen calls `SessionOrchestrator`
yet**, so the Dart compiler's tree-shaker removes the
unreachable code from the APK. The `Sprint 23` literal
string in the kernel comes from a separate TODO comment
in `screens/*.dart` (RCS connection — Sprint 24 backlog).

When Sprint 24 wires `SessionOrchestrator.startSession`
into the active-pool flow, the new code will be included
automatically — no build-config change needed.

## Test results

```
flutter analyze: 0 issues
flutter test:    61/61 PASS
   Sprint 22 base:                 43 tests
   test/models_test.dart:           12 tests
   test/session_orchestrator_v23:    8 tests
   (1 new test from previous
    sprint110d_handler_test fix)     -2
   net delta:                       +18 tests
```

## Integration probe (`tools/integration_v23.py`)

Runs the v1 wire path against the live test backend:
1. Mint JWT locally with `iss=opene2ee-backend` +
   user-supplied `JWT_SECRET`.
2. `POST /api/v1/sessions` with `mode=p2p` + 
   `task_type=whatsapp_text` + `test_text`.
3. `POST /api/v1/sessions/<id>/close` → verify
   `summary_stats` block.
4. `GET /api/v1/sessions` → verify our session appears.

Known backend bug (not Dart): the `POST /api/v1/sessions`
endpoint sometimes returns 500 internal_error even
though the row IS committed to the DB (the new session
shows up in `GET /sessions`). This is a backend
post-create hook failure (probably the WebSocket
signalling setup that depends on the WebRTC service,
which is `nil` in the test env config). Sprint 24+
will fix this on the backend side.

## Deferred to Sprint 24+

- WebSocket signalling (replaces the long-poll
  `/api/v1/webrtc/{offer,answer}` pair). Required
  before `_receiverSessionId` can come back into
  the model.
- Re-enable `DELETE /api/v1/sessions/{id}` in
  `tearDown` once the route is added back to Kong.
- The `mode=echobot` 500 from the backend
  (independent bug in `InsertSession` post-hook).
- WebRTC service dependency injection on the
  backend (currently `nil` in the test env config,
  so all 4 `/api/v1/webrtc/*` endpoints return 500).
