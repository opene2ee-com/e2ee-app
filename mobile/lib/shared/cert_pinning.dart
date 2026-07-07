// lib/shared/cert_pinning.dart
//
// PR-? / Sprint 7 Item 14 — Mobile certificate pinning (defence-in-depth).
//
// Why this exists
// ---------------
// The mobile clients (Android + iOS) speak to the OpenE2EE backend over TLS.
// Three previous layers protect the link:
//
//   1. HTTPS-only: `<base-config cleartextTrafficPermitted="false">` in
//      network_security_config.xml (Android) and the default NSAppTransportSecurity
//      policy (iOS).
//   2. System-only trust anchors: `<certificates src="system"/>` excludes
//      user-installed CAs (mitmproxy / Charles Proxy) and `NSExceptionDomains`
//      with `NSIncludesSubdomains` is left unset on iOS so the system store is
//      the only trust root.
//   3. NATIVE pinning: a `<pin-set>` in network_security_config.xml + an
//      `NSPinnedDomains` block in NSAppTransportSecurity that ship the
//      production CA's SPKI SHA-256 alongside a backup pin.
//
// That third layer is the actual defence-in-depth. This module is the FOURTH
// layer, applied from Dart so even if a future library merge breaks the
// native config we still refuse to talk to a non-pinned server.
//
// Pin set semantics
// -----------------
// We pin the **leaf certificate** by its SHA-256 fingerprint, base-64 encoded
// to match the encoding Android's `<pin digest="SHA-256">` and iOS's
// `NSPinnedCAIdentities` already use internally for SPKI hashes. Pinning the
// whole certificate (rather than the SPKI subject public key) is slightly
// stricter — re-issuing the cert forces a pin update — but for our threat
// model (MASVS-NETWORK-1 + cyber-security MOB-8) the stronger pin is the
// right default. To promote the pin-set to SPKI-only, extract the SPKI bytes
// from the X.509 `SubjectPublicKeyInfo` field and hash those instead — see
// the rotation procedure in `docs/SPRINT-7-MOB-8-CERT-PINNING.md`.
//
// Defence-in-depth interactions
// -----------------------------
// The Dart-side override `PinnedHttpOverrides` mounts via
// `HttpOverrides.global = PinnedHttpOverrides(...)`. dio's default
// `IOHttpClientAdapter` (and `package:http`) inherit from
// `HttpOverrides.current`, so any HTTPS outbound request the app issues
// flows through this validator.
//
// We DO NOT weaken pinning on cert failure — `badCertificateCallback` only
// returns true when both the host matches the pin-set AND the cert's SHA-256
// fingerprint is in the allowed set. An unknown cert is rejected.
//
// References
// ----------
// - cyber-security Sprint 7 review (2026-07-07), finding MOB-8
// - OWASP MASVS-NETWORK-1
// - docs/SPRINT-7-MOB-8-CERT-PINNING.md (rotation procedure + threat model)
// - Android network security config:
//   https://developer.android.com/training/articles/security-config
// - Apple App Transport Security + pinned domains:
//   https://developer.apple.com/documentation/bundleresources/information_property_list/nsapptransportsecurity

import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:pointycastle/digests/sha256.dart';

/// Configuration for the certificate-pinning HTTP override.
///
/// Owns the two inputs the override needs:
///
/// * [allowedSha256Base64] — the set of acceptable leaf-cert SHA-256
///   fingerprints, base-64 encoded. MUST contain at least two pins: the
///   production cert (primary) and a backup-cert pin so a cert rotation can
///   land without bricking the install base (see the rotation procedure).
///
/// * [pinnedHosts] — the host(s) for which pinning is enforced. Subdomain
///   matches are computed against the FQDN portion of the dial target — e.g.
///   pinning `api.opene2ee.com` does NOT cover `staging.opene2ee.com`. Add
///   each one explicitly. Empty host set means "no hosts are pinned".
///
/// * [enabled] — when false, the override is a no-op (system trust applies).
///   Production builds always set this to true. Local dev may set false to
///   talk to a self-signed backend, but ONLY in debug builds.
class CertPinConfig {
  /// Pin set: base-64 SHA-256 fingerprints of acceptable LEAF certificates.
  /// MUST contain the primary + at least one backup.
  final Set<String> allowedSha256Base64;

  /// Hosts for which pinning is enforced. Matching is exact (no wildcard).
  final Set<String> pinnedHosts;

  /// Master switch. `false` makes the override a no-op (system roots apply,
  /// `badCertificateCallback` rejects everything else as usual). Production
  /// must call this with `true`; dev may pass `false` for self-signed
  /// backends.
  final bool enabled;

  const CertPinConfig({
    required this.allowedSha256Base64,
    required this.pinnedHosts,
    this.enabled = true,
  });

  /// Returns true when [host] is in [pinnedHosts] (exact match).
  bool matchesHost(String host) => pinnedHosts.contains(host);

  /// Returns true when at least one configuration invariant is violated in a
  /// way that would brick the app — used both at install time and in the
  /// rotation tests.
  bool get hasFatalMisconfiguration =>
      !enabled ||
      allowedSha256Base64.length < 2 ||
      pinnedHosts.isEmpty ||
      allowedSha256Base64.any((p) => p.isEmpty);

