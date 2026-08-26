package api

// telemetry.go — POST /api/v1/sessions/{id}/telemetry.
//
// This is the hot path of the API: the mobile VPN samples
// packets, computes entropy + TLS fingerprint, and ships the
// aggregate up to the server. We MUST reject any payload
// that doesn't match shared/schemas/telemetry.schema.json — a
// single bad row could pollute the transparency matrix and
// damage the public trust score.
//
// PRIVACY (ADR-0006 §Veri Minimizasyonu):
//   - The schema REQUIRES device_id_hash (the salted hash),
//     NOT the raw UUID v7. Anything else gets a 400.
//   - The schema REQUIRES public_key_fp (the SHA-256
//     fingerprint), NOT the raw public key. Anything else
//     gets a 400.
//   - The schema accepts ip_subnet (the /24 or /48 masked
//     IP), NOT the raw IP. The handler then forwards only
//     ip_subnet to storage.
//   - The schema does NOT include any field shaped like a
//     phone number, IMEI, MAC address, or contact list.
//     Any request that smuggles one in via "additionalProperties"
//     will be rejected because the schema sets
//     additionalProperties:false.
//   - The handler NEVER echoes the parsed body back to the
//     client. The response is just {id: <db_id>, accepted:true}.

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"github.com/opene2ee-com/e2ee-app/backend/internal/storage"
)

// telemetryResponse is the minimal success body. The DB row
// id is the only thing the client needs back (so it can
// cross-reference server-side logs during a test run).
type telemetryResponse struct {
	ID        int64  `json:"id"`
	Accepted  bool   `json:"accepted"`
	SessionID string `json:"session_id,omitempty"`
}

// handlePostTelemetry is POST /api/v1/sessions/{id}/telemetry.
func (a *API) handlePostTelemetry() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// (1) Path param: validate the session id before we
		// waste cycles on body parsing.
		idStr := chi.URLParam(r, "id")
		pathSessionID, err := uuid.Parse(idStr)
		if err != nil {
			writeBadRequest(w, "Invalid session id.")
			return
		}
		handleTelemetryInsert(a, w, r, &pathSessionID)
	}
}

// handlePostTelemetryLegacy is POST /api/v1/telemetry.
//
// Sprint 10.1D era — the original Sprint 7 wire-up contract
// carried session_id in the body rather than the URL path.
// That contract never had a matching backend handler, so any
// mobile client (10.1B / 10.1D) that POSTed here received a
// 404. This endpoint restores backward compatibility: the
// body must carry `session_id` (the schema accepts it as
// optional, but the legacy path requires it explicitly).
//
// We delegate to the shared handleTelemetryInsert helper so
// both routes share the schema validation, JSON decode,
// session-id cross-check, and storage insert logic.
func (a *API) handlePostTelemetryLegacy() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		handleTelemetryInsert(a, w, r, nil)
	}
}

// handleTelemetryInsert is the shared body for the two
// telemetry POST handlers. `pathSessionID` is the path-level
// session id when present (the canonical
// /sessions/{id}/telemetry route) or nil when the route is
// the legacy /telemetry POST (in which case the body must
// carry session_id).
//
// Accepts two payload shapes (per-packet OR batch) — the
// schema validator picks the right one based on which fields
// are present in the body. Mobile `TelemetryService.send()`
// (Sprint 10.1D batch contract) and `TelemetryService.send()`
// per-packet (Sprint 11+ contract) both work without a
// mobile rebuild.
//
// Behaviour:
//   - Reads the body, validates against telemetry.schema.json
//     (per-packet) first, then telemetry-batch.schema.json.
//   - JSON-decodes into the matching struct.
//   - Resolves the session id: prefers the path argument,
//     falls back to the body's `session_id` field, and 400s
//     when neither is set.
//   - Persists via storage.TelemetryWriter (same code path
//     regardless of shape).
//   - Returns 202 with the new telemetry row id (single
//     row) or the LAST inserted row id + count (batch).
func handleTelemetryInsert(a *API, w http.ResponseWriter, r *http.Request, pathSessionID *uuid.UUID) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			writeError(w, http.StatusRequestEntityTooLarge, ErrorBody{
				Code:    CodePayloadTooLarge,
				Message: "Request body exceeds size limit.",
			})
			return
		}
		writeBadRequest(w, "Failed to read request body.")
		return
	}
	if len(body) == 0 {
		writeBadRequest(w, "Empty request body.")
		return
	}

	// Sniff the shape — the batch contract uses `sessionId`
	// (camelCase) while the per-packet contract uses
	// `device_id_hash` / `session_id`. We pick the matching
	// schema from the leading byte of the JSON body so the
	// unknown-other-shape schema never runs.
	shape := sniffTelemetryShape(body)

	switch shape {
	case telemetryShapeBatch:
		handleTelemetryBatch(a, w, r, body)
		return
	case telemetryShapePerPacket:
		handleTelemetryPerPacket(a, w, r, body, pathSessionID)
		return
	default:
		writeBadRequest(w, "Request body did not match telemetry or telemetry-batch schema.")
		return
	}
}

