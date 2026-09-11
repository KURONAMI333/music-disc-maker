package com.kuronami.musicdiscmaker.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * 器から見た再生可能位置の順序と、前後送りの純粋規則。
 *
 * <p>位置は URL や曲情報ではなく {@code discIndex/trackIndex} で識別する。同じ URL が列の別位置に
 * 現れても別の曲順として残る。実際の {@code ItemStack} の展開は {@link MediaSequenceResolver} が担い、
 * この型は Minecraft の型を使わない。
 *
 * <p>previous は現在位置の直前へ移り、先頭では repeat の時だけ末尾へ戻る。再生経過を見て
 * 「曲頭なら前、途中なら頭出し」のように切り替える仕様は未確定なので、時間による分岐は持たない。
 * shuffle の順序生成も未確定であり、呼び側が確定した順序を {@link #of(List)} へ渡す境界だけを持つ。
 */
public final class MediaSequence {

    /** 媒体内の実位置。Album でない媒体は常に {@code 0/trackIndex}。 */
    public record Position(int discIndex, int trackIndex) {
        public Position {
            if (discIndex < 0 || trackIndex < 0) {
                throw new IllegalArgumentException("media position indices must not be negative");
            }
        }
    }

    private final List<Position> positions;

    private MediaSequence(List<Position> positions) {
        this.positions = List.copyOf(positions);
    }

    /** resolver が見つけた再生可能位置を、再生順のまま保持する。 */
    public static MediaSequence of(List<Position> positions) {
        Objects.requireNonNull(positions, "positions");
        for (final Position position : positions) {
            Objects.requireNonNull(position, "position");
        }
        return new MediaSequence(positions);
    }

    /** 再生可能位置が無い列。 */
    public static MediaSequence empty() {
        return new MediaSequence(List.of());
    }

    public boolean isEmpty() {
        return positions.isEmpty();
    }

    public int size() {
        return positions.size();
    }

    public List<Position> positions() {
        return positions;
    }

    public Optional<Position> first() {
        return positions.isEmpty() ? Optional.empty() : Optional.of(positions.get(0));
    }

    /**
     * {@code anchor} を列の先頭に固定し、残りを seed 付き Fisher–Yates で並べ替える。
     *
     * <p>媒体の実位置は変えず、再生順だけを作る。anchor が現行媒体から消えていた場合は物理先頭を
     * 代わりに固定するため、壊れた保存値でも決定的に復帰する。
     */
    public MediaSequence shuffled(Position anchor, long seed) {
        if (positions.size() < 2) {
            return this;
        }
        final int anchorIndex = positions.indexOf(anchor);
        final Position first = positions.get(anchorIndex >= 0 ? anchorIndex : 0);
        final List<Position> remaining = new ArrayList<>(positions);
        remaining.remove(first);
        final Random random = new Random(seed);
        for (int index = remaining.size() - 1; index > 0; index--) {
            final int swapIndex = random.nextInt(index + 1);
            final Position swap = remaining.get(index);
            remaining.set(index, remaining.get(swapIndex));
            remaining.set(swapIndex, swap);
        }
        remaining.add(0, first);
        return new MediaSequence(remaining);
    }

    /**
     * 次の再生可能位置。末尾では repeat 時だけ列の先頭へ戻る。
     * 保存値が現行列に無い時は、壊れた位置を再利用せず先頭から復帰する。
     */
    public Optional<Position> next(Position current, boolean repeat) {
        if (positions.isEmpty()) {
            return Optional.empty();
        }
        final int index = positions.indexOf(current);
        if (index < 0) {
            return first();
        }
        if (index + 1 < positions.size()) {
            return Optional.of(positions.get(index + 1));
        }
        return repeat ? first() : Optional.empty();
    }

    /**
     * 前の再生可能位置。先頭では repeat 時だけ列の末尾へ戻る。
     * 保存値が現行列に無い時は末尾へ復帰する。
     */
    public Optional<Position> previous(Position current, boolean repeat) {
        if (positions.isEmpty()) {
            return Optional.empty();
        }
        final int index = positions.indexOf(current);
        if (index < 0) {
            return Optional.of(positions.get(positions.size() - 1));
        }
        if (index > 0) {
            return Optional.of(positions.get(index - 1));
        }
        return repeat ? Optional.of(positions.get(positions.size() - 1)) : Optional.empty();
    }

    /**
     * サーバーの時計で自動送りしてよい曲か。手動操作はこの判定を使わず、LIVE・尺不明からも脱出できる。
     */
    public static boolean allowsAutomaticAdvance(long durationMs, boolean radio) {
        return !radio && durationMs > 0L;
    }
}
