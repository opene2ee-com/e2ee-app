// test/shared/cert_pinning_test.dart
//
// MOB-8 / Sprint 7 — Unit tests for the Dart-side certificate pinning
// override (`PinnedHttpOverrides`). These tests are pure unit tests: no
// network, no X509Certificate construction (the dart:io X509Certificate
// constructor is not exposed across the public API surface, so we test
// the pinning decision against a precomputed SHA-256 instead).
//
// Test matrix:
//   1. `sha256Base64OfDer` matches reference vectors for a handful of
//      fixed inputs. The vectors are computed once with python's hashlib
//      and copy-pasted here so a regression in the digest path is loud.
//   2. `CertPinConfig.hasFatalMisconfiguration` flags configs that would
//      brick the app:
//        - enabled=false is a fatal miss (would silently disable pinning)
//        - <2 pins would not survive a rotation
//        - 0 pinned hosts means nothing is enforced
//   3. `CertPinConfig.matchesHost` is exact-match (no domain fronting).
//   4. `PinnedHttpOverrides.acceptsHostAndPin` returns true only when all
//      three inputs (enabled flag, host membership, hash membership) hold.
//      False on every mis-aligned input.
//   5. `PinnedHttpOverrides.installGlobal` throws StateError on a fatal
//      misconfiguration when `enabled=true`. With `enabled=false`, it
//      installs a permissive no-op override (the dev-mode escape hatch).
//
// Reference docs:
//   - lib/shared/cert_pinning.dart (the module under test)
//   - docs/SPRINT-7-MOB-8-CERT-PINNING.md (threat model + rotation)

import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:opene2ee/shared/cert_pinning.dart';

/// Reference SHA-256 inputs chosen to be small + unambiguous. Vectors
/// cross-checked against Python's `hashlib.sha256().digest()` + base64.
final List<Map<String, String>> _sha256Fixtures = <Map<String, String>>[
  <String, String>{
    'input': 'abc',
    'expected_b64': 'ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=',
  },
  <String, String>{
    'input': 'hello',
    'expected_b64': 'LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=',
  },
  <String, String>{
    'input': 'world',
    'expected_b64': 'SG6kYiTRu0+2gPNPfJrZao8k7Ii+c+qOWmxlJg6cuKc=',
  },
  <String, String>{
    'input': 'OpenE2EE pinning fixture',
    'expected_b64': 'axzYMkZDCKpJQOKLqShJMfK10kwGlkNoEC7ZFdEw7ww=',
  },
];