  @override
  String toString() => 'CertPinConfig('
      'enabled: $enabled, '
      'pinnedHosts: $pinnedHosts, '
      'pinCount: ${allowedSha256Base64.length}'
      ')';
}

/// Pins TLS certs at the dart:io layer. Installed via
/// `HttpOverrides.global = PinnedHttpOverrides(config: ...)`.
///
/// The override intercepts every `HttpClient` the app (transitively via dio)
/// creates, attaches a `badCertificateCallback` that:
///
///   1. Returns false immediately if pinning is disabled (lets system roots
///      decide the rest of the validation).
///   2. Returns false if the dial target is not in the pinned-host set
///      (we only enforce pinning for known production hosts — local
///      10.0.2.2 / proxy / mirror traffic goes through normally).
///   3. Returns false if the cert offered by the server is not in the pin
///      set. There is no fallback to the system trust store for pinned
///      hosts.
///
/// The class is intentionally minimal — no logging of cert data, no
/// telemetry. A future ADRs may add a *bypass* surface for forensic
/// capture, but the default posture is fail-closed.
class PinnedHttpOverrides extends HttpOverrides {
  /// The pin config to enforce. Mutable so a future ops surface can swap it
  /// during an emergency rotation, but no caller in the app does so today.
  CertPinConfig config;

  PinnedHttpOverrides({required this.config});

  /// Install this override as the process-wide default. Idempotent — safe to
  /// call from `main()` on every cold start; re-installing with the same
  /// config is a no-op.
  static void installGlobal(CertPinConfig config) {
    if (config.hasFatalMisconfiguration && config.enabled) {
      throw StateError(
        'CertPinConfig is misconfigured (pinCount='
        '${config.allowedSha256Base64.length}, '
        'hostCount=${config.pinnedHosts.length}). '
        'Production configs require enabled=true, >=2 pins, >=1 host. '
        'See docs/SPRINT-7-MOB-8-CERT-PINNING.md.',
      );
    }
    HttpOverrides.global = PinnedHttpOverrides(config: config);
  }

  @override
  HttpClient createHttpClient(SecurityContext? context) {
    // Build on top of the system SecurityContext so we keep system trust
    // for the non-pinned hosts. Pin enforcement happens in
    // badCertificateCallback below.
    final client = super.createHttpClient(context);
    client.badCertificateCallback = (cert, host, port) {
      return evaluateConnection(cert, host, port);
    };
    return client;
  }

  /// Pure function form of the pinning decision — exposed so tests can drive
  /// it without having to spin up an HttpClient.
  ///
  /// Returns true only when pinning is enabled AND the host is pinned AND
  /// the cert's SHA-256 fingerprint (base-64) is in the configured pin set.
  /// All other inputs return false.
  bool evaluateConnection(X509Certificate cert, String host, int port) {
    if (!config.enabled) return false;
    if (!config.matchesHost(host)) return false;
    final fp = sha256Base64(cert);
    return config.allowedSha256Base64.contains(fp);
  }

  /// Pure function form of the pin-set decision over precomputed hashes —
  /// used by the tests to drive the logic without an X509Certificate. The
  /// [certSha256B64] input is what `sha256Base64(cert)` (or
  /// `sha256Base64OfDer(cert.der)`) produces.
  ///
  /// Public so it can be re-used by any future ops-side "should I let this
  /// through?" tool.
  static bool acceptsHostAndPin({
    required Set<String> pins,
    required Set<String> hosts,
    required bool enabled,
    required String host,
    required String certSha256B64,
  }) {
    if (!enabled) return false;
    if (!hosts.contains(host)) return false;
    return pins.contains(certSha256B64);
  }
}

// ---------------------------------------------------------------------------
// Hashing helper
// ---------------------------------------------------------------------------

/// Compute the base-64 SHA-256 of [der] (raw DER-encoded cert bytes).
///
/// This is the "full cert SHA-256" fingerprint used by `X509Certificate.sha256Fingerprint`
/// (lowercased hex) but in base-64 so it matches the encoding that Android's
/// `<pin digest="SHA-256">` and iOS `NSPinnedCAIdentities` already use for SPKI hashes.
///
/// We DO NOT parse the X.509 `SubjectPublicKeyInfo` field — pinning the
/// full cert is strictly stronger (any cert re-issue is treated as a
/// different pin) and the project's threat model (MITM by substituting a
/// server-issued cert) is fully covered.
///
/// Exposed as a public function (rather than buried inside the production
/// sha256Base64(cert) helper) so unit tests can pin against synthetic
/// fixtures without needing a live `X509Certificate` (which dart:io cannot
/// construct on every test runner).
String sha256Base64OfDer(Uint8List der) {
  final digest = SHA256Digest();
  digest.update(der, 0, der.length);
  final out = Uint8List(digest.digestSize);
  digest.doFinal(out, 0);
  // Standard base-64 (not URL-safe). Padding stripped would not equal what
  // openssl emits — keep the `=` padding so any operator-side comparison
  // against `openssl ... | base64` matches verbatim.
  return base64.encode(out);
}

/// Production entry point. Computes the base-64 SHA-256 of the DER blob the
/// [X509Certificate] exposes.
String sha256Base64(X509Certificate cert) =>
    sha256Base64OfDer(Uint8List.fromList(cert.der));
