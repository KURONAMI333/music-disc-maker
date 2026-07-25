package com.kuronami.musicdiscmaker.util;

import java.util.function.LongSupplier;

/**
 * 実時間 (wall-clock) の単一の取得口。
 *
 * <p>再生位置とビート出力は {@code gameTime} ではなく実時間で進む: client の音声は TPS と無関係に
 * 実時間で流れるため、{@code gameTime} を基準にすると server が重い間ずっとビートが遅れ続けて戻らない。
 *
 * <p>差し替え可能にしてあるのは headless テストのため。GameTest は tick を進められても
 * {@link System#currentTimeMillis()} を進められず、時刻源を握らないと「ある時刻のコンパレータ出力」を
 * 固定できない (= 出力値そのものでなく index の状態しか assert できない)。
 */
public final class WallClock {

    private static volatile LongSupplier source = System::currentTimeMillis;

    private WallClock() {
    }

    public static long nowMs() {
        return source.getAsLong();
    }

    /**
     * 時刻源を差し替え、差し替え前の時刻源を返す (テスト専用の seam)。本番経路からは呼ばない。
     *
     * @param replacement 新しい時刻源
     * @return 差し替え前の時刻源 (テスト終了時に戻すため)
     */
    public static LongSupplier swap(LongSupplier replacement) {
        final LongSupplier previous = source;
        source = replacement;
        return previous;
    }
}
