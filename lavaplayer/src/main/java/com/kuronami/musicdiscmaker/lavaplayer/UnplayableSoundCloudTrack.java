package com.kuronami.musicdiscmaker.lavaplayer;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;

/**
 * 単曲の SoundCloud URL が、プレビューしか無い・DRM のどちらかで鳴らせない時の例外。
 *
 * <p>文面は {@code FailureClassifier} の目印 ({@code PREVIEW_MARKER} / {@code DRM_MARKER}) のまま
 * なので、分類は素の {@link FriendlyException} と変わらない。違うのは<b>曲の情報を持って帰る</b>
 * こと — {@link MusicLoaderImpl} はそれで同じ曲を別の配信元から探す。エラーを出して終わりに
 * しないため (2026-11-01 本人「エラー文を書けば解決ってわけでもない」)。
 *
 * <p>読み込みは LavaPlayer の別スレッドで走り、例外だけが呼び出し側へ渡るので、情報は
 * スレッドローカルではなく例外そのものに載せる。
 */
final class UnplayableSoundCloudTrack extends FriendlyException {

    private static final long serialVersionUID = 1L;

    /** 曲名 (SoundCloud の {@code title})。 */
    final String title;
    /** 投稿者の表示名 (SoundCloud の {@code user.username})。 */
    final String author;
    /** 全曲の長さ (ms)。プレビューの曲でも全長を名乗る {@code full_duration}。不明なら 0。 */
    final long fullDurationMs;

    UnplayableSoundCloudTrack(String marker, String title, String author, long fullDurationMs) {
        super(marker, Severity.COMMON, null);
        this.title = title == null ? "" : title;
        this.author = author == null ? "" : author;
        this.fullDurationMs = Math.max(0L, fullDurationMs);
    }
}
