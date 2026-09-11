package com.kuronami.musicdiscmaker.component;

/**
 * ブームボックスの画面同期用の再生状態。
 *
 * <p>曲名・尺・曲数は媒体を解決すれば得られるため、ここへ重ねて保持しない。{@code elapsedMs} は
 * 選択曲内の経過で、停止時は 0、一時停止時は保存された offset を返す。
 */
public record BoomboxPlaybackState(PlaybackCursor cursor, long elapsedMs, boolean repeat,
        boolean shuffle, int volumePercent) {

    public BoomboxPlaybackState {
        if (elapsedMs < 0L) {
            elapsedMs = 0L;
        }
        volumePercent = Math.max(0, Math.min(100, volumePercent));
    }
}
