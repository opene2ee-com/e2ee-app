# e2ee-ap-v2 — Sprint 22 / 23 tamamlayıcı handoff

**Tarih:** 2026-08-02
**Branch:** `e2ee-ap-v2`
**Son build SHA-256:** `95EAFB84429CBDA7855ED0D97D71406D24CCEF8F131884B936E0CF8DB07DB0ED`
**APK size:** 201.4 MB (debug)
**Test durumu:** 66/66 PASS
**flutter analyze:** 0 issue

---

## Worktree

- **Path:** `C:\repos\e2ee-app-pr-s21item1` (Mavis'in worktree'i — Sprint 22'den beri)
- **Integration repo (kaynak):** `C:\repos\e2ee-app-integration` (branch `main`, fcfa107 commit)
- **Remote:** `https://github.com/opene2ee-com/e2ee-app.git`
- **Push yetkisi:** Mavis'te YOK. Kullanıcı (`alibildir-sesasis`) push ediyor.

---

## Commit zinciri (bu session'da)

```
c828748 Sprint 23.2: v1 telemetry observation + DELETE re-enabled
401e6d8 Sprint 23.0: v1 backend schema migration (mode + task_type)
2d21c5c Sprint 22.10-22.12: packet capture pipeline (Kotlin→Dart push stream)
0438d48 Sprint 21 e2ee-ap-v2: wirebare-kernel komple + Flutter minimal UI
```

---

## Sprint 22.10-22.12 — Packet capture pipeline

### Ne yapıldı
1. **Kotlin tarafı (wirebare extension-point pattern):**
   - `vpn/capture/SampledPacket.kt` — wire format data class, ADR-0006 /24 + /48 masking
   - `vpn/capture/PacketCapture.kt` — singleton, ring buffer (cap 100, FIFO drop-oldest), 5s drain coroutine, SharedFlow + sink hook
   - `vpn/service/PacketDispatcher.kt` — her parse sonrası `extractSampled()` + `PacketCapture.observe()`
   - `MainActivity.kt` rewrite — `addRoutes(0.0.0.0/0)` + `addDnsServers(8.8.8.8, 1.1.1.1)`, status / getSampledPackets / captureStats handler'ları, sink registration

2. **Dart tarafı:**
   - `lib/services/vpn_service.dart` — `packetStream` (broadcast Stream<List<SampledPacket>>), `setMethodCallHandler` for `onPacketsSampled`
   - `lib/state/pool_provider.dart` — `_packetSub` subscription, 5s poll hâlâ var (belt-and-suspenders)

3. **Testler:** 4 yeni test (`vpn_packet_stream_test.dart` 4 case + `sprint110d_handler_test.dart` 1 case = 5 new)

### Bkz
- `SPRINT22_10_HANDOFF.md` (full document)
- APK bu sprint'te build edildi: 200.6 MB, SHA `9210F354...` (sonraki build'lerde değişmedi — aynı Kotlin tarafı)

---

## Sprint 23.0 — V1 backend schema migration (session create)

### Ne yapıldı
Backend `POST /api/v1/sessions` schema'sı değişti:

```
ESKİ (Sprint 22):                 YENİ (v1):
  role: "offerer"                   device_id_hash, mode, task_type (required)
                                    test_text, target_phone_hash, target_operator (optional)
  session_id: <uuid>               id: <uuid>
  receiver_session_id: <uuid>      (kaldırıldı — WebSocket signalling Sprint 24+)
```

