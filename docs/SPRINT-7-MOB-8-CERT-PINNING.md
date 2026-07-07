# Sprint 7 MOB-8 — TLS Certificate Pinning Rotation Procedure

**Date:** 2026-07-07
**Owner:** Frontend-coder (hand-off from cyber-security)
**Finding:** MOB-8 (High) — Flutter mobile lacks TLS certificate pinning (defence-in-depth alongside HTTPS-only enforcement)
**Status:** Active — the pin-set shipped in this PR ships with `PLACEHOLDER_*` strings. Operators MUST replace before public launch.

---

## 1. Threat model (what pinning defends against)

The OpenE2EE mobile clients speak to the backend over HTTPS. The PR-39
"Sprint 6 mobile security hardening" change shipped three
already-deployed layers:

1. **HTTPS-only.** `<base-config cleartextTrafficPermitted="false">` in
   `network_security_config.xml` (Android), and the default iOS
   `NSAppTransportSecurity` policy with no `NSAllowsArbitraryLoads` (iOS).
2. **System-only trust anchors.** `<certificates src="system"/>` in
   network_security_config.xml and no `NSExceptionDomains` widening in
   Info.plist. User-installed CAs (Charles Proxy / mitmproxy on a
   rooted device) cannot intercept our traffic.
3. **Native pinning.** A `<pin-set>` in
   `network_security_config.xml` (Android `<pin digest="SHA-256">`) and
   `NSPinnedDomains` in `NSAppTransportSecurity` (iOS
   `NSPinnedLeafIdentities`). Both hold the production CA's **SPKI
   SHA-256** plus a backup pin.

MOB-8 (Sprint 7) **promotes the placeholder pin-set that PR-39 left as
commented text** into a real pin-set, and adds a fourth layer:

4. **Dart-side `PinnedHttpOverrides`.** Installed at boot via
   `PinnedHttpOverrides.installGlobal(...)`. Pin enforcement happens
   inside `badCertificateCallback` so an attacker who somehow bypasses
   the native pinning (corrupt lib, jailbroken OS, OS upgrade regression)
   still cannot talk to a non-pinned server from inside the
   Dart `HttpClient`.

Pinning the **cert's public key** (SPKI) — rather than the cert itself
— is the recommended practice: it lets the CA renew the cert without
also rotating the pin. The native configs follow that practice. The
Dart override pins the **full-cert SHA-256** because Dart does not
expose the SPKI field on `X509Certificate` without ASN.1 parsing;
this is strictly stronger (rotating a cert forces a pin update) and
does not weaken the threat model.

---

## 2. Pin-set semantics (what's currently shipped)

| Layer | Platform | File | Format |
|-------|----------|------|--------|
| Native (Android) | API 24+ | `mobile/android/app/src/main/res/xml/network_security_config.xml` | `<pin digest="SHA-256">SPKI_SHA256_BASE64</pin>` |
| Native (iOS) | iOS 14+ | `mobile/ios/Runner/Info.plist` → `NSAppTransportSecurity.NSPinnedDomains.<domain>.NSPinnedLeafIdentities` | `<data>SPKI_SHA256_BASE64</data>` |
| Dart | All platforms | `mobile/lib/shared/cert_pinning.dart` → `PinnedHttpOverrides` | `Set<String>` of base-64 SHA-256 fingerprints of cert DER |

Per host (`api.opene2ee.com` and `staging.opene2ee.com`), each layer
holds **two pins**: a **primary** (current production CA) and a
**backup** (secondary / cross-signed CA). The overlap is what lets the
operator rotate the primary without forcing an app update — see §5.

`api.opene2ee.com` and `staging.opene2ee.com` are pinned with
**`NSIncludesSubdomains="true"` / `includeSubdomains="true"`** so a
new sub-domain (e.g. `vpn.api.opene2ee.com`) inherits the pin without
an Info.plist / network_security_config rewrite.

`network_security_config.xml` carries a `<pin-set expiration="YYYY-MM-DD">`.
**Past this date Android hard-fails every TLS handshake** regardless of
the cert presented. The shipped expiration is `2027-07-07`. Operators
must extend this whenever they touch the pin set.

---

## 3. Pre-launch checklist (operators)

Before shipping a real build to the App Store / Play Store, the
placeholder pins in `network_security_config.xml`, `Info.plist`, and
`cert_pinning.dart` MUST be replaced with the production CA's real SPKI
SHA-256.

```bash
# 1. Obtain the production CA's SPKI SHA-256 (base-64).
#    Adjust HOST below; use the SAME command for the backup CA.
HOST=api.opene2ee.com

openssl s_client \
   -connect "$HOST":443 -servername "$HOST" < /dev/null \
   2>/dev/null \
 | openssl x509 -noout -pubkey \
 | openssl pkey -pubin -outform DER \
 | openssl dgst -sha256 -binary \
 | base64
# → "ABCDef123…==" (44 base-64 chars / 32 bytes SHA-256)
```

