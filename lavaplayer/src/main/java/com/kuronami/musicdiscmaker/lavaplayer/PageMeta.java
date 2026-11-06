package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
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
 * 音楽サービスの曲の情報を<b>1 往復</b>で読み、曲名・アーティスト・尺の材料を取り出す共通部分。
 *
 * <p>Spotify は公開ページの {@code og:} タグ、Apple Music / Deezer は認証不要の照会 API
 * (JSON) と<b>読む先は違う</b>が、欲しいものは同じで、違うのは「どこに曲名が書いてあるか」
 * だけ。取得の作法 — 回数・間隔・全体の締切・ソーシャルクローラー向けの UA — を相手ごとに
 * 写すと、片方だけ直した時に<b>無応答のページで解決スレッドが詰まる</b>という同じ不具合が
 * 戻ってくる。ここへ 1 つだけ置く。
 *
 * <h2>なぜ 1 回で諦めないか</h2>
 * この 1 往復が失敗すると、URL からは曲名も分からないので盤を作る手が完全に消える。実際に
 * 起きる失敗は<b>接続の取りこぼしと、中身の読めない応答 (JS シェル・空の results) の返却</b>が
 * 主で、どちらも直後に引き直すと通ることが多い。回数は 3 回まで、間隔は短く、全体の時間も
 * 上限で押さえる (解決スレッドは 2 本しかない)。
 */
final class PageMeta {

    private static final Logger LOGGER = LoggerFactory.getLogger(PageMeta.class);

    /** ページを取りに行く回数。 */
    static final int ATTEMPTS = 3;
    /** 引き直すまでの間隔 (ms)。短く切るのは、待つより取り直す方が効く失敗だから。 */
    static final long RETRY_DELAY_MS = 500L;
    /**
     * 1 回の取得に掛けてよい上限 (ms)。connect / socket / 接続待ちのすべてに同じ値を置く。
     *
     * <p>実際に使う値は<b>残り時間との小さい方</b>。無応答のページを掴んだ時に、全体の上限
     * ({@link #BUDGET_MS}) を過ぎて待ち続けないようにするため。
     */
    static final long PAGE_TIMEOUT_MS = 10_000L;
    /**
     * 引き直しを含めた全体の上限 (ms)。解決スレッドは 2 本しかないので、ここで頭を押さえる。
     *
     * <p>各試行の timeout は<b>この残り時間に縮む</b> ({@link #PAGE_TIMEOUT_MS})。打ち切れない
     * 1 往復が上限を超えて伸びることは無い。
     */
    static final long BUDGET_MS = 12_000L;

