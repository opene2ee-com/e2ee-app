// test/models_test.dart
//
// Sprint 23.0 — model enum wire-format tests.
//
// Verifies the v1 schema mapping for the new SessionMode and
// TaskType enums:
//   - `wireName` is what the backend's v1 schema accepts
//     (snake_case for TaskType, lowercase for SessionMode)
//   - `fromWireName` is the inverse (with `null` for
//     forward-compat with future values)
//
// These tests pin the wire contract — if a future schema
// bump changes a wire name, this test fails loudly so the
// mapping can be updated in lockstep.

import 'package:flutter_test/flutter_test.dart';
import 'package:e2ee_ap_v2/models/session_mode.dart';
import 'package:e2ee_ap_v2/models/task_type.dart';

void main() {
  group('SessionMode (Sprint 23.0)', () {
    test('all 3 values exist', () {
      const modes = {
        SessionMode.p2p,
        SessionMode.echobot,
        SessionMode.single,
      };
      expect(modes.length, 3,
          reason: 'Adding a new mode is a breaking schema change — '
              'update both this test and the backend schema in lockstep.');
    });

    test('wireName matches the v1 schema (lowercase enum identifier)', () {
      expect(SessionMode.p2p.wireName, 'p2p');
      expect(SessionMode.echobot.wireName, 'echobot');
      expect(SessionMode.single.wireName, 'single');
    });

    test('fromWireName round-trips the wire values', () {
      expect(SessionMode.fromWireName('p2p'), SessionMode.p2p);
      expect(SessionMode.fromWireName('echobot'), SessionMode.echobot);
      expect(SessionMode.fromWireName('single'), SessionMode.single);
    });

    test('fromWireName returns null for unknown / null input', () {
      // Forward-compat: backend may add new modes without a
      // client bump. The Dart side must not crash on those.
      expect(SessionMode.fromWireName('p2p_group'), isNull);
      expect(SessionMode.fromWireName(''), isNull);
      expect(SessionMode.fromWireName(null), isNull);
    });
  });

  group('TaskType (Sprint 23.0)', () {
    test('all 5 values exist', () {
      const types = {
        TaskType.whatsappText,
        TaskType.whatsappImage,
        TaskType.whatsappVoice,
        TaskType.rcsText,
        TaskType.rcsImage,
      };
      expect(types.length, 5,
          reason: 'Adding a new transport is a breaking schema '
              'change — update both this test and the backend '
              'schema in lockstep.');
    });

    test('wireName is snake_case (NOT enum.name camelCase)', () {
      // This is the critical invariant: the backend validates
      // against snake_case. The Dart enum.name is camelCase
      // and would be rejected by the schema if sent raw.
      expect(TaskType.whatsappText.wireName, 'whatsapp_text');
      expect(TaskType.whatsappImage.wireName, 'whatsapp_image');
      expect(TaskType.whatsappVoice.wireName, 'whatsapp_voice');
      expect(TaskType.rcsText.wireName, 'rcs_text');
      expect(TaskType.rcsImage.wireName, 'rcs_image');
    });

    test('enum.name stays camelCase (we never send name over the wire)', () {
      expect(TaskType.whatsappText.name, 'whatsappText');
      expect(TaskType.rcsImage.name, 'rcsImage');
    });

    test('fromWireName round-trips the snake_case values', () {
      expect(TaskType.fromWireName('whatsapp_text'), TaskType.whatsappText);
      expect(TaskType.fromWireName('whatsapp_image'), TaskType.whatsappImage);
      expect(TaskType.fromWireName('whatsapp_voice'), TaskType.whatsappVoice);
      expect(TaskType.fromWireName('rcs_text'), TaskType.rcsText);
      expect(TaskType.fromWireName('rcs_image'), TaskType.rcsImage);
    });

    test('fromWireName returns null for camelCase (would-be misuses)', () {
      // Guard against the obvious mistake: passing the raw
      // `enum.name` (camelCase) to fromWireName. Should
      // return null so the parser surfaces a clear error
      // rather than silently coercing.
      expect(TaskType.fromWireName('whatsappText'), isNull);
      expect(TaskType.fromWireName('rcsImage'), isNull);
    });

    test('fromWireName returns null for unknown / null / empty', () {
      expect(TaskType.fromWireName('signal_text'), isNull);
      expect(TaskType.fromWireName(''), isNull);
      expect(TaskType.fromWireName(null), isNull);
    });
  });
}