Expected output format: 44 characters, base-64 alphabet only, ends
with one or two `=` padding characters (e.g.
`ABCDefghIJKLmnopQRSTUvwxyz0123456789ABC=`).

Take the two outputs (production + backup) and:

* Replace the **two** `PLACEHOLDER_PRODUCTION_CA_SPKI_SHA256_BASE64==`
  and `PLACEHOLDER_BACKUP_CA_SPKI_SHA256_BASE64==` strings in
  `mobile/android/app/src/main/res/xml/network_security_config.xml`
  (under `<pin-set>`).
* Replace the same two strings in
  `mobile/ios/Runner/Info.plist` (under both `api.opene2ee.com` and
  `staging.opene2ee.com`).
* Replace the same two strings in
  `mobile/lib/shared/cert_pinning.dart` → `// production pin set here` —
  the developer is responsible for setting `allowedSha256Base64` at boot.

Run `flutter test` after each change. The
`cert_pinning_posture_test` group fails loudly if any of:

* `<pin digest="SHA-256">` count drops below 2, or
* `<pin-set>` `expiration` is closer than 90 days from today, or
* `Info.plist` no longer pins both `api.opene2ee.com` and
  `staging.opene2ee.com` with `NSIncludesSubdomains=true`.

Do NOT ship until tests pass.

---

## 4. Rotation procedure (rolling a new CA / new pin)

`primary + backup` overlap lets the operator rotate the production CA
without breaking connectivity. The protocol below is the canonical
rotation flow.

### 4.1 Standard rotation (CA still trusted, just swapping the primary)

**Window: 7 days minimum, 90 days recommended.** The window is the
period during which BOTH pins are present.

```bash
# T0 — pre-flight
NEW_PRIMARY_B64=$(openssl s_client -connect api.opene2ee.com:443 -servername api.opene2ee.com < /dev/null 2>/dev/null \
   | openssl x509 -noout -pubkey \
   | openssl pkey -pubin -outform DER \
   | openssl dgst -sha256 -binary | base64)

# T+0 — promote the new CA on the backend (no client change yet)
# T+0 — ship the new pin IN ADDITION to the old primary
```

1. `mobile/android/app/src/main/res/xml/network_security_config.xml`
   → `<pin-set>` → insert a new `<pin digest="SHA-256">$NEW_PRIMARY_B64</pin>`
   BELOW the existing two pins. The new state has three pins
   (primary, backup, new-primary).
2. `mobile/ios/Runner/Info.plist` → `NSPinnedDomains.<domain>.NSPinnedLeafIdentities`
   → append `<data>$NEW_PRIMARY_B64</data>` to the array. The new
   state has three pins.
3. `mobile/lib/shared/cert_pinning.dart` → ship the matching
   `allowedSha256Base64` (add the new value to the set).
4. **Ship the new app version** (TestFlight + Play Console internal).
   Wait until the install base has upgraded past the previous mandatory
   floor — at minimum 7 days, recommended 90.
5. **Swap roles**: the new pin is now the "primary". The old "primary"
   becomes the "backup". Update both configs.
6. **Drop the old pin.** After the overlap window elapses, drop the
   pin that was the previous "backup". The set shrinks back to two
   pins.
7. **Bump `<pin-set expiration>`** to a new "hard fail" date 12
   months out so the operator cycle continues.

The regression-guard tests in `cert_pinning_posture_test.dart` enforce
the invariants above:

* `<pin>` count MUST be `>= 2` (rolling rotation).
* `expiration` MUST be at least 90 days in the future.
* Each pinned host MUST be present in `NSPinnedDomains`.

### 4.2 Emergency rotation (CA compromised / private key leak)

In a CA compromise the timeline is compressed:

1. Ship a fix as fast as the build pipeline allows. The "wait for the
   install base to upgrade" step is replaced by a forced-upgrade via a
   backend gate — every API call returns HTTP 426 "Upgrade Required"
   until the client pins the new cert.
2. Drop the compromised pin FIRST, then add the new pin. Order
   matters: if you only add a new pin, the old one is still trusted
   and a MITM with the old CA continues to succeed.

A forced-upgrade gate is a backend concern (PR-38-style CIDR-style API
gating) and is OUT OF SCOPE for MOB-8.

### 4.3 Dev escape hatch

Local dev builds against a self-signed backend (e.g. an emulator
pointing at `10.0.2.2:8080`) MUST skip pinning. The pattern:

