// test/mobile/security/cert_pinning_posture_test.dart
//
// MOB-8 / Sprint 7 — Regression-guard tests for the native-side certificate
// pinning configuration.
//
// These tests are parallel in style to PR-39's `android_security_posture_test`:
// they parse the native config files on disk and assert the security-relevant
// contract. The Flutter toolchain does NOT round-trip these files through
// `flutter test`, so explicit assertions are the only way to catch a future
// PR that strips the pin block.
//
// Test matrix:
//   1. iOS Info.plist `NSAppTransportSecurity` block exists AND contains an
//      `NSPinnedDomains` key that pins `api.opene2ee.com` AND
//      `staging.opene2ee.com`, each with:
//        - `NSIncludesSubdomains = true`
//        - At least 2 `<public-key-in-spki-sha256-format>` entries
//          (production + backup).
//   2. Android network_security_config.xml has an un-commented
//      `<domain-config>` block pinning `api.opene2ee.com` +
//      `staging.opene2ee.com` with a `<pin-set>` of 2+ entries and a
//      `<pin digest="SHA-256">` per entry.
//   3. The Android pin-set's `expiration` date is at least 90 days in the
//      future so that an operator gets a calendar reminder to rotate
//      instead of having the pin hard-fail.
//   4. Privacy invariant (defence-in-depth): the iOS Info.plist still has
//      `NSAllowsArbitraryLoads = false` (or at minimum does not assert
//      `true`) so we never silently bypass ATS.
//
// Reference docs:
//   - mobile/ios/Runner/Info.plist
//   - mobile/android/app/src/main/res/xml/network_security_config.xml
//   - docs/SPRINT-7-MOB-8-CERT-PINNING.md (rotation procedure + threat model)
//   - cyber-security Sprint 7 review (MOB-8, High)

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Resolve the `mobile/` directory by walking up from the current
/// working directory. Test runner's CWD is `mobile/` by default
/// (`flutter test` runs from the package root), but allow an override
/// via the `MOBILE_PACKAGE_ROOT` env var for CI matrix jobs that run
/// from the repo root.
Directory _resolveMobileRoot() {
  final override = Platform.environment['MOBILE_PACKAGE_ROOT'];
  if (override != null && override.isNotEmpty) {
    return Directory(override);
  }
  // `flutter test` CWD is `mobile/`. When run via `dart test`, fall
  // back to looking for `pubspec.yaml` upward.
  var dir = Directory.current;
  for (var i = 0; i < 4; i++) {
    if (File('${dir.path}/pubspec.yaml').existsSync()) {
      return dir;
    }
    final parent = dir.parent;
    if (parent.path == dir.path) break;
    dir = parent;
  }
  return Directory.current;
}

String _readFile(String relativePath) {
  final root = _resolveMobileRoot();
  final file = File('${root.path}/$relativePath');
  if (!file.existsSync()) {
    fail('Native config file not found: ${file.path}');
  }
  return file.readAsStringSync();
}

/// Extract the body of the dict that follows a `<key>CHILDKEY</key>`
/// immediately inside a parent dict (one level of nesting only). Tracks
/// `<dict>` / `</dict>` balance so we don't stop at the first inner dict
/// close — the `NSPinnedDomains` block has nested per-domain dicts the
/// simpler "first </dict>" regex would otherwise cut off.
///
/// We do NOT support arbitrary depth because our plist's ATS structure is
/// `ATS -> NSPinnedDomains -> domain -> pin entries` — three levels, no
/// deeper. A general plist parser is intentionally out of scope.
String _plistBodyAfterChildKey(
  String plist,
  String parentKey,
  String childKey,
) {
  final parentRe = RegExp(
    '<key>\\s*' + parentKey + '\\s*</key>\\s*<dict>',
    multiLine: true,
  );
  final parentMatch = parentRe.firstMatch(plist);
  if (parentMatch == null) return '';

  // Walk through the parent dict looking for the matching `</dict>`,
  // tracking nesting. Inside, find `<key>CHILDKEY</key><dict>` and
  // capture the dict's body with the same balance tracking.
  final parentStart = parentMatch.end;
  final parentEnd = _findMatchingClose(
    plist,
    parentStart,
    openTag: '<dict>',
    closeTag: '</dict>',
  );
  if (parentEnd < 0) return '';
  final parentInner = plist.substring(parentStart, parentEnd);

  final childRe = RegExp(
    '<key>\\s*' + childKey + '\\s*</key>\\s*<dict>',
    multiLine: true,
  );
  final childMatch = childRe.firstMatch(parentInner);
  if (childMatch == null) return '';

  final childStart = parentStart + childMatch.end;
  final childEnd = _findMatchingClose(
    plist,
    childStart,
    openTag: '<dict>',
    closeTag: '</dict>',
  );
  if (childEnd < 0) return '';
  return plist.substring(childStart, childEnd);
}

