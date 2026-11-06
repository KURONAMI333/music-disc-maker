package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Spotify トラック URL を「クレデンシャル不要」で扱う。
 *
 * <p>Spotify の公開トラックページは認証なしで {@code og:title} (曲名) と {@code og:description}
 * ("Artist · Album · Song · Year") を返すので、そこからクリーンな曲名・アーティストを取得し、
 * YouTube 検索で再生ソースを得る (Discord の Jockie Music と同じ「Spotify=メタ情報、YouTube=再生」方式)。
 * Client ID/Secret も独自 token server も不要。og タグは正規表現で抽出 (jsoup 不要)。
 *
 * <p>取得の作法 (回数・間隔・全体の締切・UA) は {@link PageMeta} に置いてある。他の音楽サービスの
 * 照会も同じ 1 往復なので、作法をここに写すと片方だけ直した時に無応答のページで解決スレッド
 * (2 本) が詰まる。
 */
final class SpotifyResolver {

    private static final Pattern TRACK = Pattern.compile(
            "(?i)^https?://open\\.spotify\\.com/(?:intl-[a-z]{2,}/)?track/[A-Za-z0-9]+.*");

    /** og タグを読みに行く 1 回分。テストはこれを差し替えてネットワークに出ない。 */
    @FunctionalInterface
    interface PageFetcher extends PageMeta.Fetcher {
    }

    /** 試行の間の待ち。テストは待たずに記録だけする。 */
    @FunctionalInterface
    interface Sleeper extends PageMeta.Sleeper {
    }

    private SpotifyResolver() {
    }

    static boolean isSpotifyTrack(String url) {
        return url != null && TRACK.matcher(url.trim()).matches();
    }

    /** @return [曲名, アーティスト]、取得できなければ {@code null}。 */
    static String[] fetchMeta(String url) {
        return fetchMeta(url, PageMeta::fetchPage, System::currentTimeMillis, Thread::sleep);
    }

    /**
     * {@link #fetchMeta(String)} の本体。取りに行く関数・時計・待ちを差し替えられる。
     *
     * @param url      Spotify のトラック URL
     * @param fetcher  1 回分の取得 (例外か og タグの無い HTML で失敗を表す)
     * @param clockMs  経過を測る時計
     * @param sleeper  試行の間の待ち
     * @return [曲名, アーティスト]、取得できなければ {@code null}
     */
    static String[] fetchMeta(String url, PageFetcher fetcher, LongSupplier clockMs, Sleeper sleeper) {
        final String html = PageMeta.fetchHtml(url, "Spotify", h -> parseMeta(h) != null,
                fetcher, clockMs, sleeper);
        return html == null ? null : parseMeta(html);
    }

    /**
     * og タグから曲名とアーティストを取り出す。
     *
     * @param html Spotify のページ
     * @return [曲名, アーティスト]。曲名が無ければ {@code null}
     */
    static String[] parseMeta(String html) {
        if (html == null) {
            return null;
        }
        final String title = PageMeta.og(html, "title");
        if (title == null || title.isBlank()) {
            return null;
        }
        String artist = "";
        final String desc = PageMeta.og(html, "description");
        if (desc != null && !desc.isBlank()) {
            final String[] parts = desc.split(" · ");
            if (parts.length > 0) {
                artist = parts[0].trim();
            }
        }
        return new String[] {title.trim(), artist};
    }
}