    // ページはソーシャルクローラー向けに SSR される。通常の Chrome UA だと JS シェルが返り、
    // 曲名も og タグも入っていない。
    private static final String UA =
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)";
    private static final Pattern CONTENT = Pattern.compile("(?i)\\bcontent\\s*=\\s*[\"']([^\"']*)[\"']");

    /** ページを読みに行く 1 回分。テストはこれを差し替えてネットワークに出ない。 */
    @FunctionalInterface
    interface Fetcher {

        /**
         * @param url       読みに行くページの URL
         * @param timeoutMs この試行に掛けてよい上限 (ms)
         * @return HTML
         * @throws Exception 取得できなかった
         */
        String fetch(String url, int timeoutMs) throws Exception;
    }

    /** 試行の間の待ち。テストは待たずに記録だけする。 */
    @FunctionalInterface
    interface Sleeper {

        void sleep(long ms) throws InterruptedException;
    }

    private PageMeta() {
    }

    /**
     * ページを読み、{@code usable} が通る HTML が返るまで引き直す。
     *
     * @param url     読みに行くページの URL
     * @param label   ログに出す相手の名前 (「Spotify」等)
     * @param usable  その HTML から必要なものが読めたか。<b>読めない HTML も失敗として数える</b>
     *                のは、待っても同じ JS シェルが返るとは限らず、取り直す価値があるため
     * @param fetcher 1 回分の取得 (例外か {@code usable} の落ちる HTML で失敗を表す)
     * @param clockMs 経過を測る時計
     * @param sleeper 試行の間の待ち
     * @return 読めた HTML。上限まで試して読めなければ {@code null}
     */
    static String fetchHtml(String url, String label, Predicate<String> usable, Fetcher fetcher,
            LongSupplier clockMs, Sleeper sleeper) {
        final String target = url.trim();
        final long deadline = clockMs.getAsLong() + BUDGET_MS;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            final long remaining = deadline - clockMs.getAsLong();
            if (remaining <= 0L) {
                LOGGER.debug("Out of time for the {} page ({}), attempt {}/{}", label, url, attempt,
                        ATTEMPTS);
                break;
            }
            // 無応答のページを掴んでも全体の上限を超えないよう、1 回分の timeout を残りに縮める。
            final int timeoutMs = (int) Math.min(PAGE_TIMEOUT_MS, remaining);
            try {
                final String html = fetcher.fetch(target, timeoutMs);
                if (usable.test(html)) {
                    return html;
                }
                // 中身の無い HTML (JS シェル) も失敗として扱う。待っても同じものが返るとは
                // 限らないので、取り直す価値がある。
                LOGGER.debug("No metadata on the {} page ({}), attempt {}/{}", label, url, attempt,
                        ATTEMPTS);
            } catch (final Throwable t) {
                LOGGER.warn("Failed to fetch the {} page ({}) attempt {}/{}", label, url, attempt,
                        ATTEMPTS, t);
            }
            if (attempt == ATTEMPTS || clockMs.getAsLong() + RETRY_DELAY_MS >= deadline) {
                break;
            }
            try {
                sleeper.sleep(RETRY_DELAY_MS);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    /**
     * ページを 1 回読みに行く。
     *
     * <p>タイムアウト未設定だと相手が無応答の時に解決スレッド (全体で2本) が永久ブロックする。
     *
     * @param url       読みに行くページの URL
     * @param timeoutMs この試行に掛けてよい上限 (ms)
     * @return HTML
     * @throws Exception 取得できなかった
     */
    static String fetchPage(String url, int timeoutMs) throws Exception {
        final RequestConfig cfg = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .build();
        try (CloseableHttpClient http = HttpClients.custom().setDefaultRequestConfig(cfg).build()) {
            final HttpGet get = new HttpGet(url.trim());
            get.setHeader("User-Agent", UA);
            get.setHeader("Accept-Language", "en");
            return http.execute(get,
                    resp -> EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8));
        }
    }

    /** 照会 API の応答に許す大きさ。曲 1 件の JSON がこれを超えることは無い。 */
    static final int MAX_JSON_BYTES = 64 * 1024;
    /** 照会 API へ名乗る UA。ページ取得のソーシャルクローラー UA とは別物。 */
    private static final String API_UA = "MusicDiscMaker/3.1.3";

    /**
     * 照会 API (Apple Music / Deezer の JSON) を 1 回読む。
     *
     * <p>ページ取得 ({@link #fetchPage}) とは作法を分ける。ここは機械向けの API なので
     * <ul>
     *   <li>リダイレクトを追わない (別ホストの応答を曲の情報として読まない)</li>
     *   <li>{@code Accept: application/json} を名乗り、MOD 名の UA を使う</li>
     *   <li>本文は {@link #MAX_JSON_BYTES} で打ち切る (巨大な応答で解決スレッドを埋めない)</li>
     *   <li>2xx 以外は本文を読まずに失敗にする</li>
     * </ul>
     *
     * @param url       照会先
     * @param timeoutMs この試行に掛けてよい上限 (ms)
     * @return 応答本文 (UTF-8)
     * @throws Exception 取得できなかった・2xx でない・大きすぎる
     */
    static String fetchJson(String url, int timeoutMs) throws Exception {
        final RequestConfig cfg = RequestConfig.custom()
                .setConnectTimeout(timeoutMs)
                .setSocketTimeout(timeoutMs)
                .setConnectionRequestTimeout(timeoutMs)
                .build();
        try (CloseableHttpClient http = HttpClients.custom()
                .setDefaultRequestConfig(cfg)
                .disableRedirectHandling()
                .build()) {
            final HttpGet get = new HttpGet(url.trim());
            get.setHeader("User-Agent", API_UA);
            get.setHeader("Accept", "application/json");
            return http.execute(get, resp -> {
                final int status = resp.getStatusLine().getStatusCode();
                if (status < 200 || status >= 300) {
                    throw new IOException("HTTP " + status + " from " + url);
                }
                if (resp.getEntity() == null) {
                    throw new IOException("empty response from " + url);
                }
                try (InputStream in = resp.getEntity().getContent()) {
                    final byte[] bytes = in.readNBytes(MAX_JSON_BYTES + 1);
                    if (bytes.length > MAX_JSON_BYTES) {
                        throw new IOException("response from " + url + " is larger than "
                                + MAX_JSON_BYTES + " bytes");
                    }
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            });
        }
    }

    /** {@code <meta property="og:<prop>" content="...">} の content を取り出す。 */
    static String og(String html, String prop) {
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

    /** HTML の実体参照を戻す。曲名に {@code &amp;} が残ると検索語が別物になる。 */
    static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&#x27;", "'").replace("&apos;", "'").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&#x2F;", "/").replace("&#47;", "/");
    }
}
