# Sprint 23.2 — v1 telemetry observation + DELETE re-enabled handoff

**Branch:** `e2ee-ap-v2`
**Build SHA-256:** `95EAFB84429CBDA7855ED0D97D71406D24CCEF8F131884B936E0CF8DB07DB0ED`
**APK size:** 201.4 MB (debug)
**Test status:** 66/66 PASS (was 61, +5 new)

## What landed

Sprint 23.0 migrated the session-create flow to the v1
schema. Sprint 23.2 does the same for telemetry (the
last hold-out — the per-packet `send()` from Sprint 22
broke the v1 schema's `additionalProperties:false`
contract).

### Wire-format breaking change

**OLD (Sprint 22):** `TelemetryService.send(List<SampledPacket>)`
posted a `packets[]` array — rejected by v1 schema
(400 "Additional property packets is not allowed").

**NEW (Sprint 23.2):** `TelemetryService.sendObservation(TelemetryObservation)`
posts ONE observation row per tick with the v1 required
field set:
```json
{
  "device_id_hash":  "<16-64 hex>",
  "public_key_fp":   "<16-32 hex>",
  "operator":        "<enum: turkcell|vodafone_tr|...|unknown>",
  "app":             "<enum: whatsapp|rcs|telegram|signal>",
  "tls_fp":          "<16-128 hex>",
  "entropy":         <number, 0-8>,
  "timestamp":       "<RFC 3339>",
  "ip_subnet":       "<NOT in v1 schema — additionalProperty rejected>",
  "session_id":      "<optional uuid>",
  "match_mode":      "<optional: p2p|echobot|single>",
  "peer_score":      "<optional 0-100>",
  "confidence":      "<optional 0-1>",
  "signature":       "<optional Ed25519>"
}
```

### Files added
(none — all changes are in existing files)

### Files modified
- `lib/services/telemetry_service.dart` — full rewrite
  for the v1 observation contract. `sendObservation` is
  the new hot path; `sendSessionObservation` does the
  same for `/sessions/{id}/telemetry`. Old `send()`
  and `sendSummary()` are GONE.
- `lib/services/session_orchestrator.dart` — `tearDown`
  re-enables `DELETE /api/v1/sessions/{id}` (route was
  missing in Kong in Sprint 23.0, restored in 23.2).
  DELETE is best-effort (5s timeout, swallows errors).
- `lib/state/pool_provider.dart` — 5-second tick now
  sends ONE observation via `fromStubs(deviceIdHash:
  kDeviceId, sessionId: _sessionId, matchMode: 'p2p')`.
  `ip_subnet` is intentionally NOT sent (probe_v14
  confirmed v1 schema rejects it via
  `additionalProperties:false`).
- `test/telemetry_service_test.dart` — 9 new tests
  pinning the v1 contract: required fields, omitted
  optionals, 401/429 error handling, `fromStubs`
  determinism (public_key_fp derived deterministically
  from device_id_hash; tls_fp is a static placeholder).
- `test/session_orchestrator_v23_test.dart` —
  `tearDown` tests updated: now expects DELETE call
  + asserts best-effort cleanup on 500.
- `pubspec.yaml` — added `crypto: ^3.0.3` for the
  `public_key_fp` / `tls_fp` SHA-256 stubs.
- `tools/probe_v14_v1_observation.py` — full v1
  lifecycle integration probe (observation matches
  Dart-side `fromStubs` byte-for-byte).

## Stubs that need real data (Sprint 24+)

Three v1 fields are placeholder values in Sprint 23.2:

1. **`public_key_fp`** — first-16-hex of
   `SHA-256("e2ee-pkfp:" + device_id_hash)`. Real value
   needs the Ed25519 keypair: first-16-hex of
   `SHA-256(ed25519PublicKeyBytes)`. Sprint 24+ generates
   the keypair in Dart (or reads it from secure storage
   after the first-launch flow).
2. **`tls_fp`** — first-16-hex of
   `SHA-256("e2ee-ap-v2-v1-tls-fingerprint-stub")`. Real
   value needs the wire-side TLS ClientHello (cipher
   suites, extensions, SNI, ALPN). This requires a
   wirebare interceptor in the kernel layer (parallel
   to the existing `PacketCapture`).
3. **`entropy`** — hardcoded `0.0`. Real value is
   Shannon entropy of the most recent payload sample
   (0-8 bit/byte). Same wire-side requirement as
   `tls_fp` (need to read TLS payload bytes).

The `operator` and `app` fields use the v1 schema's
`"unknown"` / `"whatsapp"` sentinels for now; Sprint
24+ will integrate with the existing
`/api/v1/operator/lookup` endpoint to fill `operator`
and let the user pick `app` in the active-pool UI.

## Integration probe (`tools/probe_v14_v1_observation.py`)

Walks: create session → POST /telemetry (v1 obs) →
POST /sessions/<id>/telemetry (v1 obs) → close →
DELETE /sessions/<id>. All return 2xx when the body
matches the v1 schema. The `ip_subnet` field was
discovered to be REJECTED by the v1 schema (the
`fcfa107` schema lists it as optional, but
`additionalProperties:false` blocks it from the wire).
Sprint 24 will add it to the schema or remove it
from the Dart side entirely.

## Test results

```
flutter analyze: 0 issues
flutter test:    66/66 PASS
   Sprint 22 base:                          43 tests
   Sprint 23.0 (models + orchestrator):     18 tests
   Sprint 23.2 (telemetry + tearDown):       +5 tests
                                         ─────
   net delta:                               +23 tests since Sprint 22
```

## Endpoints — current state

| Endpoint | Status | Note |
|---|---|---|
| `POST /api/v1/auth` | 200 | ✓ |
| `GET /api/v1/matrix` | 200 | ✓ |
| `GET /api/v1/operator/lookup` | 200 | ✓ |
| `GET /healthz` | 200 | ✓ |
| `GET /api/v1/sessions` | 200 | ✓ |
| `POST /api/v1/sessions` | 201 | ✓ v1 schema |
| `GET /api/v1/sessions/<id>` | 200 | ✓ |
| `POST /api/v1/sessions/<id>/close` | 200 | ✓ |
| `POST /api/v1/sessions/<id>/telemetry` | 202 | ✓ v1 schema |
| **`POST /api/v1/telemetry`** (top-level) | **202** | ✓ v1 schema |
| **`DELETE /api/v1/sessions/<id>`** | **200** | ✓ `{"deleted":true}` |
| WebRTC (4) | 500 | Backend `WebRTC` config nil (still planned) |

The two missing routes from Sprint 22 / 23.0
(`POST /api/v1/telemetry` and `DELETE /api/v1/sessions/<id>`)
are now active and the Dart client uses both.

## Sprint 24+ backlog

- Real Ed25519 keypair → real `public_key_fp`
- Wire-side TLS ClientHello capture → real `tls_fp`
- Shannon entropy on a rolling window → real `entropy`
- MNP / IP reverse-lookup integration → real `operator`
- WebRTC backend service dependency injection
- WebSocket signalling (replace long-poll `/webrtc/{offer,answer}`)
- Session close hook captures `summary_stats` (the
  current close response is server-side computed; a
  dedicated `/sessions/{id}/finalize` endpoint may
  replace it once the WebRTC service is wired)