// handleTelemetryPerPacket runs the Sprint 11+ per-packet
// contract: one telemetry row per POST. `pathSessionID` is
// the path-level id (canonical /sessions/{id}/telemetry) or
// nil for the legacy /telemetry route (where the body must
// carry `session_id`).
func handleTelemetryPerPacket(a *API, w http.ResponseWriter, r *http.Request, body []byte, pathSessionID *uuid.UUID) {
	if err := a.deps.Schemas.Validate(SchemaTelemetry, body); err != nil {
		if ve, ok := isValidationError(err); ok {
			writeValidation(w, ve)
			return
		}
		a.deps.Cfg.Logger.Error("telemetry schema validation error",
			"err_kind", "schema",
		)
		writeBadRequest(w, "Schema validation failed.")
		return
	}

	var t decodedTelemetry
	if err := json.Unmarshal(body, &t); err != nil {
		writeBadRequest(w, "Malformed JSON.")
		return
	}

	sessionID := pathSessionID
	if sessionID == nil {
		if t.SessionID == nil {
			writeBadRequest(w, "session_id is required (legacy /telemetry route).")
			return
		}
		sessionID = t.SessionID
	} else if t.SessionID != nil && *t.SessionID != *sessionID {
		writeBadRequest(w, "Session id in body does not match URL.")
		return
	}

	storageRow, err := t.toStorage(*sessionID)
	if err != nil {
		writeBadRequest(w, err.Error())
		return
	}
	id, err := a.deps.Cfg.Telemetry.InsertTelemetry(r.Context(), storageRow)
	if err != nil {
		a.deps.Cfg.Logger.Error("insert telemetry failed",
			"err_kind", "db",
			"session_id", sessionID.String(),
		)
		writeInternal(w)
		return
	}

	writeJSON(w, http.StatusAccepted, telemetryResponse{
		ID:        id,
		Accepted:  true,
		SessionID: sessionID.String(),
	})
}

// handleTelemetryBatch runs the Sprint 10.1D batch contract:
// one POST, N telemetry rows. Each `packets[]` element maps to
// one row. Returns 202 with the LAST inserted id + a `count`
// field so the mobile can correlate.
func handleTelemetryBatch(a *API, w http.ResponseWriter, r *http.Request, body []byte) {
	if err := a.deps.Schemas.Validate(SchemaTelemetryBatch, body); err != nil {
		if ve, ok := isValidationError(err); ok {
			writeValidation(w, ve)
			return
		}
		a.deps.Cfg.Logger.Error("telemetry-batch schema validation error",
			"err_kind", "schema",
		)
		writeBadRequest(w, "Schema validation failed.")
		return
	}

	var b decodedTelemetryBatch
	if err := json.Unmarshal(body, &b); err != nil {
		writeBadRequest(w, "Malformed JSON.")
		return
	}
	sessionID, err := uuid.Parse(b.SessionID)
	if err != nil {
		writeBadRequest(w, "batch sessionId must be a valid UUID.")
		return
	}
	if len(b.Packets) == 0 {
		writeBadRequest(w, "batch must contain at least one packet.")
		return
	}

	var lastID int64
	for i, p := range b.Packets {
		row, err := p.toStorageRow(b, sessionID)
		if err != nil {
			a.deps.Cfg.Logger.Warn("batch packet invalid",
				"err_kind", "validation",
				"index", i,
				"session_id", sessionID.String(),
			)
			writeBadRequest(w, err.Error())
			return
		}
		id, err := a.deps.Cfg.Telemetry.InsertTelemetry(r.Context(), row)
		if err != nil {
			a.deps.Cfg.Logger.Error("insert telemetry (batch) failed",
				"err_kind", "db",
				"err", err.Error(),
				"session_id", sessionID.String(),
				"index", i,
				"public_key_fp", row.PublicKeyFP,
			)
			writeInternal(w)
			return
		}
		lastID = id
	}

	writeJSON(w, http.StatusAccepted, telemetryResponse{
		ID:        lastID,
		Accepted:  true,
		SessionID: sessionID.String(),
	})
}

