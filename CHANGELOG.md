# Changelog

All notable changes to the opene2ee app are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased] - Sprint 12.0F+2 (TCP SYN RST workaround + R8 keep rules)

### Critical fixes

- **Sprint 12.0F+2 - R8 minify log breadcrumb fix (Fix 1)** - Owner 16:24-16:29 test of the 12.0F+1 APK (`SHA256: AE734AD37C7A4EFF706F01DBED83D3A129A971EB75EAD5CD880CC753D9153635`, 82.29 MB) showed 0 occurrences of the 12.0F+1 breadcrumbs: `handleTcpPacket: dispatching flags=0x`, `buildVpnBuilder: allowedApps=`, `checkPrivateDnsAndBindToVpn`. Root cause: R8 (release minifier) stripped the 3 breadcrumb Log.d calls because the return value is unused and the call has no obvious side effect on the program's data flow. Coder 12.0F+1 commit message was incorrect: "R8/proguard 12.0F+1 Log.d strings'i temizlemedi" was wrong — R8 DID remove the strings. Fix: added 3 keep rules to `mobile/android/app/proguard-rules.pro`:

  1. **`-keepclassmembers,allowobfuscation class * { *** Log*(...); }`** — preserves ALL `android.util.Log.*` method calls on ANY class (wildcard `*`). `allowobfuscation` keeps name mangling for the methods themselves (so attackers cannot grep `handleTcpPacket` in the obfuscated DEX and trace the call back to source). S122-4 audit verifies this rule is present.
  2. **`-keepclassmembers,allowobfuscation class * { public static final java.lang.String TAG; }`** — preserves the `TAG` field as a distinct constant. Without this, R8 may fold/inline the `TAG` literal as a primitive `String` constant in the bytecode, which still leaves the literal present but may confuse some grep patterns. S122-5 audit verifies this rule is present.
  3. **`-keepclassmembers class * { @androidx.annotation.Keep *; }`** + `-keep @androidx.annotation.Keep class * { *; }` + `-keep,allowobfuscation @interface androidx.annotation.Keep` — preserves all members annotated with `@androidx.annotation.Keep`. R8 respects `@Keep` natively but the keep rules are belt-and-braces in case R8 treats `@Keep` as a soft hint during partial evaluation. S122-6 audit verifies at least 1 `@Keep` annotation is used in our code (on the new `writeTcpRstToTun` function).