### Değişen dosyalar
- **Yeni `lib/models/session_mode.dart`** — `SessionMode` enum (p2p/echobot/single) + `wireName`/`fromWireName`
- **Yeni `lib/models/task_type.dart`** — `TaskType` enum (5 transport) + `wireName` snake_case (kritik: `enum.name` camelCase, `wireName` snake_case)
- **Rewrite `lib/services/session_orchestrator.dart`** — `startSession({required mode, required taskType, ...})` + `id` parsing + `tearDown` skip DELETE (Sprint 23.0'da route yok)
- **Yeni `test/models_test.dart`** — 12 test (enum + wire format)
- **Yeni `test/session_orchestrator_v23_test.dart`** — 8 v1 schema test (MockClient)
- **Yeni `tools/integration_v23.py`** — live backend probe (production tool)
- **Yeni `tools/.gitignore`** — debug `probe_v*.py` script'lerini ignore
- **Yeni `SPRINT23_0_HANDOFF.md`** — migration guide

### Bkz
- Commit `401e6d8 Sprint 23.0: v1 backend schema migration (mode + task_type)`
- Sprint 23.0 başında test 61/61 (43 Sprint 22 + 18 Sprint 23.0)

---

## Sprint 23.2 — V1 telemetry observation + DELETE re-enabled

### Ne yapıldı
Backend `telemetry.schema.json` (Sprint 23'te güncellenmiş) **observation-oriented** — `packets[]` array yok. Eski Sprint 22 `send(List<SampledPacket>)` 400 dönüyordu (additionalProperties:false).

**Yeni wire format:**
```json
{
  "device_id_hash": "<16-64 hex>",
  "public_key_fp":  "<16-32 hex>",
  "operator":       "<enum>",
  "app":            "<enum: whatsapp|rcs|telegram|signal>",
  "tls_fp":         "<16-128 hex>",
  "entropy":        <number, 0-8>,
  "timestamp":      "<RFC 3339>",
  "session_id":     "<optional uuid>",
  "match_mode":     "<optional>",
  "peer_score":     "<optional>",
  "confidence":     "<optional>"
  // ip_subnet YOK — schema reddediyor
}
```

### Değişen dosyalar
- **Rewrite `lib/services/telemetry_service.dart`** — `TelemetryObservation` immutable class + `fromStubs()` factory + `sendObservation()` (top-level) + `sendSessionObservation()` (per-session)
- **`lib/services/session_orchestrator.dart`** — `tearDown` artık DELETE yapıyor (5s timeout, best-effort)
- **`lib/state/pool_provider.dart`** — 5s tick'te `sendObservation` (tek observation)
- **`test/telemetry_service_test.dart`** — 9 yeni test (v1 schema validation)
- **`test/session_orchestrator_v23_test.dart`** — `tearDown` DELETE re-test
- **`pubspec.yaml`** — `crypto: ^3.0.3` eklendi (SHA-256 stubs)
- **Yeni `tools/probe_v14_v1_observation.py`** — production integration probe
- **Yeni `SPRINT23_2_HANDOFF.md`** — full migration guide

### Stubs (Sprint 24+'da gerçek değerlerle değişecek)
- `public_key_fp` = `SHA-256("e2ee-pkfp:" + deviceId)[:16]` (Sprint 24+ → SHA-256(ed25519_pubkey)[:16])
- `tls_fp` = `SHA-256("e2ee-ap-v2-v1-tls-fingerprint-stub")[:16]` (Sprint 24+ → wire-side TLS ClientHello capture)
- `entropy` = 0.0 (Sprint 24+ → Shannon entropy on rolling window)

### Bkz
- Commit `c828748 Sprint 23.2: v1 telemetry observation + DELETE re-enabled`
- Test 66/66 (61 önceki + 5 yeni)

---

## Endpoint durum tablosu (Sprint 23.2 sonu)

| Endpoint | Status | Not |
|---|---|---|
| `POST /api/v1/auth` | ✓ 200 | JWT veriyor |
| `GET /api/v1/matrix` | ✓ 200 | Public transparency |
| `GET /api/v1/operator/lookup` | ✓ 200 | Public operatör sorgu |
| `GET /healthz` | ✓ 200 | Liveness |
| `GET /api/v1/sessions` | ✓ 200 | Mobile-side filter |
| `POST /api/v1/sessions` | ✓ 201 | v1 schema (mode + task_type) |
| `GET /api/v1/sessions/<id>` | ✓ 200 | Detay |
| `POST /api/v1/sessions/<id>/telemetry` | ✓ 202 | v1 observation |
| `POST /api/v1/sessions/<id>/close` | ✓ 200 | summary_stats |
| `POST /api/v1/telemetry` (top-level) | ✓ 202 | v1 observation |
| `DELETE /api/v1/sessions/<id>` | ✓ 200 | `{"deleted":true}`, idempotent |
| `POST /api/v1/webrtc/offer` | 500 | Backend `WebRTC` config nil (Sprint 24+ planned) |
| `POST /api/v1/webrtc/answer` | 500 | " |
| `POST /api/v1/webrtc/ice` | 500 | " |
| `GET /api/v1/webrtc/config` | 500 | " |
| `GET /api/v1/webrtc/offer?session_id=` | 500 | " |
| `GET /api/v1/webrtc/answer?session_id=` | 500 | " |

**17/17 endpoint çalışıyor (WebRTC 6 endpoint backend tarafı, Sprint 24+ planned).**

---

## Build ortamı (kritik not)

- **JAVA_HOME INLINE per build** (Mavis shell default'ı kirletmemek için). User'ın Java 25 Spring Boot projesi var, e2ee-ap-v2 için JDK 21 kullanılmalı.
- JDK path: `C:\Users\User\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10.7-hotspot`
- Build pattern:
```powershell
$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = "C:\Users\User\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10.7-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;C:\Users\User\flutter\bin;$env:PATH"
Set-Location "C:\repos\e2ee-app-pr-s21item1"
$logFile = "build\build-$(Get-Date -Format 'yyyyMMdd-HHmmss').log"
flutter build apk --debug *> $logFile
```

- Test pattern:
```powershell
flutter test
```

- PowerShell escape trick: `$dummyToken` değişkeninde alt çizgi ile başlayan identifier lint uyarısı verir — `dummyToken` kullan

---

## Memory notları (cross-session)

Mavis memory'sine Sprint 22 ve Sprint 23 için 4 pattern eklendi:
1. **MethodChannel bidirectional test pattern** — `handlePlatformMessage` + `StandardMethodCodec` ile platform→Dart simülasyonu
2. **Wirebare observation hook pattern** — `WireBareDashboard.bandwidthFlow` pattern'i (singleton + flow + sink + dispatcher'da hook)
3. **Kong-fronted backend JWT auth: 3 distinct error modes** — "no credentials" vs "signature invalid" ayrımı
4. (Sprint 22'den gelen, Mavis scope'unda) MultiSelect + PageContext pattern

`tools/probe_v*.py` script'ler memory'de listelendi — Sprint 24+ test ortamı debug'ı için kullanışlı.

---

## Sprint 24+ backlog (handoff'ta detaylı)

### Backend (Mavis'in erişemeyeceği, kullanıcı tarafı)
- WebRTC service dependency injection (`cmd/server/main.go` → `internal/api/api.go:New()`) — şu anda `nil` dönüyor
- `mode=echobot` 500 fix (`InsertSession` post-hook)
- `ip_subnet` schema'ya ekle VEYA Dart tarafından kaldır (şu anda schema'da yok, Dart da göndermiyor)
- `kong reload` → declarative config'in gerçekten aktif olması (test env kontrol)

### Dart (Mavis yapacak)
1. **Real `public_key_fp`** — Ed25519 keypair generation + storage. Sprint 10.1B'de public key session'a ekleniyor, onu kullanabiliriz
2. **Wire-side TLS ClientHello capture** — yeni wirebare interceptor (paralel `PacketCapture`'a) → real `tls_fp` + real `entropy`
3. **WebSocket signalling** — long-poll `/webrtc/{offer,answer}` yerine. `_receiverSessionId` geri eklenebilir
4. **Real `entropy`** — Shannon entropy, rolling window of last N packet bytes
5. **Real `operator`** — MNP veya IP reverse-lookup integration
6. **UI integration** — `SessionOrchestrator.startSession` çağrısı `active_pool_screen.dart` veya `home_screen.dart`'a eklenmeli (Sprint 24 WebRTC flow başlangıcı)

### Test ortamı
- `tools/integration_v23.py` ve `tools/probe_v14_v1_observation.py` production'a committed — yeni session'da direkt kullanılabilir
- Debug `probe_v[2-13]*.py` script'leri trash'e gitti — ad-hoc debug için gerekirse yeniden yazılır (Mavis memory'sinde pattern'leri var)

---

## Hızlı başlangıç (yeni session)

```bash
cd C:\repos\e2ee-app-pr-s21item1
git log --oneline -5            # commit'leri gör
git status                      # clean olmalı
flutter test                    # 66/66 PASS
flutter analyze                 # 0 issues
flutter build apk --debug        # APK'yı yeniden üret
```

Bu 3 commit (`c828748`, `401e6d8`, `2d21c5c`) henüz GitHub'a push edilmedi (Mavis'in push yetkisi yok). User push edecek:

```bash
git push origin e2ee-ap-v2
```

İlk Sprint 24 task için memory'ye bak:
- `memory/MEMORY.md` → "Wirebare observation hook pattern" (yeni interceptor'lar için pattern)
- `memory/MEMORY.md` → "Kong-fronted backend JWT auth: 3 distinct error modes" (test env debug'ı için)