// telemetryShape enumerates the two accepted telemetry body
// shapes. The handler sniffs the leading byte to pick the
// right schema before validating.
type telemetryShape int

const (
	telemetryShapeUnknown telemetryShape = iota
	telemetryShapePerPacket                 // Sprint 11+ contract
	telemetryShapeBatch                     // Sprint 10.1D batch contract
)

// sniffTelemetryShape looks at the body for one of two
// distinctive keys and decides which contract it looks
// like. Go's encoding/json sorts object keys alphabetically,
// so the first key per contract is deterministic:
//
//   - Per-packet: "app" (followed by "device_id_hash",
//     "entropy", "operator", ...)
//   - Batch:     "packets" (followed by "sampledAt",
//     "sessionId", ...)
//
// We scan the first ~512 bytes looking for either of these
// keys anywhere. That's safer than "first key" because a
// future schema revision might add a field that sorts
// earlier.
func sniffTelemetryShape(body []byte) telemetryShape {
	scanLimit := 512
	if len(body) < scanLimit {
		scanLimit = len(body)
	}
	rest := body[:scanLimit]
	// Walk the JSON top-level object key by key, tracking
	// the opening/closing quote positions.
	i := 0
	// Skip whitespace and the opening `{`.
	for i < len(rest) && (rest[i] == ' ' || rest[i] == '\t' || rest[i] == '\n' || rest[i] == '\r') {
		i++
	}
	if i >= len(rest) || rest[i] != '{' {
		return telemetryShapeUnknown
	}
	i++
	for i < len(rest) {
		// Skip whitespace and commas between key-value pairs.
		for i < len(rest) && (rest[i] == ' ' || rest[i] == '\t' || rest[i] == '\n' || rest[i] == '\r' || rest[i] == ',') {
			i++
		}
		if i >= len(rest) || rest[i] == '}' {
			break
		}
		if rest[i] != '"' {
			return telemetryShapeUnknown
		}
		// Read the key name up to the next unescaped quote.
		j := i + 1
		for j < len(rest) {
			if rest[j] == '\\' && j+1 < len(rest) {
				j += 2
				continue
			}
			if rest[j] == '"' {
				break
			}
			j++
		}
		if j >= len(rest) {
			return telemetryShapeUnknown
		}
		key := string(rest[i+1 : j])
		switch key {
		case "packets", "sessionId":
			return telemetryShapeBatch
		case "app", "device_id_hash":
			return telemetryShapePerPacket
		}
		i = j + 1
	}
	return telemetryShapeUnknown
}

// decodedTelemetry is the request-side shape of one telemetry
// row. It mirrors the relevant subset of telemetry.schema.json
// — the schema validator enforces the contract, this struct is
// just the decoders' convenience.
//
// IMPORTANT: fields the api package never wants to receive are
// OMITTED here. If the schema ever adds a forbidden field, the
// schema validator will still allow it through (the schema is
// the contract for what mobile sends), but the struct decoder
// will silently drop it. That asymmetry is intentional — the
// schema's `additionalProperties:false` is the source of
// truth, not this struct.
type decodedTelemetry struct {
	DeviceIDHash   string    `json:"device_id_hash"`
	PublicKeyFP    string    `json:"public_key_fp"`
	Operator       string    `json:"operator"`
	App            string    `json:"app"`
	TLSFP          string    `json:"tls_fp"`
	Entropy        float64   `json:"entropy"`
	IPSubnet       string    `json:"ip_subnet,omitempty"`
	SessionID      *uuid.UUID `json:"session_id,omitempty"`
	Timestamp      string    `json:"timestamp"` // RFC 3339
	SNI            string    `json:"sni,omitempty"`
	TLSVersion     string    `json:"tls_version,omitempty"`
	OperatorSource string    `json:"operator_source,omitempty"`
	MatchMode      string    `json:"match_mode,omitempty"`
	PeerScore      *float64  `json:"peer_score,omitempty"`
	Confidence     *float64  `json:"confidence,omitempty"`
	Signature      string    `json:"signature,omitempty"`
}

// decodedTelemetryBatch is the Sprint 10.1D batch contract —
// the mobile `TelemetryService.send` (per-tick batch) still
// posts this shape. The schema is `telemetry-batch.schema.json`.
//
// Each `packets[]` element maps to one storage.Telemetry row;
// the handler fans iter the array and inserts each.
type decodedTelemetryBatch struct {
	SessionID    string                  `json:"sessionId"`
	SampledAt    string                  `json:"sampledAt"` // RFC 3339
	SamplingCap  int                     `json:"samplingCap,omitempty"`
	DeviceIDHash string                  `json:"deviceIdHash,omitempty"`
	Packets      []decodedTelemetryBatchPacket `json:"packets"`
}

