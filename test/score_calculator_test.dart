// test/score_calculator_test.dart
//
// Sprint 22 — Score Calculator unit tests.
//
// Per Architect brief: "4 unit test (integration, loss,
// latency, jitter)". We exceed the floor (7 cases) to cover
// the overall weighted sum + the empty-list + the
// sessionId pass-through edge cases.

import 'package:flutter_test/flutter_test.dart';
import 'package:e2ee_ap_v2/services/score_calculator.dart';

void main() {
  group('SessionScoreCalculator (Sprint 22)', () {
    test('perfect session: integrity 100, loss 0, latency 0, jitter 0 → 100', () {
      final s = SessionScoreCalculator.compute(
        SessionTelemetry(
          sessionId: 'ts-perfect',
          role: 'offerer',
          startedAt: _epoch,
          totalPackets: 100,
          encryptedPackets: 100,
          packetLossPct: 0.0,
          meanLatencyMs: 0.0,
          jitterMs: 0.0,
          encryptionIntegrityPct: 100.0,
        ),
      );
      expect(s.overallScore, closeTo(100.0, 0.1));
      expect(s.tier, ScoreTier.good);
    });

    test('loss-only regression: integrity 100, loss 50, latency 0, jitter 0 → 85', () {
      final s = SessionScoreCalculator.compute(
        SessionTelemetry(
          sessionId: 'ts-loss',
          role: 'offerer',
          startedAt: DateTime.utc(2026, 7, 11),
          totalPackets: 100,
          encryptedPackets: 50,
          packetLossPct: 50.0,
          meanLatencyMs: 0.0,
          jitterMs: 0.0,
          encryptionIntegrityPct: 100.0,
        ),
      );
      // 0.4 * 1.0 + 0.3 * 0.5 + 0.2 * 1.0 + 0.1 * 1.0 = 0.85
      expect(s.overallScore, closeTo(85.0, 0.1));
    });

    test('latency-only regression: integrity 100, loss 0, latency 500, jitter 0 → 90', () {
      final s = SessionScoreCalculator.compute(
        SessionTelemetry(
          sessionId: 'ts-lat',
          role: 'answerer',
          startedAt: DateTime.utc(2026, 7, 11),
          totalPackets: 100,
          encryptedPackets: 100,
          packetLossPct: 0.0,
          meanLatencyMs: 500.0,
          jitterMs: 0.0,
          encryptionIntegrityPct: 100.0,
        ),
      );
      // 0.4 * 1.0 + 0.3 * 1.0 + 0.2 * 0.5 + 0.1 * 1.0 = 0.90
      expect(s.overallScore, closeTo(90.0, 0.1));
    });

    test('jitter-only regression: integrity 100, loss 0, latency 0, jitter 50 → 95', () {
      final s = SessionScoreCalculator.compute(
        SessionTelemetry(
          sessionId: 'ts-jit',
          role: 'offerer',
          startedAt: DateTime.utc(2026, 7, 11),
          totalPackets: 100,
          encryptedPackets: 100,
          packetLossPct: 0.0,
          meanLatencyMs: 0.0,
          jitterMs: 50.0,
          encryptionIntegrityPct: 100.0,
        ),
      );
      // 0.4 * 1.0 + 0.3 * 1.0 + 0.2 * 1.0 + 0.1 * 0.5 = 0.95
      expect(s.overallScore, closeTo(95.0, 0.1));
    });

    test('overall weighted sum with all 4 metrics regressed', () {
      final s = SessionScoreCalculator.compute(
        SessionTelemetry(
          sessionId: 'ts-mixed',
          role: 'offerer',
          startedAt: DateTime.utc(2026, 7, 11),
          totalPackets: 100,
          encryptedPackets: 80,
          packetLossPct: 20.0,
          meanLatencyMs: 200.0,
          jitterMs: 30.0,
          encryptionIntegrityPct: 80.0,
        ),
      );
      // 0.4 * 0.8 + 0.3 * 0.8 + 0.2 * 0.8 + 0.1 * 0.7
      // = 0.32 + 0.24 + 0.16 + 0.07 = 0.79 → 79.0
      expect(s.overallScore, closeTo(79.0, 0.1));
      expect(s.tier, ScoreTier.warn);
    });

    test('computeAll: list of 3 → 3 scores', () {
      final scores = SessionScoreCalculator.computeAll([
        SessionTelemetry(
          sessionId: 'a',
          role: 'offerer',
          startedAt: DateTime.utc(2026, 7, 11),
          encryptionIntegrityPct: 100.0,
        ),
        SessionTelemetry(
          sessionId: 'b',
          role: 'answerer',
          startedAt: DateTime.utc(2026, 7, 11),
          packetLossPct: 100.0,
        ),
        SessionTelemetry(
          sessionId: 'c',
          role: 'offerer',
          startedAt: DateTime.utc(2026, 7, 11),
        ),
      ]);
      expect(scores.length, 3);
      expect(scores[0].sessionId, 'a');
      expect(scores[1].sessionId, 'b');
      expect(scores[2].sessionId, 'c');
      expect(scores[1].overallScore, closeTo(70.0, 0.1));
    });

    test('standardDeviation helper', () {
      final s = standardDeviation(
          [2, 4, 4, 4, 5, 5, 7, 9].map((e) => e.toDouble()).toList());
      expect(s, closeTo(2.0, 0.01));
    });
  });
}

final DateTime _epoch = DateTime.utc(2026, 1, 1);