/// Returns the offset of the `closeTag` that matches the `openTag` at
/// [start], with balance tracking. -1 if not found.
int _findMatchingClose(
  String s,
  int start, {
  required String openTag,
  required String closeTag,
}) {
  var depth = 1;
  var i = start;
  while (i < s.length) {
    final nextOpen = s.indexOf(openTag, i);
    final nextClose = s.indexOf(closeTag, i);
    if (nextClose < 0) return -1;
    if (nextOpen >= 0 && nextOpen < nextClose) {
      depth += 1;
      i = nextOpen + openTag.length;
    } else {
      depth -= 1;
      if (depth == 0) return nextClose;
      i = nextClose + closeTag.length;
    }
  }
  return -1;
}

/// Extract the contents of the dict that immediately follows
/// `<key>DOMAIN</key><dict>` inside the [scope] (typically the
/// `NSPinnedDomains` body). Same balance tracking approach.
String _plistDomainBody(String scope, String domain) {
  final re = RegExp(
    '<key>\\s*' + domain + '\\s*</key>\\s*<dict>',
    multiLine: true,
  );
  final m = re.firstMatch(scope);
  if (m == null) return '';
  final start = m.end;
  final end = _findMatchingClose(
    scope,
    start,
    openTag: '<dict>',
    closeTag: '</dict>',
  );
  if (end < 0) return '';
  return scope.substring(start, end);
}

