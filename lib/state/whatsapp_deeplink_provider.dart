// lib/state/whatsapp_deeplink_provider.dart
//
// Sprint 22.3 — WhatsApp deep link helper.
//
// The original `whatsapp://send?text=<urlencoded-message>`
// URI scheme is NOT routed by Android's intent dispatcher to
// the WhatsApp package on all OEM ROMs (notably Xiaomi MIUI),
// so the `canLaunchUrl` check returned `true` on the dev
// tablet but the actual launch silently no-op'd (no app
// opened, no error surfaced).
//
// 2-tier fallback (wa.me primary, intent:// secondary)
// -----------------------------------------------------
// The primary path is the WhatsApp "click-to-chat" web URL
// `https://wa.me/?text=<urlencoded>` — wa.me is a public
// HTTPS domain whose App Links manifest routes Chrome Custom
// Tabs directly to the WhatsApp package. This path bypasses
// the Magisk intent-interception layer (HTTPS → wa.me
// redirect → WA) and works on every Android OEM ROM verified
// so far.
//
// The legacy `intent://send?text=...#Intent;scheme=whatsapp;
// package=com.whatsapp;end` URI stays as the secondary
// fallback for the rare device where wa.me routing is not
// yet live.
//
// The `tryOpenWithReason()` API surfaces a debug-friendly
// tuple so the WhatsApp task detail screen's snackbar can
// show the user exactly which tier succeeded / failed. The
// legacy boolean `tryOpen()` is preserved (now implemented
// over `tryOpenWithReason`) so any future call site that
// still wants the bool-only shape does not have to change.
//
// Privacy
// -------
// The encoded message is the test fixture
// `Bu mesaj şifreleme bütünlüğü için test amacıyla
// gönderilmiştir.` — the same string the original Sprint
// 10.0 used. No device id, no IMEI, no MSISDN, no contacts.

import 'package:url_launcher/url_launcher.dart';

/// Result of a `tryOpenWithReason()` call. The `ok` bool is
/// the final "did something open" verdict; `reason` is a
/// Turkish-language debug string intended for the snackbar in
/// `whatsapp_task_detail_screen.dart`.
class WhatsAppDeepLinkResult {
  const WhatsAppDeepLinkResult({required this.ok, this.reason});
  final bool ok;
  final String? reason;
}

class WhatsAppDeepLink {
  WhatsAppDeepLink._();

  // The two halves of the Android Intent URI (kept for the
  // intent:// fallback tier).
  static const String _intentPrefix = 'intent://send?';
  static const String _intentSuffix =
      '#Intent;scheme=whatsapp;package=com.whatsapp;end';
  // Primary path is the WhatsApp "click-to-chat" web URL.
  // wa.me routes via HTTPS → Chrome Custom Tabs → WhatsApp
  // package via wa.me's App Links manifest declaration.
  static const String _waMeBase = 'https://wa.me/?text=';

  static const String message =
      'Bu mesaj şifreleme bütünlüğü için test amacıyla gönderilmiştir.';

  /// Build the Android Intent URI. Exposed so tests / audit
  /// can assert the exact
  /// `intent://send?text=<encoded>#Intent;scheme=whatsapp;package=com.whatsapp;end`
  /// literal.
  static Uri buildUri() {
    return Uri.parse(
      '$_intentPrefix'
      'text=${Uri.encodeComponent(message)}'
      '$_intentSuffix',
    );
  }

  /// Build the wa.me click-to-chat URL. Exposed so tests /
  /// audit can assert the exact `https://wa.me/?text=<urlencoded>`
  /// literal.
  static Uri buildWaMeUri() {
    return Uri.parse('$_waMeBase${Uri.encodeComponent(message)}');
  }

  /// Open WhatsApp with the prepared message, with a 2-tier
  /// fallback (wa.me web URL primary, Android Intent URI
  /// secondary). Returns a `WhatsAppDeepLinkResult` with a
  /// Turkish-language `reason` string describing which tier
  /// succeeded / failed.
  ///
  /// Tier 1 (wa.me): Android intent dispatcher routes via
  /// Chrome Custom Tabs → wa.me → WhatsApp. Survives Magisk /
  /// LSPosed / MIUI intent-interception layers.
  /// Tier 2 (intent://): the Android Intent URI. Kept as a
  /// fallback for the rare device where wa.me routing is not
  /// yet live.
  static Future<WhatsAppDeepLinkResult> tryOpenWithReason() async {
    // Tier 1 — wa.me click-to-chat web URL.
    final waMeUri = buildWaMeUri();
    final canWaMe = await canLaunchUrl(waMeUri);
    if (canWaMe) {
      try {
        final ok = await launchUrl(
          waMeUri,
          mode: LaunchMode.externalApplication,
        );
        if (ok) {
          return const WhatsAppDeepLinkResult(
            ok: true,
            reason: 'wa.me: canLaunchUrl=true, launch=true',
          );
        }
        // canLaunchUrl said yes but launch returned false —
        // fall through to the intent:// tier.
        final fallback = await _tryIntentUri();
        return WhatsAppDeepLinkResult(
          ok: fallback.ok,
          reason: 'wa.me: canLaunchUrl=true, launch=false; ${fallback.reason}',
        );
      } on Exception catch (e) {
        final fallback = await _tryIntentUri();
        return WhatsAppDeepLinkResult(
          ok: fallback.ok,
          reason: 'wa.me: canLaunchUrl=true, launch exception=$e; ${fallback.reason}',
        );
      }
    }
    // Tier 1 wasn't visible at all — go straight to the fallback.
    return _tryIntentUri(prefix: 'wa.me: canLaunchUrl=false; ');
  }

  /// Try the intent:// Android Intent URI. `prefix` lets
  /// `tryOpenWithReason()` prepend context about the wa.me
  /// tier outcome to the final reason string.
  static Future<WhatsAppDeepLinkResult> _tryIntentUri({String prefix = ''}) async {
    final intentUri = buildUri();
    final canIntent = await canLaunchUrl(intentUri);
    if (!canIntent) {
      return WhatsAppDeepLinkResult(
        ok: false,
        reason: '${prefix}intent://: canLaunchUrl=false (her iki yöntem başarısız)',
      );
    }
    try {
      final ok = await launchUrl(
        intentUri,
        mode: LaunchMode.externalApplication,
      );
      if (ok) {
        return WhatsAppDeepLinkResult(
          ok: true,
          reason: '${prefix}intent://: canLaunchUrl=true, launch=true',
        );
      }
      return const WhatsAppDeepLinkResult(
        ok: false,
        reason: 'intent://: canLaunchUrl=true, launch=false (her iki yöntem başarısız)',
      );
    } on Exception catch (e) {
      return WhatsAppDeepLinkResult(
        ok: false,
        reason: '${prefix}intent://: canLaunchUrl=true, launch exception=$e',
      );
    }
  }

  /// Backward-compat boolean wrapper around
  /// `tryOpenWithReason()`. Existing call sites that imported
  /// the legacy `bool ok = await WhatsAppDeepLink.tryOpen();`
  /// surface continue to work without changes. The
  /// `whatsapp_task_detail_screen.dart` calls
  /// `tryOpenWithReason()` directly to surface the debug
  /// reason in the snackbar.
  static Future<bool> tryOpen() async {
    final result = await tryOpenWithReason();
    return result.ok;
  }
}
