// lib/models/task_type.dart
//
// Sprint 23 — task-type enum for the v1 backend schema.
//
// The backend's `POST /api/v1/sessions` `task_type` field is
// an enum (see `backend/internal/api/schemas/session-create.schema.json`).
// The value drives:
//   - which transport layer the e2ee-ap-v2 mobile app uses
//     (WhatsApp vs RCS) for the smoke test;
//   - which BFF telemetry bucket the session results land in
//     (so the transparency matrix can render per-transport
//     entropy / latency / integrity scores).
//
// Wire format: the enum `name` is what we send + receive
// over JSON. Keep these literals stable — the backend
// schema validates them on POST.
//
// Adding a new transport (Signal, Telegram, iMessage, etc.)
// requires a schema bump on both sides.

enum TaskType {
  /// Plain text message over WhatsApp (the most-common
  /// path; default for `echobot` mode in Sprint 23).
  whatsappText,

  /// Image attachment over WhatsApp.
  whatsappImage,

  /// Voice note over WhatsApp.
  whatsappVoice,

  /// Plain text over RCS (Universal Profile).
  rcsText,

  /// Image attachment over RCS.
  rcsImage;

  /// The snake_case wire name the backend's v1 schema
  /// (`session-create.schema.json`) validates against.
  /// Use this in every JSON body that goes to the
  /// backend — the literal `TaskType.name` is camelCase
  /// and would be rejected by the schema.
  String get wireName {
    switch (this) {
      case TaskType.whatsappText:
        return 'whatsapp_text';
      case TaskType.whatsappImage:
        return 'whatsapp_image';
      case TaskType.whatsappVoice:
        return 'whatsapp_voice';
      case TaskType.rcsText:
        return 'rcs_text';
      case TaskType.rcsImage:
        return 'rcs_image';
    }
  }

  /// Inverse of [wireName] — `task_type` value (snake_case)
  /// → [TaskType] enum. Returns `null` for unknown values
  /// (forward-compat for transports the client doesn't yet
  /// know about — backend may add new values without a
  /// client bump).
  static TaskType? fromWireName(String? wire) {
    if (wire == null) return null;
    switch (wire) {
      case 'whatsapp_text':
        return TaskType.whatsappText;
      case 'whatsapp_image':
        return TaskType.whatsappImage;
      case 'whatsapp_voice':
        return TaskType.whatsappVoice;
      case 'rcs_text':
        return TaskType.rcsText;
      case 'rcs_image':
        return TaskType.rcsImage;
      default:
        return null;
    }
  }
}