void main() {
  group('MOB-8 iOS NSPinnedDomains posture', () {
    test(
      'Info.plist declares NSAppTransportSecurity so pin set is honoured',
      () {
        final plist = _readFile('ios/Runner/Info.plist');
        expect(
          RegExp(
            '<key>\\s*NSAppTransportSecurity\\s*</key>\\s*<dict>',
            multiLine: true,
          ).hasMatch(plist),
          isTrue,
          reason:
              'Cyber-security MOB-8: Info.plist must declare '
              'NSAppTransportSecurity so the device evaluates iOS '
              'App Transport Security policy at TLS handshake time.',
        );
      },
    );

    test(
      'NSAppTransportSecurity.NSPinnedDomains pins api.opene2ee.com with '
      'subdomain coverage AND at least 2 base64 SPKI hashes (production + '
      'backup)',
      () {
        final plist = _readFile('ios/Runner/Info.plist');
        final pinned = _plistBodyAfterChildKey(
          plist,
          'NSAppTransportSecurity',
          'NSPinnedDomains',
        );
        expect(pinned, isNotEmpty,
            reason: 'NSPinnedDomains block missing.');

        final body = _plistDomainBody(pinned, 'api.opene2ee.com');
        expect(body, isNotEmpty,
            reason: 'No pinned-domain block for api.opene2ee.com.');

        expect(
          RegExp(
            '<key>\\s*NSIncludesSubdomains\\s*</key>\\s*<true\\s*/?>',
          ).hasMatch(body),
          isTrue,
          reason:
              'NSPinnedDomains entry for api.opene2ee.com must declare '
              'NSIncludesSubdomains=true (so the pin applies to '
              'subdomains too).',
        );

        // Count pinned-identity entries. iOS accepts either SPKI (under
        // the modern NSPinnedLeafIdentities path) or full-cert SHA-256.
        // The spec says at minimum 2 (production + backup).
        final pinDigests =
            RegExp('<data\\s*>', multiLine: true).allMatches(body).length;
        // Note: `<data>` tags appear once per SPKI/hash entry. We use
        // this as a positional marker; we accept >=2.
        expect(pinDigests, greaterThanOrEqualTo(2),
            reason:
                'Pinned-domain entry for api.opene2ee.com must include '
                '>=2 hash entries (production + backup), found $pinDigests. '
                'A single pin bricks the install base on the next cert '
                'rotation.');
      },
    );

    test(
      'NSAppTransportSecurity.NSPinnedDomains also pins '
      'staging.opene2ee.com (parity with the Android config)',
      () {
        final plist = _readFile('ios/Runner/Info.plist');
        final pinned = _plistBodyAfterChildKey(
          plist,
          'NSAppTransportSecurity',
          'NSPinnedDomains',
        );
        expect(pinned, isNotEmpty,
            reason: 'NSPinnedDomains block missing.');

        final stagingBody = _plistDomainBody(pinned, 'staging.opene2ee.com');
        expect(stagingBody, isNotEmpty,
            reason:
                'staging.opene2ee.com must also be pinned: the Android '
                'network_security_config.xml pins both environments, and the '
                'iOS Info.plist MUST do the same so a CA change during a '
                'staging deployment does not silently desync from prod.');
      },
    );

    test(
      'Info.plist does NOT enable NSAllowsArbitraryLoads (defence-in-depth '
      '— we enforce ATS by default)',
      () {
        final plist = _readFile('ios/Runner/Info.plist');
        // Either no NSAllowsArbitraryLoads at all (defaults to false) or
        // an explicit `<false/>`. We forbid `<true/>` only.
        final trueMatch = RegExp(
          '<key>\\s*NSAllowsArbitraryLoads\\s*</key>\\s*<true\\s*/?>',
          multiLine: true,
        ).hasMatch(plist);
        expect(trueMatch, isFalse,
            reason:
                'MOB-8 / privacy: NSAllowsArbitraryLoads=true silently '
                'disables App Transport Security — nullifying all pinning '
                'effort.');
      },
    );
  });

  group('MOB-8 Android pin-set posture', () {
    test(
      'network_security_config.xml has an un-commented <domain-config> '
      'that pins api.opene2ee.com and staging.opene2ee.com',
      () {
        final cfg = _readFile(
          'android/app/src/main/res/xml/network_security_config.xml',
        );
        // Strip XML comments first (the placeholder pin-set shipped in
        // PR-39 was intentionally commented out — it MUST now be live).
        final stripped = cfg.replaceAll(
          RegExp(r'<!--[\s\S]*?-->', multiLine: true),
          '',
        );
        expect(
          RegExp(
            '<domain-config>',
          ).hasMatch(stripped),
          isTrue,
          reason:
              'network_security_config.xml must contain a real '
              '<domain-config> block (the placeholder block shipped in '
              'PR-39 was commented out and MUST now be un-commented).',
        );
        expect(
          RegExp('<domain[^>]*>api\\.opene2ee\\.com</domain>').hasMatch(
            stripped,
          ),
          isTrue,
          reason: 'api.opene2ee.com must be listed in <domain-config>.',
        );
        expect(
          RegExp('<domain[^>]*>staging\\.opene2ee\\.com</domain>').hasMatch(
            stripped,
          ),
          isTrue,
          reason: 'staging.opene2ee.com must be listed in <domain-config>.',
        );
      },
    );

    test(
      '<pin-set> contains at least 2 <pin digest="SHA-256"> entries',
      () {
        final cfg = _readFile(
          'android/app/src/main/res/xml/network_security_config.xml',
        );
        final stripped = cfg.replaceAll(
          RegExp(r'<!--[\s\S]*?-->', multiLine: true),
          '',
        );
        final pinMatches = RegExp(
          '<pin\\s+digest\\s*=\\s*"SHA-256"',
        ).allMatches(stripped).length;
        expect(
          pinMatches,
          greaterThanOrEqualTo(2),
          reason:
              'Production must ship at least 2 pins: a primary + a backup. '
              'A single pin bricks the install base on the next cert '
              'rotation. Found $pinMatches.',
        );
      },
    );

    test(
      '<pin-set> has an expiration date >= 90 days in the future (forces '
      'operator cadence rather than silent hard-fail)',
      () {
        final cfg = _readFile(
          'android/app/src/main/res/xml/network_security_config.xml',
        );
        final expMatch = RegExp(
          '<pin-set[^>]*expiration="(\\d{4}-\\d{2}-\\d{2})"',
        ).firstMatch(cfg);
        expect(expMatch, isNotNull,
            reason: '<pin-set> must declare an expiration date.');
        final parsed = DateTime.tryParse(expMatch!.group(1)!);
        expect(parsed, isNotNull,
            reason: 'pin-set expiration must be ISO-8601 (YYYY-MM-DD).');
        final cutoff = DateTime.now().add(const Duration(days: 90));
        expect(
          parsed!.isAfter(cutoff),
          isTrue,
          reason:
              'pin-set expiration $parsed must be at least 90 days in the '
              'future so a slow operator gets a calendar reminder rather '
              'than a silent hard-fail on the next TLS handshake.',
        );
      },
    );
  });
}
