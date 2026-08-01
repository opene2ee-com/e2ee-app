"""Sprint 23.2 — verify the new v1 telemetry observation
matches what Dart's `TelemetryService.fromStubs` produces.

The Dart stub for `public_key_fp` is:
    sha256("e2ee-pkfp:" + device_id_hash)[:16]
The Dart stub for `tls_fp` is:
    sha256("e2ee-ap-v2-v1-tls-fingerprint-stub")[:16]
operator = "unknown"
app = "whatsapp"
entropy = 0.0 (default)

This script posts one observation that exactly matches the
Dart-side shape and confirms the backend accepts it.
"""
import time, json, hashlib, urllib.request, urllib.error, jwt

BASE = "https://api-test.opene2ee.com"
SECRET = "BjZMNJdYp9rFibke70Jeo7X6M0iunraNoi8i8tYePiF"
DEVICE_ID_RAW = f"mavis-v1-obs-{int(time.time())}"
DEVICE = hashlib.sha256((DEVICE_ID_RAW + "e2ee-server-salt-2026").encode()).hexdigest()[:16]

# Dart-side stubs:
PK_FP = hashlib.sha256(f"e2ee-pkfp:{DEVICE}".encode()).hexdigest()[:16]
TLS_FP = hashlib.sha256(b"e2ee-ap-v2-v1-tls-fingerprint-stub").hexdigest()[:16]

now = int(time.time())
token = jwt.encode(
    payload={"iss": "opene2ee-backend", "sub": DEVICE,
             "iat": now, "exp": now + 3600, "jti": f"v14-{now}"},
    key=SECRET, algorithm="HS256",
)
h = {"X-API-Version": "1", "Content-Type": "application/json",
     "Authorization": f"Bearer {token}"}


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method, headers=h)
    try:
        with urllib.request.urlopen(r, timeout=10) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def short(b, n=200):
    return b if len(b) < n else b[:n] + "..."


print(f"device_id_hash: {DEVICE}  (length {len(DEVICE)})")
print(f"public_key_fp:  {PK_FP}  (length {len(PK_FP)})")
print(f"tls_fp:         {TLS_FP}  (length {len(TLS_FP)})")
print()

# ── 1. Create session ──
print("=== Create session ===")
s, b = call("POST", "/api/v1/sessions",
            body={"device_id_hash": DEVICE, "mode": "p2p",
                  "task_type": "whatsapp_text"})
print(f"  [{s}] {short(b)}")
session_id = json.loads(b).get("id") if s in (200, 201) else None
print(f"  session_id: {session_id}")
print()

# ── 2. POST /api/v1/telemetry (v1 observation, matches Dart) ──
print("=== POST /api/v1/telemetry (v1 observation, exactly Dart shape) ===")
body = {
    "device_id_hash": DEVICE,
    "public_key_fp": PK_FP,
    "operator": "unknown",
    "app": "whatsapp",
    "tls_fp": TLS_FP,
    "entropy": 0.0,
    "timestamp": "2026-08-02T00:00:00Z",
    "session_id": session_id,
    "match_mode": "p2p",
    "peer_score": 95.0,
    "confidence": 0.9,
}
s, b = call("POST", "/api/v1/telemetry", body=body)
print(f"  [{s}] {short(b)}")
print()

# ── 3. POST /sessions/<id>/telemetry (same shape) ──
print("=== POST /sessions/<id>/telemetry (same shape) ===")
if session_id:
    s, b = call("POST", f"/api/v1/sessions/{session_id}/telemetry", body=body)
    print(f"  [{s}] {short(b)}")
print()

# ── 4. Close session ──
print("=== POST /sessions/<id>/close ===")
if session_id:
    s, b = call("POST", f"/api/v1/sessions/{session_id}/close",
                body={"closed_at": "2026-08-02T00:00:00Z"})
    print(f"  [{s}] {short(b)}")
    if s in (200, 201):
        j = json.loads(b)
        if "summary_stats" in j:
            ss = j["summary_stats"]
            print(f"  summary_stats: total={ss.get('total_packets')}, "
                  f"encrypted={ss.get('encrypted_packets')}")
print()

# ── 5. DELETE session ──
print("=== DELETE /sessions/<id> ===")
if session_id:
    s, b = call("DELETE", f"/api/v1/sessions/{session_id}")
    print(f"  [{s}] {short(b)}")
print()

print("Done.")
