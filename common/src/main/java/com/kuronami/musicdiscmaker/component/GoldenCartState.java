package com.kuronami.musicdiscmaker.component;

/**
 * 金ジューク付きトロッコの画面同期用スナップショット。server が menu を開いている player へ
 * {@code golden_cart_state} payload で届ける。
 *
 * <p>{@link BoomboxPlaybackState} と同型だが、金ジューク固有の範囲・指向性を持つ。
 * 曲名・尺は媒体 (menu slot の ItemStack) から client が自分で解決するため運ばない。
 */
public record GoldenCartState(PlaybackCursor cursor, long elapsedMs, boolean repeat,
        boolean shuffle, int volumePercent, int rangeBlocks, boolean directional) {

    public GoldenCartState {
        if (elapsedMs < 0L) {
            elapsedMs = 0L;
        }
        volumePercent = Math.max(GoldenDeck.VOLUME_MIN, Math.min(GoldenDeck.VOLUME_MAX, volumePercent));
        rangeBlocks = Math.max(GoldenDeck.RANGE_MIN, Math.min(GoldenDeck.RANGE_MAX, rangeBlocks));
    }
}