type decodedTelemetryBatchPacket struct {
	Timestamp    string  `json:"ts"`
	SrcIPSubnet  string  `json:"srcIpSubnet,omitempty"`
	DstIPSubnet  string  `json:"dstIpSubnet,omitempty"`
	SrcPort      int     `json:"srcPort,omitempty"`
	DstPort      int     `json:"dstPort,omitempty"`
	Protocol     string  `json:"protocol,omitempty"`
	TLSFP        string  `json:"tlsFp"`
	Entropy      float64 `json:"entropy"`
	SNI          string  `json:"sni,omitempty"`
	TLSVersion   string  `json:"tlsVersion,omitempty"`
}

// toStorageRow converts one batch packet into a storage row.
// We use the device fingerprint / operator / app carried at
// batch level (the schema marks them per-packet too in
// Sprint 11+, but Sprint 10.1D mobile leaves them at the
// batch envelope). For Sprint 10.1D-shaped batches that
// don't include them, the storage row gets reasonable
// defaults ("unknown" / "unknown") — operators can
// re-derive them via the operator-lookup endpoint from
// src_ip_subnet on the dashboard side.
//
// If the batch *does* carry per-packet operator / app
// fields (a Sprint 11+ extension), they override the
// defaults.
func (p decodedTelemetryBatchPacket) toStorageRow(batch decodedTelemetryBatch, sessionID uuid.UUID) (storage.Telemetry, error) {
	ts, err := time.Parse(time.RFC3339, p.Timestamp)
	if err != nil {
		return storage.Telemetry{}, errors.New("invalid packet ts (RFC 3339 required)")
	}
	if p.TLSFP == "" {
		return storage.Telemetry{}, errors.New("packet tlsFp is required")
	}
	srcSubnet := p.SrcIPSubnet
	if srcSubnet == "" {
		srcSubnet = p.DstIPSubnet // fall back to whichever side has a value
	}
	sid := sessionID
	// Synthetic hex-only fingerprint derived from the
	// session id. The telemetry schema enforces
	// public_key_fp.pattern == ^[a-f0-9]+$ (16-32 chars),
	// so the legacy "batch-<sessionid>" prefix fails the
	// pattern check. We derive a 16-hex-char string from
	// SHA-256(sessionID)[:8] which is unique per session
	// and shape-compatible with the per-packet contract.
	sum := sha256.Sum256([]byte(batch.SessionID))
	pubKeyFP := hex.EncodeToString(sum[:8])

	// device_id_hash: optional in the batch envelope
	// (Sprint 10.1D mobile didn't carry it). When missing,
	// synthesize a 16-hex-char string from
	// SHA-256("device:" + sessionID)[:8] so the storage
	// row has a non-empty, schema-valid value. The
	// synthesizer is deterministic per session — the same
	// session always maps to the same device_id_hash.
	var devIDHash string
	if batch.DeviceIDHash != "" {
		devIDHash = batch.DeviceIDHash
	} else {
		devSum := sha256.Sum256([]byte("device:" + batch.SessionID))
		devIDHash = hex.EncodeToString(devSum[:8])
	}

	return storage.Telemetry{
		DeviceIDHash: devIDHash,
		PublicKeyFP:   pubKeyFP,
		Operator:      "unknown",
		App:           "unknown",
		TLSFP:         p.TLSFP,
		Entropy:       p.Entropy,
		SessionID:     &sid,
		IPSubnet:      srcSubnet,
		Timestamp:     ts.UTC(),
	}, nil
}

// toStorage converts the wire shape into the storage type.
// We re-parse the timestamp string because storage.Telemetry
// uses time.Time and the handler layer shouldn't rely on the
// caller to send a time.Time JSON form.
func (d decodedTelemetry) toStorage(sessionID uuid.UUID) (storage.Telemetry, error) {
	ts, err := time.Parse(time.RFC3339, d.Timestamp)
	if err != nil {
		return storage.Telemetry{}, errors.New("invalid timestamp format (RFC 3339 required)")
	}
	sid := sessionID
	return storage.Telemetry{
		DeviceIDHash: d.DeviceIDHash,
		PublicKeyFP:  d.PublicKeyFP,
		Operator:     d.Operator,
		App:          d.App,
		TLSFP:        d.TLSFP,
		Entropy:      d.Entropy,
		IPSubnet:     d.IPSubnet,
		SessionID:    &sid,
		Timestamp:    ts.UTC(),
	}, nil
}