- **Sprint 12.0F+2 - TCP SYN RST workaround (Fix 2, Ali's suggestion)** - 10 saniye timeout confirmed by Owner test. Root cause: kernel TCP stack established the connection BEFORE our user-space stack saw the SYN (the "established connection cache" survives VPN reconfiguration). The kernel routes the 3-way handshake via the real NIC, our user-space stack only sees the data packets of the now-established connection. Fix: instead of silently dropping unknown-flow packets, synthesize a TCP RST packet and write it back to the TUN. The kernel delivers the RST to the app, the app tears down the connection and IMMEDIATELY retransmits a fresh SYN. Our user-space stack sees the NEW SYN and `handleSyn()` can do its normal 3-way handshake via `protect()`'d `java.net.Socket`. Net effect: 1-2 second "blip" for the user instead of a 10-second stall.

  Implementation: new `writeTcpRstToTun(srcIp, dstIp, srcPort, dstPort, seqNum, ackNum, flowKey)` function in `TcpForwarder`. RST packet format per RFC 793 §3.5: 20-byte IP header (srcIp/dstIp SWAPPED so the RST goes BACK to the app, protocol=6) + 20-byte TCP header (srcPort/dstPort SWAPPED, flags=RST+ACK=0x14, seqNum=ackNum, ackNum=seqNum+1, payload=empty). Function is annotated with `@androidx.annotation.Keep` for belt-and-braces protection against R8 inlining.

  Called from 4 "unknown flow" branches:
    - `handleData` (PSH+ACK for unknown/no-socket flow) — 2 places (conn == null OR state != ESTABLISHED)
    - `handleTcpPacket` ACK case (bare ACK for unknown flow)
    - `handleFinAck` (FIN+ACK for unknown flow)
  Each call logs a `writeTcpRstToTun: dispatching RST for flow X` warning breadcrumb so the Owner can confirm the workaround fired.

### Owner's 7-step test akışı (S121-4 / S122-7)

Per Owner 12.0F+1 test (10s timeout, log at `C:\Users\User\Downloads\logcat120fplus1_v1.txt` line 13-22), the 6-step test akışı was extended to 7 steps with the RST recovery scenario. Tag 4 filter remains `OpenE2eeVpn:V TcpForwarder:V UdpForwarder:V NettyChannelClient:V`.

  1. **VPN KAPALI** (toggle OFF in uygulama).
  2. **Uygulamayı force-stop**: `adb shell am force-stop com.opene2ee.opene2ee`.
  3. **First request (real NIC)**: Open uygulama, send first request to `212.64.210.85:443` (e.g., "Aktif Nöbet başlat" → `POST /api/v1/sessions`). At this point the VPN is OFF so the SYN goes via the real NIC. The request should succeed (200 OK or similar).
  4. **VPN AÇ** (toggle ON in uygulama, OR `adb shell am start -a android.net.VpnService`).
  5. **Second request (should trigger RST)**: Open uygulama, send a SECOND request to `212.64.210.85:443`. The kernel has already established the connection via real NIC in step 3, so our user-space stack sees only the data packets (not the SYN). The `writeTcpRstToTun` workaround should fire.
  6. **Wait 10 seconds for timeout**: give the RST recovery time to cycle (RST → app retransmits → new SYN → user-space stack handles).
  7. **Expected outcomes** (one of two):
     - **Direct response**: SYN kuruldu ilk seferde, RST gerekmedi (the kernel captured the SYN into the user-space stack — unlikely given the 12.0F+1 test but possible if the Owner fully rebooted between steps).
     - **RST recovery (1-2s blip)**: önce 1-2 saniye timeout (RST gönderildi, app retransmit yaptı), sonra response (yeni SYN user-space stack tarafından handle edildi, bağlantı kuruldu, server response geldi). Owner greps `writeTcpRstToTun: dispatching RST` in logcat to confirm the workaround fired.

  **Log al**: `adb logcat -d -s "OpenE2eeVpn:V TcpForwarder:V UdpForwarder:V NettyChannelClient:V" | grep -E "handleTcpPacket: dispatching flags=0x|allowedApps=|checkPrivateDnsAndBindToVpn|writeTcpRstToTun: dispatching RST"`

  **Decision matrix after the 7-step test**:
    - `handleTcpPacket: dispatching flags=0x` fires 1+ times → R8 keep rules WORK, the 12.0F+1 breadcrumbs are preserved. If the SYN's `SYN=true` AND `writeTcpRstToTun: dispatching RST` fires once → the kernel-bypass recovery is working (RST was sent for the first stale packet, app retransmits, new SYN is captured by user-space stack).
    - `handleTcpPacket: dispatching flags=0x` STILL 0 → R8 keep rules NOT enough; check if R8 was upgraded to a version that ignores `@Keep` (unlikely) or if the Log.d was inlined by the kotlin compiler (R8 sees the inlined call, not the `@Keep` annotation). Fix: switch to a `synchronized(loggingMutex) { ... }` block around the Log.d call (R8 cannot remove synchronized blocks because they have visible side effects on the lock state).
    - `writeTcpRstToTun: dispatching RST` fires but app still times out → RST was sent but app's TCP retransmit didn't reach the TUN (the kernel keeps using the real NIC for the established connection). Fix 3 (best effort): add `bindProcessToNetwork` retry with a 5s delay (the brief's Fix 3 — best effort only).

### S122 audit (added in this sprint)

- **S122-1**: `fun writeTcpRstToTun` function exists in `TcpForwarder` (grep the function declaration).
- **S122-2**: `writeTcpRstToTun(` call in `handleData` "unknown/no-socket flow" branch (grep).
- **S122-3**: `writeTcpRstToTun(` call in `handleAck` OR `handleFinAck` "unknown flow" branch (at least 1 of the 2).
- **S122-4**: `proguard-rules.pro` contains `-keepclassmembers,allowobfuscation class * { *** Log*(...); }` rule.
- **S122-5**: `proguard-rules.pro` contains `-keepclassmembers,allowobfuscation class * { public static final java.lang.String TAG; }` rule.
- **S122-6**: `import androidx.annotation.Keep` in `OpenE2eeVpnService.kt` AND at least 1 `@Keep` annotation on a member (e.g., `@Keep private fun writeTcpRstToTun`).
- **S122-7**: `CHANGELOG.md` has `Sprint 12.0F+2` section + `Tag 4 filter` + `7-step` documentation.

### Reference

- Log: `C:\Users\User\Downloads\logcat120fplus1_v1.txt` (663 satır, 0.18 MB, Tag 4 filter)
- 12.0F+1 APK: `SHA256: AE734AD3...` (NOT WORKING, breadcrumblar GELMEDI, R8 minify kaldırmış)
- Ali's analysis: kernel established TCP connection via real NIC (routing table exception + "established connection cache" survives VPN reconfiguration), fix: synthesize RST for unknown-flow packets

## [Unreleased] - Sprint 12.0F+1 (TCP SYN processing debug)

## [Unreleased] - Sprint 12.0F+1 (TCP SYN processing debug)

### Diagnostics

- **Sprint 12.0F+1 - TCP SYN processing debug breadcrumbs** - Owner 12.0F logcat analysis (`C:\Users\User\Downloads\logcat120f_v3.txt` line 17-21) showed 9 dispatch events all carried PSH+ACK, 0 SYN. The TcpForwarder SYN path was never exercised because TCP SYN packets bypass the VPN TUN (kernel routes them via the real NIC). TcpForwarder therefore never created a Socket, and the subsequent PSH+ACK data packets were dropped with "no-socket flow" (no SYN handler fired first to insert the conn into tcpConnectionMap). Root cause is one of 4 hypotheses: (1) VPN setup timing — TCP established before VPN opened, (2) `addAllowedApplication` only for own app, (3) `bindProcessToNetwork` timing, (4) kernel routing exception. Sprint 12.0F+1 adds 2 diagnostics so the Owner can pinpoint the root cause in their next live test:

  1. **`handleTcpPacket: dispatching flags=0x.. (SYN=.., ACK=.., PSH=.., FIN=.., RST=..)` breadcrumb** in `TcpForwarder.handleTcpPacket` (every captured TCP packet). The Owner greps this token in logcat and can now distinguish "all packets are PSH+ACK" (kernel SYN bypass) from "SYN IS present" (dispatch precedence bug). The flag decode uses the same RFC 793 bit constants the dispatch precedence does (`TCP_SYN=0x02`, `TCP_ACK=0x10`, `TCP_PSH=0x08`, `TCP_FIN=0x01`, `TCP_RST=0x04`).

  2. **`buildVpnBuilder: allowedApps=N packages=[...]` breadcrumb** at `buildVpnBuilder` entry. The Owner greps this token to confirm whether the VPN is restricted to a single package (suspicious for the OpenE2EE flow where Owner's other apps should also be captured). The default behavior is `allowedApps=0 packages=[]` (the VPN captures ALL traffic — Chrome / WhatsApp / system apps included; matches the per-route `addRoute(0.0.0.0/0)` default). If the Owner sees `allowedApps=1 packages=[com.opene2ee.opene2ee]`, the VPN is restricted to a single package and Chrome/system apps bypass the TUN.

### Owner's 6-step test akışı (S121-4)

Per Owner 12.0F logcat analysis: TCP SYN paketleri TUN'a hiç ulaşmıyor (9 dispatch'in 9'u da PSH+ACK, 0 SYN). The 4 hypotheses (timing, allowedApps, bindProcess, kernel) need a controlled test to disambiguate. The 6-step test akışı below is the canonical procedure:

  1. **VPN KAPALI** (toggle OFF in uygulama).
  2. **Uygulamayı KAPAT** (swipe to dismiss from recents OR `adb shell am force-stop com.opene2ee.opene2ee`). This is critical: any TCP bağlantı that was alive while the VPN was up will continue using the VPN's protected sockets even after the app is backgrounded, and any NEW TCP bağlantı from a freshly-launched app will use the kernel routing table. A force-stop ensures the JVM is killed and the next launch starts from a clean slate.
  3. **Uygulama içinden 212.64.210.85:443'e istek gönder** (e.g., the "Aktif Nöbet başlat" button → `POST /api/v1/sessions`). At this point the VPN is OFF so the SYN goes via the real NIC, the kernel TCP stack handles the 3-way handshake, and the data reaches the server. The Owner should see the request succeed with `200 OK` or similar.
  4. **VPN AÇ** (toggle ON in uygulama, OR `adb shell am start -a android.net.VpnService`). The `VpnService.Builder.establish()` call returns a TUN file descriptor; the `startReaderThread` begins reading packets from the TUN.
  5. **Uygulama içinden AYNI adrese yeni istek gönder** (a second `POST /api/v1/sessions` or `GET /api/v1/sessions/{id}`). This time the SYN must go through the VPN's TUN (because `addRoute(0.0.0.0/0)` captures all traffic). The Owner should see the request reach the server AND the `handleTcpPacket: dispatching flags=0x.. SYN=true ACK=false PSH=false FIN=false RST=false` breadcrumb fire in logcat (proving the SYN reached the TUN).
  6. **Log al**: `adb logcat -d -s "OpenE2eeVpn:V TcpForwarder:V UdpForwarder:V NettyChannelClient:V"`. The 4-tag filter (NOT just `-s OpenE2eeVpn:V`) is required because the breadcrumbs are spread across 4 classes with 4 different TAG constants: `OpenE2eeVpn` (main service), `TcpForwarder` (TCP state machine in 12.0C), `UdpForwarder` (UDP forwarder in 12.0B), `NettyChannelClient` (Netty skeleton, runtime-dead at 12.0C but kept for S100-S114 audit compatibility).

### Decision matrix after the 6-step test

- **Yeni SYN geliyorsa** (the 12.0F+1 breadcrumb shows `SYN=true` for the new request's first packet): the dispatch precedence + handleSyn logic is correct. The 12.0F bug was a stale TCP bağlantı (the Owner tested an old connection, not a fresh one). No further code change needed; the 12.0F+1 diagnostics are the regression guard.
- **Yeni SYN gelmiyorsa** (the breadcrumb shows ONLY `PSH=true ACK=true` packets, never `SYN=true`): kernel routing exception. Confirm via `adb shell ip route` that the default route `0.0.0.0/0` is `tun0` (NOT `wlan0` or `rmnet0`). If the default route is the real NIC, the VPN is not capturing the SYN. The 3 remaining hypotheses are:
  - **(a)** VPN setup timing: confirm the VPN AÇ is `service.running.set(true)` BEFORE the second istek fires (the test akışı step 5 fires the request AFTER step 4 completes; the Establish returns a non-null ParcelFileDescriptor within ~50ms on a healthy device).
  - **(b)** `addAllowedApplication` only for own app: confirm the 12.0F+1 buildVpnBuilder log shows `allowedApps=0 packages=[]`. If it shows `allowedApps=1 packages=[com.opene2ee.opene2ee]`, remove the allowedApplications restriction (the default behavior captures all traffic).
  - **(c)** `bindProcessToNetwork` timing: confirm the S98 invariant — `checkPrivateDnsAndBindToVpn()` is called BEFORE `builder.establish()` (line 1132 BEFORE line 1133 in `startCapture`). The bind itself happens INSIDE `onAvailable` which fires AFTER `establish()` returns (the 11.0Y Sprint 11.0Y fix). If the call order is reversed, the NetworkCallback may never fire on OnePlus OxygenOS (the Owner 21:37 root cause for the 11.0Y fix).

### S121 audit (added in this sprint)

- **S121-1**: handleTcpPacket dispatch breadcrumb contains `flags=0x` + 5 flag names (`SYN=`, `ACK=`, `PSH=`, `FIN=`, `RST=`).
- **S121-2**: `buildVpnBuilder: allowedApps=` breadcrumb exists (so the Owner can confirm whether the VPN is restricted or captures all traffic).
- **S121-3**: `checkPrivateDnsAndBindToVpn()` called BEFORE `builder.establish()` (the 11.0Y Sprint 11.0Y Sprint 98 invariant that fixes the OnePlus OxygenOS NetworkCallback-never-fires bug; the request must be issued before establish so the system has a pending subscriber for the VPN transport).
- **S121-4**: 4-step test akışı (this section, plus the actual 6-step procedure above) documented in CHANGELOG.md.
- **S121-5**: APK build OK (R8 strict mode no missing classes; the 12.0F release build with `proguard-rules.pro` + `proguardFiles` must continue to work — the 12.0F+1 dispatcher breadcrumb uses only `Log.d(TAG, ...)` which is a stable Android API and cannot trigger R8 missing-class warnings).
- **S121-6**: APK SHA logged (the 12.0F+1 commit message includes the new APK SHA-256 hash so the Owner can match the install against the git log).
- **S121-7**: Tag 4 filter (`OpenE2eeVpn:V TcpForwarder:V UdpForwarder:V NettyChannelClient:V`) documented in test akışı (this section step 6).

## [Unreleased] - Sprint 8 (in branch `feat/pr-8-integration`, push YAPILMADI)

## [Unreleased] — Sprint 8 (in branch `feat/pr-8-integration`, push YAPILMADI)

### CI / multiplatform tooling

- **PR-MP-CI** — `.github/workflows/ios.yml`. Brought the iOS workflow to a fully multi-OS matrix (`ubuntu-latest` + `macos-latest` + `windows-latest`) per Sprint 3 carry-over, formalising the Sprint 7 STRIDE-8-01 macos-latest hand-off. Per-OS pub-cache steps refactored from a single shared step into three OS-specific steps so each runner's cache key prefix matches what the runner actually consumes (`runner.os` for Ubuntu / macOS / Windows). New windows-only Gradle cache step (`~\.gradle\caches` keyed on `**/build.gradle*` + `**/settings.gradle*`) with hit/miss log step (`Test-Path` + `Get-ChildItem -Recurse | Measure-Object -Property Length -Sum`). `fail-fast: false` confirmed explicit. xcodebuild gated on `matrix.os == 'macos-latest'` (no signing; simulator-only). Cross-references: `docs/CI-MATRIX.md` §3.2 (canonical runner → step mapping), `docs/ADR-0008-multiplatform-tooling.md` §"Per-OS Matrix". Hand-off origin: Sprint 3 carry-over + Sprint 7 STRIDE-8-01 macos matrix gate.

### Compliance — privacy, KVKK, anonymisation

- **ADV-3 follow-up** — `docs/REVIEW-CHECKLIST.md` (new file). Long-lived review-checklist that tracks the Sprint 2 hotfix review findings (ADV-1..ADV-4 from `SPRINT-2-HOTFIX-INTEGRATION-GATE.md` §6) and the Sprint 7 verifier §6 carry-overs, with cross-links to the Sprint 8 ADR extensions. Closure status (Sprint 7 net effect on Sprint 2 hotfix findings): ADV-2 CORS "\*" → CLOSED (PR-32 + Sprint 7 AUTHZ-2), ADV-3 kong.yml minimal plugins → CLOSED (PR-32 Kong JWT plugin + Sprint 7 AUTHZ-2 /healthz JWT extension), ADV-1 certbot path mismatch → carry-over Sprint 9+ (pre-existing PR-13, never hotfix scope), ADV-4 nginx HTTP→HTTPS redirect → carry-over Sprint 9+ (pre-existing PR-13, never hotfix scope). Sprint 7 §6 carry-overs tracked: S7-OPEN-1 Kong 3.8 EOL grandfather clause, S7-OPEN-2 Coturn TLS cipher-list :!ECDSA consistency, S7-OPEN-3 Kong request-transformer + prometheus plugins (ADV-3 partial close), MP-CI-OPEN-1 iOS release-build missing from multi-OS matrix (resolved by Sprint 8 PR-MP-CI). Fixup commits `2777047` (Sprint 3 evidence + verifier §6 retry) and `b87238d` (NEW-STALE text cleanup) preserved in `feat/pr-s8-adv3-followup` ancestry.

### Architecture Decision Records (doc-only extensions)

- **ADR-0006 extension (anonimlik)** — `docs/ADR-0006-anonimlik.md`. Sprint 8 HIGH-severity extension from Sprint 5 PR-33 stub to normative form. Four new sections: (1) Anonim Cihaz Kimliği contract pin (C-1 raw UUID v7 device-only, C-2 Ed25519 private key device-only, C-3 `device_id_hash = SHA-256(uuid || salt)[:16]`), each invariant paired with source-of-truth code path and the test that pins it (C-3 input-order note credits Sprint 1 PR-15 `353cb2a`). (2) KVKK DELETE cross-device propagation (endpoint shape + Sprint 6 PR-37 AUTHZ-1 JWT sub check + propagation chain `PostgresStore.DeleteUser → RedisPool.DeleteByHash → RunSweeper`). (3) MaskIP `/24` IPv4 / `/48` IPv6 contract (credits Sprint 1 PR-20 `2e4d492`). (4) Testability (CI guard coverage + known gaps). Revision History + extended Cross-references added. Fixup `6da6f9a` corrects 6 verifier-§6 phantom references (phantom test name → real `TestPublicKeyFingerprint_UniquePerKey`, wrong commit attribution `ba2fc31` → correct STRIDE-3-01 `a9fed70`/merged `7ff3efd`, phantom table `kvkk_delete_audit` → real slog warn-log evidence, wrong TTL `24h` → real `15m` per `DefaultPoolTTL`, phantom Risk Register E3 added, phantom `RISK-REGISTER.md` references rewritten as forward-looking follow-ups). Hand-off origin: Sprint 8 scope Item 4 (HIGH).
- **ADR-0003 extension (vpn-layer)** — `docs/ADR-0003-vpn-layer.md`. Sprint 8 HIGH-severity extension: (1) VPN purge semantics (STRIDE-6-03 follow-up) — when KVKK / GDPR Art. 17 DELETE fires on `DELETE /api/v1/users/{device_id_hash}`, the server hard-deletes Postgres rows (Sprint 6 PR-37) AND purges the Active Pool row from Redis (Sprint 7 Item 4 STRIDE-6-03) AND this ADR adds the third leg — the mobile-side VPN session tears down locally so the device stops holding per-session tunnel state (ring buffer + per-session AES key). Idempotent + audit-logged on both sides; 7-day SLA matches Redis `DefaultIdleSweepInterval` + consumer-retry jitter. (2) iOS Keychain access group — Runner.entitlements and OpenE2eeTunnelProvider.entitlements share `group.com.opene2ee.opene2ee` so the host Runner and the NetworkExtension process (Sprint 5 PR-22b) can read the same Keychain entries (tunnel master key per PR-29, per-session tunnel key, device-identity Ed25519 private key). (3) Android Keystore (MOB-5 follow-up) — Sprint 7 Item 6 bumped minSdk from 21 to 23 so `KeyGenParameterSpec` (genuine-backed AES master-key generation) is unconditionally available; tunnel master + per-session + device-identity keys live in AndroidKeyStore-backed flutter_secure_storage. Threat model V1-V12 (10 Active + 2 Planned). Hand-off origin: Sprint 8 scope Item 5 (HIGH).
- **ADR-0008 extension (multiplatform-tooling)** — `docs/ADR-0008-multiplatform-tooling.md`. Sprint 8 multiplatform-tooling extension: Per-OS Matrix section (formalises the ubuntu+macos+windows GHA matrix and per-OS cache key prefixes per Sprint 8 PR-MP-CI deliverable) + CI Tools Pinning section (version-pinning for jq, curl, openssl, sha256sum, pyyaml via `tools/PINS.toml` per Sprint 7 STRIDE-8-03). Hand-off origin: Sprint 8 scope Item 6.

### Documentation amend (no functional change)

- **PR-19 commit-amend** — `backend/internal/auth/keys.go` (no diff). Sprint 1 PR-15's commit message described itself as *"fixing a failing test"*, but the Sprint 1 PR-2 attempt actually implemented salt-first (`SHA-256(salt || uuid)`) AND its reference test `TestHashDeviceID_KnownAnswer` was pinned to the salt-first output `0a26ef7ed58d777eea5ccd0bc33307bb` — so the test PASSED against PR-2. PR-15 then deliberately rewrote both sides (impl + reference vector) to uuid-first as a contract change, not a bug fix. The previous PR-15 body implied a pre-existing test failure that never existed. Item 2 amends the commit message to accurately describe what happened (deliberate contract change, both impl + test rewritten in lockstep) and includes ADR-0006 §"Backend'de Saklanan" verbatim at line 204 (`SHA-256(device_id || server_salt)[:16]`) + §"Identity construction" at line 210. Tree-equality with PR-15 (`353cb2a`) preserved: `git rev-parse '353cb2a^{tree}' '244830c^{tree}'` returns the same SHA `ce4d9033b39e2efa7c8e529921e0a8385df96b2d`. Net effect on integration branch: zero file content change (PR-15 tree content already on main via `feat/pr-1-mp6-vscode` first-parent path); the amend is retrievable via `git log --grep='HashDeviceID'` on the integration branch.

## [Unreleased] — Sprint 7 (in branch `feat/pr-7-integration`, push YAPILMADI)

### Security — cipher, transport, authN, authZ

- **AUTHZ-2** — `infra/kong/kong.yml` + `infra/docker-compose.yml` + `scripts/smoke/healthz-jwt.{sh,ps1}` + `infra/kong/README.md`. The Kong `healthz` route is now JWT-protected under the new `opene2ee-monitoring` consumer, matching the existing `/api/v1/*` posture. The compose-side nginx healthcheck bypasses JWT (internal Docker network), and the bash + pwsh smoke scripts document the 4-scenario contract (no-auth→401, valid→200, expired→401, wrong-iss→401). Hand-off origin: cyber-security review of the Sprint 6 PR-39 mobile-security gap.
- **SEC-1** — `backend/cmd/server/main.go` + `backend/cmd/server/main_test.go`. The silent dev JWT fallback at `loadConfig` is removed; the new posture gates the fallback behind `OE2EE_ENV=dev` with a structured WARN log (`fallback_dev=true`, `oe2ee_env=dev`). Non-dev + unset JWT_SECRET fails closed (errors out with `main() exits 1`). Defense-in-depth: the compose-side `${JWT_SECRET:?...}` stays as the first layer. Hand-off origin: cyber-security review of the PR-32 JWT-secret posture.
- **SEC-6/7** — `infra/docker-compose.yml` + `infra/.env.example` + `infra/README.md`. Redis is no longer exposed host-side without auth. Added `--protected-mode yes --requirepass ${REDIS_PASSWORD:?}` to the redis service; removed the `6379:6379` host port; bounded the healthcheck probe to thread `-a $REDIS_PASSWORD` so NOAUTH doesn't silently break the probe. Hand-off origin: cyber-security review of the unauthenticated-cache gap.
- **STRIDE-6-03** — `backend/internal/matching/pool.go` + `pool_test.go` + `backend/cmd/server/main.go`. The Active Pool (`Pool`) now exposes `DeleteByHash(ctx, hash)`, `SweepIdle(ctx)`, and `RunSweeper(ctx)` for KVKK / GDPR Art. 17 hard-delete SLA (7-day window). The sweeper goroutine runs every `DefaultIdleSweepInterval=60s` (< `DefaultPoolTTL=15m`) so any KVKK DELETE that fails its hook also gets swept on the next tick. Hand-off origin: cyber-security review of the `users.go` KVKK hook chain.

### Compliance — privacy, KVKK, anonymisation

- **STRIDE-3-01** — `tool/ci_grep_privacy_violations.dart` + `.github/workflows/ci.yml`. A new Dart-based CI guard scans `mobile/lib/` and `mobile/test/` for `TelephonyManager`, `getDeviceId`, IMEI, `androidId` after stripping comments + string literals (so the documented RegExp in `mobile/test/shared/device_identity_test.dart:245-249` does not false-positive). The job is `ubuntu-latest` only — Dart-aware stripping is the reason and a matrix would 3x cost for zero value. Hand-off origin: cyber-security review of the F1 anonimlik contract in ADR-0006.
- **MOB-4** — `.github/workflows/android-release.yml` + `docs/CI-DEBUG-FAILURES.md`. The `android-release-build` job now emits `::error::` + `exit 1` BEFORE the JDK/Flutter/Android-SDK setup if `mobile/android/key.properties` is missing. The companion CI-DEBUG-FAILURES runbook documents the `keytool -genkey` + 4-field `key.properties` recipe so operators can self-serve provisioning (GitHub Actions secret + 1Password CLI pattern). Hand-off origin: cyber-security review of the silent-debug-fail mode in release artifacts.
- **MOB-5** — `mobile/android/app/build.gradle.kts` + `mobile/android/.../OpenE2eeVpnService.kt` + `mobile/README.md` + `mobile/test/min_sdk_posture_test.dart` + `docs/NATIVE-DEV-SETUP.md`. `minSdk` bumped from 21→23 so `AndroidKeyStore.KeyGenParameterSpec` (the Android 6+ genuine-backed key generation API) is unconditionally available — no more `flutter_secure_storage 9.x` "fall back to plaintext on Android 5/6" silent bypass. Market share of Android 5.0–5.1.1 dropped per Google Play 2026 numbers (&lt;0.5%). Hand-off origin: cyber-security review of the cipher-bypass on legacy Android.

### Mobile (Flutter / Dart)

- **MOB-6** — `mobile/ios/Config/{Local,Production}.xcconfig` + `mobile/ios/Runner.xcodeproj/project.pbxproj` + both `.entitlements` + `docs/SETUP-iOS.md`. `DEVELOPMENT_TEAM` moved from `""` literals to `$(TEAMS_IDENTIFIER)` xcconfig for all 6 XCBuildConfiguration entries (H3-H8 for Debug+Release × Runner+Tunnel+RunnerTests). The `com.apple.developer.team-identifier` entitlement is now bound on both `Runner.entitlements` and `OpenE2eeTunnelProvider.entitlements`. xcconfig does NOT auto-substitute in plist — operator manual `sed` OR a Sprint 8 build-phase script is the substitution mechanism (documented in SETUP-iOS.md §2.2). Hand-off origin: cyber-security review of the iOS keychain-sharing gap.
- **MOB-8** — `mobile/lib/shared/cert_pinning.dart` + `mobile/test/shared/cert_pinning_test.dart` + `mobile/test/mobile/security/cert_pinning_posture_test.dart` + `mobile/ios/Runner/Info.plist` + `mobile/android/app/src/main/res/xml/network_security_config.xml` + `docs/SPRINT-7-MOB-8-CERT-PINNING.md`. Full cert SHA-256 pinning on both platforms (Android SPKI + iOS NSPinnedDomains), wired into `ApiClient` via `PinnedHttpOverrides`. Placeholder pin strings remain on Android/iOS — operators MUST replace per `docs/SPRINT-7-MOB-8-CERT-PINNING.md` §3 before public launch. Hand-off origin: cyber-security review of the network-trust-anchor gap.
- **MOB-10** — `mobile/lib/mobile/auth/biometric.dart` + `mobile/test/mobile/auth/biometric_test.dart` + `mobile/test/mobile/security/biometric_posture_test.dart` + `mobile/ios/Runner/Info.plist` + `mobile/android/app/src/main/AndroidManifest.xml` + `mobile/pubspec.{yaml,lock}`. New `BiometricAuthenticator` wrapper with hardened `AuthOptions(biometricOnly=true, stickyAuth=true)` + `BiometricUnavailableError`. Wraps `requireBiometricForKvkkDelete()` + `requireBiometricForKeyExport()`. NSFaceIDUsageDescription + USE_BIOMETRIC pinned in posture test; no `passcode`/`PIN` fallback. Hand-off origin: cyber-security review of the local_auth `passcode` fallback path.
- **MOB-14** — `mobile/pubspec.{yaml,lock}`. `flutter_webrtc: ^0.10.8 → ^1.5.2`. The 1.x line ships m137→m144 libwebrtc, RTCRtpEncoding priority API, macOS AVAudioEngine improvements, and several back-ported security patches from the upstream WebRTC project. Dart API surface used by `FlutterWebRtcBridge` is stable across the bump (per upstream 1.x changelog).

### Infrastructure — coturn + Kong + compose + tools

- **SCA-19** — `docs/policy/KONG-UPGRADE-POLICY.md` + `infra/kong/smoke-jwt.py` + `Makefile`. Formal Kong upgrade cadence policy (upstream-paced, monthly minor / quarterly major). Amend `aeef6ee` added a Grandfather Clause acknowledging `kong:3.8-alpine` past non-LTS EOL (2024-12-12) and an explicit bump PR SLA within 14 days. Companion `infra/kong/smoke-jwt.py` (PyJWT round-trip helper) + `Makefile` `test-compose` target give operators a 5-step gate (compose config parse + smoke-jwt.py + docker-compose-config + privacy-check + go-build-test). Hand-off origin: cyber-security review of the Kong upgrade cadence.
- **SCA-22** — `infra/coturn/turnserver.conf` + `infra/coturn/entrypoint.sh` + `infra/docker-compose.yml` + `infra/.env.example` + `docs/SETUP.md` + `scripts/smoke/coturn-tls.{sh,ps1}`. Coturn now supports TLS/DTLS in production (cipher list TLS 1.2+ only, no SSLv3 / TLS 1.0 / TLS 1.1). The new `entrypoint.sh` is fail-closed: it verifies the cert+key+DH files exist before exec'ing `turnserver` with `--tls --dtls`; unset `COTURN_TLS_ENABLED` falls back to dev `--no-tls --no-dtls` (NOT for production). SETUP.md documents cert provisioning + rotation; coturn-tls.{sh,ps1} provides ADR-0008 cross-platform smoke (TLS 1.0/1.1 refused, TLS 1.2 accepted, cert chain verifies). Hand-off origin: cyber-security review of plaintext-credential-on-TURN gap.

### Tooling — CI tools pinning + Windows Docker matrix

- **STRIDE-8-01** — `.github/workflows/ios.yml`. New GHA matrix with `windows-latest + ubuntu-latest` static checks (Podfile + Info.plist + project.pbxproj) and `macos-latest` `xcodebuild ... build test` against the iOS Simulator (no signing). SwiftPM + CocoaPods caching via `actions/cache@v4`. Hand-off origin: cyber-security review of the iOS unit-test gap.
- **STRIDE-8-02** — `.github/workflows/ci.yml` + `docs/CI-MATRIX.md`. The `docker-compose-config` matrix already skipped Windows; the long-comment is now a 33-line NOTICE block citing the POSIX secret paths (`infra/docker-compose.yml:104-110`), the YAML anchors, and the WSL 2 manual-verify escape hatch. The `backend-build` job (Linux-only) gained a 20-line NOTICE block. CI-MATRIX.md catalogues every runner×step pairing (14-row ci.yml + 1-row ios.yml). Hand-off origin: cyber-security review of the silent-Windows-skip posture.
- **STRIDE-8-03** — `tools/PINS.toml` + `tools/ci-tools-pin-check.{sh,ps1}` + `tools/install-pinned.{sh,ps1}` + `tools/lib/scanner.py` + `tools/test-ci-tools-pin-check.sh` + `tools/README.md` + `tools/.gitignore` + `tools/bin/.gitkeep` + `docs/CI-TOOLS.md` + `.github/workflows/ci.yml`. A central version-pinning policy for jq, curl, openssl, sha256sum, pyyaml. Both bash and PowerShell validator twins scan 4 roots (scripts/, infra/scripts/, tools/, .github/workflows/) for unpinned 3rd-party invocations and exit 1 if found. `docs/CI-TOOLS.md` documents the policy + 5-step rotation + adversarial compromise recovery. Hand-off origin: cyber-security review of the unpinned-tool supply-chain.

## [Unreleased] — Sprint 6 (PR-39 merge: 1651ae4)

- `merge: Sprint 6 PR-39 mobile security hardening (Android cleartext pin + R8/ProGuard + iOS NE MinOSVersion bump)` (a428d8c).
- `merge: Sprint 6 PR-38 backend fix #2 (pgx/v5 CVE chain guardrail: CI govulncheck + hermetic pin test)` (730da02).
- `merge: Sprint 6 PR-37 backend fix #1 (KVKK cross-device JWT sub check)` (67a8c29).

See the per-PR verifier reports under `outputs/pr-sprint6-*/` for the SPRINT 6 deliverables.

---

This changelog is incrementally maintained. Older sprints are intentionally omitted — use `git log` + the per-PR verifier reports for historical context.
