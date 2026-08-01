// lib/state/is_accepted_provider.dart
//
// Sprint 22 — bilgilendirme (consent) acceptance flag.
//
// Tracks whether the user has acknowledged the bilgilendirme screen.
// Mock, in-memory only for Sprint 22; persistence (Sprint 23+)
// replaces this with a SharedPreferences-backed notifier.
//
// On the very first run, the value is `false` and the router
// redirects the user to `/bilgilendirme` before showing `/home`.

import 'package:flutter_riverpod/flutter_riverpod.dart';

final isAcceptedProvider = StateProvider<bool>((ref) => false);