void main() {
  group('sha256Base64OfDer — reference vectors', () {
    for (final fixture in _sha256Fixtures) {
      final input = fixture['input']!;
      final expected = fixture['expected_b64']!;
      test('SHA-256("$input") base-64 == $expected', () {
        final bytes = Uint8List.fromList(input.codeUnits);
        final got = sha256Base64OfDer(bytes);
        expect(got, expected, reason: 'SHA-256("$input") base-64 mismatch '
            '(lib shared with a rotation tool — any drift here means '
            'production pin sets will silently reject every cert).');
      });
    }

    test('different inputs produce different hashes (no collisions)', () {
      final a = sha256Base64OfDer(Uint8List.fromList('abc'.codeUnits));
      final b = sha256Base64OfDer(Uint8List.fromList('abd'.codeUnits));
      expect(a, isNot(equals(b)));
    });

    test('output is RFC 4648 base-64 with `=` padding', () {
      // Pinned Android/iOS operators compare against the output of:
      //   openssl ... | openssl dgst -sha256 -binary | base64
      // which emits standard base-64 INCLUDING trailing `=` padding. We
      // mirror that.
      final got = sha256Base64OfDer(Uint8List.fromList('abc'.codeUnits));
      expect(got.endsWith('='), isTrue,
          reason: 'Pin-tool operators must be able to paste our hash '
              'directly into openssl pipelines.');
      // No URL-safe alphabet chars (`-`, `_` are URL-safe-specific).
      expect(got.contains('-'), isFalse);
      expect(got.contains('_'), isFalse);
    });
  });

  group('CertPinConfig invariants', () {
    test('hasFatalMisconfiguration flags missing pins', () {
      const cfg = CertPinConfig(
        allowedSha256Base64: <String>{'a'}, // only 1 pin — not enough
        pinnedHosts: <String>{'api.opene2ee.com'},
        enabled: true,
      );
      expect(cfg.hasFatalMisconfiguration, isTrue,
          reason: 'A 1-pin config is fatal — it cannot survive a single '
              'cert rotation without bricking the install base.');
    });

    test('hasFatalMisconfiguration flags empty host set', () {
      const cfg = CertPinConfig(
        allowedSha256Base64: <String>{'a', 'b'},
        pinnedHosts: <String>{},
        enabled: true,
      );
      expect(cfg.hasFatalMisconfiguration, isTrue,
          reason: 'Empty pinnedHosts means pinning never activates — '
              'the user does not get the protection they think they have.');
    });

    test('hasFatalMisconfiguration is true when enabled=false is the only '
        'flag set, but installGlobal skips the throw in that case', () {
      const cfg = CertPinConfig(
        allowedSha256Base64: <String>{'a', 'b'},
        pinnedHosts: <String>{'api.opene2ee.com'},
        enabled: false,
      );
      // hasFatalMisconfiguration stays true — it is a structural check,
      // not a runtime "should I throw?" check.
      expect(cfg.hasFatalMisconfiguration, isTrue);
    });

    test('matchesHost is exact (no wildcard, no subdomain match)', () {
      const cfg = CertPinConfig(
        allowedSha256Base64: <String>{'a', 'b'},
        pinnedHosts: <String>{'api.opene2ee.com'},
        enabled: true,
      );
      expect(cfg.matchesHost('api.opene2ee.com'), isTrue);
      // Subdomain mismatch is intentional — operators add each production
      // FQDN explicitly. Defence-in-depth is provided natively by the
      // Android/iOS pin-set which DOES include `NSIncludesSubdomains`.
      expect(cfg.matchesHost('staging.opene2ee.com'), isFalse);
      expect(cfg.matchesHost('opene2ee.com'), isFalse);
      expect(cfg.matchesHost(''), isFalse);
    });
  });

  group('PinnedHttpOverrides.acceptsHostAndPin', () {
    final pins = <String>{'ALPHA', 'BETA'};
    final hosts = <String>{'api.opene2ee.com'};

    test('accepts when enabled=true, host matches, hash matches', () {
      final got = PinnedHttpOverrides.acceptsHostAndPin(
        pins: pins,
        hosts: hosts,
        enabled: true,
        host: 'api.opene2ee.com',
        certSha256B64: 'ALPHA',
      );
      expect(got, isTrue);
    });

    test('rejects when enabled=false (override should be a no-op)', () {
      final got = PinnedHttpOverrides.acceptsHostAndPin(
        pins: pins,
        hosts: hosts,
        enabled: false,
        host: 'api.opene2ee.com',
        certSha256B64: 'ALPHA',
      );
      expect(got, isFalse,
          reason: 'enabled=false is the explicit "disable pinning" path. '
              'Returning false here is intentional — any caller treating '
              'the override as "off" still goes through normal system trust.');
    });

    test('rejects when host is not pinned (other hosts route via '
        'system trust)', () {
      final got = PinnedHttpOverrides.acceptsHostAndPin(
        pins: pins,
        hosts: hosts,
        enabled: true,
        host: 'staging.opene2ee.com',
        certSha256B64: 'ALPHA',
      );
      expect(got, isFalse);
    });

    test('rejects when hash is not in the pin set (the actual MITM '
        'defence path)', () {
      final got = PinnedHttpOverrides.acceptsHostAndPin(
        pins: pins,
        hosts: hosts,
        enabled: true,
        host: 'api.opene2ee.com',
        certSha256B64: 'GAMMA', // not in the set
      );
      expect(got, isFalse,
          reason: 'Unknown hash = unknown cert. This is the line of defence '
              'we ship MOB-8 to provide.');
    });

    test('rejects when BOTH host and hash mismatch (no partial credit)', () {
      final got = PinnedHttpOverrides.acceptsHostAndPin(
        pins: pins,
        hosts: hosts,
        enabled: true,
        host: 'attacker.example.com',
        certSha256B64: 'GAMMA',
      );
      expect(got, isFalse);
    });
  });

  group('PinnedHttpOverrides.installGlobal — misconfiguration guard', () {
    test('throws StateError on enabled=true with only one pin (cannot '
        'survive a cert rotation)', () {
      expect(
        () => PinnedHttpOverrides.installGlobal(const CertPinConfig(
          allowedSha256Base64: <String>{'only-one'},
          pinnedHosts: <String>{'api.opene2ee.com'},
          enabled: true,
        )),
        throwsA(isA<StateError>()),
        reason: 'Single-pin configs are a footgun — any cert rotation '
            'bricks the install base with no way to recover. installGlobal '
            'MUST fail closed at boot.',
      );
    });

    test('throws StateError on enabled=true with no pinned hosts', () {
      expect(
        () => PinnedHttpOverrides.installGlobal(const CertPinConfig(
          allowedSha256Base64: <String>{'a', 'b'},
          pinnedHosts: <String>{},
          enabled: true,
        )),
        throwsA(isA<StateError>()),
        reason: 'enabled=true without pinnedHosts means the override '
            'installs but never activates. The user thinks they have pinning '
            'and they do not.',
      );
    });

    test('does NOT throw when enabled=false (dev escape hatch)', () {
      // No exception: in dev builds, an enabled=false config lets the
      // app talk to a self-signed backend. The override is installed as
      // a no-op so the validator surface is still consistent.
      //
      // Note: we DON'T touch `io.HttpOverrides.global` directly here.
      // Under `flutter test`, `flutter_test`'s transitive export of
      // `package:test`'s `HttpOverrides` shadows `dart:io`'s class,
      // which lacks the `global` getter. The install itself is the
      // assertion: if `installGlobal` misbehaves (e.g. throws when it
      // shouldn't), this test fails.
      PinnedHttpOverrides.installGlobal(const CertPinConfig(
        allowedSha256Base64: <String>{'a', 'b'},
        pinnedHosts: <String>{'api.opene2ee.com'},
        enabled: false,
      ));
    });

    test('well-formed production config installs without exception', () {
      // The negative assertion (no throw) is the one we care about — the
      // install-time validate is the regression-guard. Asserting on
      // `HttpOverrides.global` directly is brittle under flutter_test's
      // shadow import, so we don't depend on it here.
      PinnedHttpOverrides.installGlobal(const CertPinConfig(
        allowedSha256Base64: <String>{'production-pin', 'backup-pin'},
        pinnedHosts: <String>{'api.opene2ee.com', 'staging.opene2ee.com'},
        enabled: true,
      ));
      // Smoke check: instantiating with the same input survives a second
      // call (idempotent install — even though the runtime stores the
      // latest reference, the guard must not throw on a re-install).
      PinnedHttpOverrides.installGlobal(const CertPinConfig(
        allowedSha256Base64: <String>{'production-pin', 'backup-pin'},
        pinnedHosts: <String>{'api.opene2ee.com', 'staging.opene2ee.com'},
        enabled: true,
      ));
    });
  });
}
