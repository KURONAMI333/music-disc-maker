package com.kuronami.musicdiscmaker.lavaplayer;

import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spotify トラック URL を「クレデンシャル不要」で扱う。
 *
 * <p>Spotify の公開トラックページは認証なしで {@code og:title} (曲名) と {@code og:description}
 * ("Artist · Album · Song · Year") を返すので、そこからクリーンな曲名・アーティストを取得し、
 * YouTube 検索で再生ソースを得る (Discord の Jockie Music と同じ「Spotify=メタ情報、YouTube=再生」方式)。
 * Client ID/Secret も独自 token server も不要。og タグは正規表現で抽出 (jsoup 不要)。
 *
 * <h2>なぜ 1 回で諦めないか</h2>
 * この 1 往復が失敗すると、URL からは曲名も分からないので盤を作る手が完全に消える。実際に
 * 起きる失敗は<b>接続の取りこぼしと、JS シェル (og タグの無い HTML) の返却</b>が主で、
 * どちらも直後に引き直すと通ることが多い。回数は 3 回まで、間隔は短く、全体の時間も上限で
 * 押さえる (解決スレッドは 2 本しかない)。
 */
final class SpotifyResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpotifyResolver.class);

    private static final Pattern TRACK = Pattern.compile(
            "(?i)^https?://open\\.spotify\\.com/(?:intl-[a-z]{2,}/)?track/[A-Za-z0-9]+.*");
    // og タグはソーシャルクローラー向けに SSR される。通常の Chrome UA だと JS シェルが返り og が無い。
    private static final String UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)";
    private static final Pattern CONTENT = Pattern.compile("(?i)\\bcontent\\s*=\\s*[\"']([^\"']*)[\"']");

    /** ページを取りに行く回数。 */
    private static final int META_ATTEMPTS = 3;
    /** 引き直すまでの間隔 (ms)。短く切るのは、待つより取り直す方が効く失敗だから。 */
    private static final long META_RETRY_DELAY_MS = 500L;
    /**
     * 引き直しを含めた全体の上限 (ms)。各試行の connect / socket は 10 秒なので、
     * 無応答が続くとそのまま 30 秒になる。ここで頭を押さえる。
     *
     * <p>途中の HTTP 呼び出しは横から打ち切れないため、この上限は<b>次の試行を始めるか</b>の
     * 判定にだけ効く (始めた試行は自分の timeout まで待つ)。
     */
    private static final long META_BUDGET_MS = 12_000L;

    /** og タグを読みに行く 1 回分。テストはこれを差し替えてネットワークに出ない。 */
    @FunctionalInterface
    interface PageFetcher {

        String fetch(String url) throws Exception;
    }

    /** 試行の間の待ち。テストは待たずに記録だけする。 */
    @FunctionalInterface
    interface Sleeper {

        void sleep(long ms) throws InterruptedException;
    }

    private SpotifyResolver() {
    }

    static boolean isSpotifyTrack(String url) {
        return url != null && TRACK.matcher(url.trim()).matches();
    }

    /** @return [曲名, アーティスト]、取得できなければ {@code null}。 */
    static String[] fetchMeta(String url) {
        return fetchMeta(url, SpotifyResolver::fetchPage, System::currentTimeMillis, Thread::sleep);
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
        final long deadline = clockMs.getAsLong() + META_BUDGET_MS;
        for (int attempt = 1; attempt <= META_ATTEMPTS; attempt++) {
            try {
                final String[] meta = parseMeta(fetcher.fetch(url.trim()));
                if (meta != null) {
                    return meta;
                }
                // og タグの無い HTML (JS シェル) も失敗として扱う。待っても同じものが返るとは
                // 限らないので、取り直す価値がある。
                LOGGER.debug("No og tags on the Spotify page ({}), attempt {}/{}", url, attempt, META_ATTEMPTS);
            } catch (final Throwable t) {
                LOGGER.warn("Failed to fetch the Spotify page ({}) attempt {}/{}", url, attempt,
                        META_ATTEMPTS, t);
            }
            if (attempt == META_ATTEMPTS || clockMs.getAsLong() + META_RETRY_DELAY_MS >= deadline) {
                break;
            }
            try {
                sleeper.sleep(META_RETRY_DELAY_MS);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    /**
     * og タグを 1 回読みに行く。
     *
     * <p>タイムアウト未設定だと Spotify 無応答時に解決スレッド (全体で2本) が永久ブロックする。
     *
     * @param url Spotify のトラック URL
     * @return HTML
     * @throws Exception 取得できなかった
     */
    private static String fetchPage(String url) throws Exception {
        final RequestConfig cfg = RequestConfig.custom()
                .setConnectTimeout(10_000)
                .setSocketTimeout(10_000)
                .setConnectionRequestTimeout(10_000)
                .build();
        try (CloseableHttpClient http = HttpClients.custom().setDefaultRequestConfig(cfg).build()) {
            final HttpGet get = new HttpGet(url.trim());
            get.setHeader("User-Agent", UA);
            get.setHeader("Accept-Language", "en");
            return http.execute(get,
                    resp -> EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8));
        }
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
        final String title = og(html, "title");
        if (title == null || title.isBlank()) {
            return null;
        }
        String artist = "";
        final String desc = og(html, "description");
        if (desc != null && !desc.isBlank()) {
            final String[] parts = desc.split(" · ");
            if (parts.length > 0) {
                artist = parts[0].trim();
            }
        }
        return new String[] {title.trim(), artist};
    }

    /** {@code <meta property="og:<prop>" content="...">} の content を取り出す。 */
    private static String og(String html, String prop) {
        final Matcher tag = Pattern.compile(
                "(?i)<meta\\b[^>]*\\bproperty\\s*=\\s*[\"']og:" + prop + "[\"'][^>]*>").matcher(html);
        if (tag.find()) {
            final Matcher c = CONTENT.matcher(tag.group());
            if (c.find()) {
                return unescape(c.group(1));
            }
        }
        return null;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&#x27;", "'").replace("&apos;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&#x2F;", "/").replace("&#47;", "/");
    }
}
