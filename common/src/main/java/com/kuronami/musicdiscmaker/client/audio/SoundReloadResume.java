package com.kuronami.musicdiscmaker.client.audio;

/** sound engine 再読み込みの復帰経路が共有する、再開位置の計算。 */
final class SoundReloadResume {
    private SoundReloadResume() {}

    /**
     * server が最後に送った位置を、代わりの voice を開く時点まで進める。
     *
     * @return 再開位置。有限長の曲が既に終わっていれば {@code -1}
     */
    static long offset(long suppliedOffsetMs, long receivedPlaybackMs, long nowPlaybackMs,
            long durationMs, boolean radio) {
        if (radio) return 0L;
        final long elapsed = Math.max(0L, nowPlaybackMs - receivedPlaybackMs);
        final long base = Math.max(0L, suppliedOffsetMs);
        final long advanced = elapsed > Long.MAX_VALUE - base ? Long.MAX_VALUE : base + elapsed;
        return durationMs > 0L && advanced >= durationMs ? -1L : advanced;
    }
}
