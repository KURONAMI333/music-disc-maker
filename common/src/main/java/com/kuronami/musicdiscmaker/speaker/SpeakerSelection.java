package com.kuronami.musicdiscmaker.speaker;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * 固定スピーカー配送で、listener ごとに採用する一つのスピーカーを選ぶ純粋な基盤。
 *
 * <p>ここでは位置・有効範囲・mute・音量だけを扱う。コーンの強度や音声の再生方法は、
 * 聴感の裁定が済むまでこの層へ持ち込まない。
 */
public final class SpeakerSelection {

    /** 境界での往復を抑えるため、前の候補を維持する距離差（block）。 */
    public static final double SWITCH_MARGIN_BLOCKS = 0.5D;

    private SpeakerSelection() {
    }

    /** listener の座標。 */
    public record Listener(double x, double y, double z) {

        boolean hasFinitePosition() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        }
    }

    /** 配送先として選べる可能性がある固定スピーカー。 */
    public record Candidate(String id, double x, double y, double z, double rangeBlocks,
                            int volumePercent, boolean muted, double gainMultiplier) {
        public Candidate(String id, double x, double y, double z, double rangeBlocks, int volumePercent, boolean muted) {
            this(id, x, y, z, rangeBlocks, volumePercent, muted, 1.0D);
        }

        boolean isValid() {
            return id != null && !id.isBlank()
                    && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && Double.isFinite(rangeBlocks) && rangeBlocks >= 0.0D
                    && volumePercent > 0 && !muted && Double.isFinite(gainMultiplier) && gainMultiplier >= 0.0D;
        }
    }

    /**
     * 範囲内かつ有効な候補から最寄りを選ぶ。
     *
     * <p>前回の候補がまだ有効なら、新しい最寄り候補が {@value #SWITCH_MARGIN_BLOCKS} block
     * を超えて近くなるまで前回を維持する。前回候補が mute・圏外・不正なら直ちに再選択する。
     */
    public static Optional<Candidate> select(Listener listener, Collection<Candidate> candidates,
                                              String previousCandidateId) {
        if (listener == null || !listener.hasFinitePosition() || candidates == null) {
            return Optional.empty();
        }

        Candidate previous = null;
        double previousDistance = Double.NaN;
        Candidate nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;

        for (Candidate candidate : candidates) {
            final double distance = distanceIfInRange(listener, candidate);
            if (!Double.isFinite(distance)) {
                continue;
            }

            if (previousCandidateId != null && previousCandidateId.equals(candidate.id())) {
                if (previous == null || compareByDistanceThenId(candidate, distance, previous, previousDistance) < 0) {
                    previous = candidate;
                    previousDistance = distance;
                }
            }
            if (nearest == null || compareByDistanceThenId(candidate, distance, nearest, nearestDistance) < 0) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }

        if (nearest == null) {
            return Optional.empty();
        }
        if (previous != null && nearestDistance + SWITCH_MARGIN_BLOCKS >= previousDistance) {
            return Optional.of(previous);
        }
        return Optional.of(nearest);
    }

    private static double distanceIfInRange(Listener listener, Candidate candidate) {
        if (candidate == null || !candidate.isValid()) {
            return Double.NaN;
        }
        final double distance = Math.hypot(Math.hypot(listener.x() - candidate.x(), listener.y() - candidate.y()),
                listener.z() - candidate.z());
        return Double.isFinite(distance) && distance <= candidate.rangeBlocks() ? distance : Double.NaN;
    }

    private static int compareByDistanceThenId(Candidate left, double leftDistance,
                                               Candidate right, double rightDistance) {
        final int byDistance = Double.compare(leftDistance, rightDistance);
        return byDistance != 0 ? byDistance : Comparator.comparing(Candidate::id).compare(left, right);
    }
}