```dart
import 'package:opene2ee/shared/cert_pinning.dart';

void main() {
  if (const bool.fromEnvironment('DISABLE_TLS_PINNING')) {
    PinnedHttpOverrides.installGlobal(const CertPinConfig(
      allowedSha256Base64: <String>{},
      pinnedHosts: <String>{},
      enabled: false,
    ));
  } else {
    PinnedHttpOverrides.installGlobal(const CertPinConfig(
      allowedSha256Base64: <String>{
        'PRODUCTION_SPKI_SHA256_BASE64==',
        'BACKUP_SPKI_SHA256_BASE64==',
      },
      pinnedHosts: <String>{
        'api.opene2ee.com',
        'staging.opene2ee.com',
      },
      enabled: true,
    ));
  }
  // … rest of main()
}
```

`--dart-define=DISABLE_TLS_PINNING=true` then makes `enabled=false`
take effect at boot. Production MUST be built without the define
(`enabled=true`).

---

## 5. Adding new pinned hosts

To pin a new host (e.g. `vpn.api.opene2ee.com`) once the platform
natively supports it:

### Android

`network_security_config.xml` already pins `*.opene2ee.com` via
`includeSubdomains="true"`, so a new sub-domain inherits the pin
automatically. No XML change required.

### iOS

`Info.plist`'s `NSIncludesSubdomains=true` on each entry covers the
same scope.

### Dart

`PinnedHttpOverrides.config.pinnedHosts` is an exact-match set.
Subdomain expansion is **not** automatic — `api.opene2ee.com` in the
set does NOT cover `vpn.api.opene2ee.com`. To pin a new
sub-domain, add it to the set:

```dart
pinnedHosts: <String>{
  'api.opene2ee.com',
  'staging.opene2ee.com',
  'vpn.api.opene2ee.com',  // new
},
```

This is a deliberate divergence from the native configs — Dart pinning
is the operator's last line of defence, not the first, and exact-match
behaviour prevents "wildcard mistakes" from landing.

---

## 6. Regression-guard test matrix

`mobile/test/mobile/security/cert_pinning_posture_test.dart` enforces:

| # | Invariant | Failing consequence |
|---|-----------|---------------------|
| 1 | Info.plist declares `NSAppTransportSecurity` | No iOS-side pinning at all. |
| 2 | `NSPinnedDomains.api.opene2ee.com` pins with `NSIncludesSubdomains=true` + ≥2 `<data>` entries | MITM succeeds against the production host. |
| 3 | `NSPinnedDomains.staging.opene2ee.com` is also pinned | Staging deployment silently bypasses pinning. |
| 4 | Info.plist does NOT set `NSAllowsArbitraryLoads=true` | ATS disabled, all pinning moot. |
| 5 | `network_security_config.xml` has an un-commented `<domain-config>` for both pinned hosts | Android-side pinning removed. |
| 6 | `<pin-set>` has ≥2 `<pin digest="SHA-256">` entries | Single-pin = install-base brick on rotation. |
| 7 | `<pin-set expiration>` is ≥90 days in the future | Silent hard-fail once expiration passes. |

`mobile/test/shared/cert_pinning_test.dart` enforces:

| # | Invariant | Failing consequence |
|---|-----------|---------------------|
| A | `sha256Base64OfDer` reference-vector match | Pin tooling drift — operators paste wrong hashes. |
| B | `CertPinConfig` rejects 1-pin / 0-host configs | Install-base footgun. |
| C | `PinnedHttpOverrides.acceptsHostAndPin` returns false on hash mismatch | MITM accepted. |
| D | `PinnedHttpOverrides.installGlobal` throws on fat-finger misconfiguration | App boots without pinning. |

Any regression in either test group breaks `flutter test` in CI —
intentional, since the changes PR-39 / MOB-8 ship are in XML / Plist /
Dart source files that the Flutter toolchain does not round-trip
through `flutter test`.

---

## 7. References

* cyber-security Sprint 7 review (2026-07-07) — finding MOB-8
* OWASP MASVS-NETWORK-1
* Apple developer docs — `NSAppTransportSecurity.NSPinnedDomains`:
  https://developer.apple.com/documentation/bundleresources/information_property_list/nsapptransportsecurity/nspinneddomains
* Android developer docs — `<pin-set>` and `<pin digest>`:
  https://developer.android.com/training/articles/security-config#pin-set
* Sprint 6 PR-39 follow-up — `docs/SPRINT-6-PR-39-VERIFICATION.md`
  (the verification plan this PR's posture tests are patterned after)

---

**File path:** `docs/SPRINT-7-MOB-8-CERT-PINNING.md`
**Pattern:** Sprint 6 PR-39 verification doc style (manually walked through, then locked in via regression-guard tests).
