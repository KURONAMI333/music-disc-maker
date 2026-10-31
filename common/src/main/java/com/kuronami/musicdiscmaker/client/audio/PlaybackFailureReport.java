package com.kuronami.musicdiscmaker.client.audio;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

/**
 * 再生できなかったことを {@code latest.log} に残す。チャットには出さない (D22)。
 *
 * <p>チャットは<b>その jukebox を見ていない全員</b>に流れる。理由が要るのは「鳴らないな」と
 * 思って画面を開いた人だけで、ラジオの再接続や複数台の同時再生では読む気を無くす量になる。
 * 金ジュークは {@link GoldenJukeboxFailures} が座標ごとに覚えた理由を画面の一言ラベルに出し、
 * GUI を持たない面 (バックパック・移動構造物・Pocket Jukebox・バニラのジュークボックス) は
 * このログだけになる。
 *
 * <p>分類そのものは {@link PlaybackFailure} が持つ (MC 非依存・headless テスト対象)。ここは
 * ログ行の組み立てだけを持つ。
 */
public final class PlaybackFailureReport {

    private PlaybackFailureReport() {
    }

    /**
     * 失敗を報告する。
     *
     * @param track   失敗した曲 (曲名と URL をログに残す。null 可)
     * @param failure 分類済みの失敗
     */
    public static void report(@Nullable CustomTrackData track, PlaybackFailure failure) {
        report(track == null ? null : track.title(), track == null ? null : track.sourceKey(), failure);
    }

    /** 曲データを持たない経路 (プレイリスト解決前など) 向け。 */
    public static void report(@Nullable String title, @Nullable String url, PlaybackFailure failure) {
        MusicDiscMaker.LOGGER.warn("Playback failed [{}] track={} url={}", failure.label(),
                title == null || title.isBlank() ? "?" : title, url == null ? "?" : url);
    }

    /**
     * 座標 (key) と再生の世代 (token) が分かる呼び出し元向け (計測用。挙動は変えない)。
     *
     * <p>{@code key}/{@code token} は {@link ClientPlaybackManager} が持ち回っている値をそのまま
     * 渡すだけで、この行専用に新しく採番するものではない ({@code PlaybackTiming} の
     * "Track switch" 行に載る token と同じ値になる)。同じ再生を指すログ行同士を突き合わせるためのもの。
     */
    public static void report(@Nullable CustomTrackData track, BlockPos key, int token, PlaybackFailure failure) {
        report(track == null ? null : track.title(), track == null ? null : track.sourceKey(), key, token, failure);
    }

    /** {@link #report(CustomTrackData, BlockPos, int, PlaybackFailure)} の曲データを持たない版。 */
    public static void report(@Nullable String title, @Nullable String url, BlockPos key, int token,
            PlaybackFailure failure) {
        MusicDiscMaker.LOGGER.warn("Playback failed [{}] track={} url={} key={} token={}", failure.label(),
                title == null || title.isBlank() ? "?" : title, url == null ? "?" : url,
                key.toShortString(), token);
    }
}
