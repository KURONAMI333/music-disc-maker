package com.kuronami.musicdiscmaker.client.audio;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.minecraft.core.BlockPos;

/**
 * 再生できなかったことを、その client の<b>ログ</b>へ理由つきで残す。
 *
 * <h2>画面に出すのは GUI の短い文・ここはログだけ</h2>
 * 利用者に見せる理由は {@code GoldenJukeboxScreen} が金ジュークの画面へ 1 行で出す
 * ({@link GoldenJukeboxFailures} が座標ごとに覚えている)。理由が要るのは「鳴らないな」と思って
 * その jukebox を開いた人だけなので、出口はそこに絞る。チャットへ流すと、<b>その jukebox を
 * 見ていない全員</b>に届き、ラジオの再接続や複数台の同時再生では読む気を無くす量になる。
 *
 * <p>ここが残すのは 1 行の {@code WARN} で、宛先は {@code latest.log}。分類ラベル・曲名・URL は
 * 画面の文には載らないので、切り分けと報告への貼り付けはこちらが受け持つ。
 *
 * <p><b>画面を持たない経路はログだけになる</b> — バックパック・Create の移動構造物・
 * Pocket Jukebox・バニラのジュークボックスは GUI が無いか座標キーを持たない。
 *
 * <p>分類そのものは {@link PlaybackFailure} が持つ (MC 非依存・headless テスト対象)。ここは
 * 文面の組み立てと配送だけを持つ。
 */
public final class PlaybackFailureReport {

    private PlaybackFailureReport() {
    }

    /**
     * 失敗を報告する (main thread から呼ぶこと)。
     *
     * @param track   失敗した曲 (曲名と URL をログに残す。null 可)
     * @param failure 分類済みの失敗
     */
    public static void report(@Nullable CustomTrackData track, PlaybackFailure failure) {
        report(track == null ? null : track.title(), track == null ? null : track.url(), failure);
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
        report(track == null ? null : track.title(), track == null ? null : track.url(), key, token, failure);
    }

    /** {@link #report(CustomTrackData, BlockPos, int, PlaybackFailure)} の曲データを持たない版。 */
    public static void report(@Nullable String title, @Nullable String url, BlockPos key, int token,
            PlaybackFailure failure) {
        MusicDiscMaker.LOGGER.warn("Playback failed [{}] track={} url={} key={} token={}", failure.label(),
                title == null || title.isBlank() ? "?" : title, url == null ? "?" : url,
                key.toShortString(), token);
    }
}
