package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * YouTube の watch ページから<b>元の尺だけ</b>を取る。
 *
 * <p>再生用の player API が拒んでいても watch ページは 200 で返る (2026-08-18 実測: 年齢制限で
 * {@code This video requires login.} を返す {@code SkTt9k4Y-a8} でも {@code "lengthSeconds":"439"}
 * が読めた)。代替ソースの候補が<b>短縮版や抜粋でないか</b>を確かめるのにこれを使う。
 *
 * <p>取れなくても機能は止めない。bot 判定でここが弾かれる環境では尺は永久に取れないので、
 * <b>取れないことを理由に鳴らさない側へ倒すと機能が丸ごと死ぬ</b>。呼び出し側は
 * {@code 0} を「尺は分からない」として扱い、従来どおりの選び方に落ちる。
 *
 * <h2>1.2MB を全部読まない</h2>
 * watch ページは 1MB を超える。尺は先頭から約 700KB の位置に現れるので、
 * <b>見つけた時点で読むのをやめる</b>。それでも見つからなければ {@link #MAX_SCAN_CHARS} で打ち切る。
 */
final class YoutubeWatchPage {

    private static final Logger LOGGER = LoggerFactory.getLogger(YoutubeWatchPage.class);

    /**
     * 問い合わせてよい URL の形。{@code MusicLoaderImpl#normalizeYoutubeUrl} が作る素の watch URL
     * だけを通す ({@link YoutubeOEmbed} と同じ規律 — 利用者が入れた文字列をそのまま載せない)。
     */
    private static final Pattern WATCH_URL =
            Pattern.compile("^https://www\\.youtube\\.com/watch\\?v=[A-Za-z0-9_-]{11}$");

    /** {@code videoDetails} の尺 (秒)。 */
    private static final String SECONDS_MARKER = "\"lengthSeconds\":\"";
    /** 見つからなかった時の予備。{@code <meta itemprop="duration" content="PT7M19S">}。 */
    private static final Pattern ISO_DURATION = Pattern.compile(
            "(?i)itemprop=\"duration\"\\s+content=\"PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?\"");

    /** ここまで読んで見つからなければ諦める (ページ全体より少し大きい程度)。 */
    private static final int MAX_SCAN_CHARS = 2_000_000;
    private static final int CHUNK_CHARS = 32_768;
    /** 走査済みの位置を戻す幅 (チャンクの切れ目でマーカーが割れるのを拾うため)。 */
    private static final int SCAN_OVERLAP = 64;

    private YoutubeWatchPage() {
    }

    /**
     * 元の尺を取る。
     *
     * @param watchUrl  正規化済みの watch URL
     * @param timeoutMs 待ち上限
     * @return 尺 (ms)。取れなければ {@code 0}
     */
    static long durationMs(String watchUrl, long timeoutMs) {
        if (watchUrl == null || !WATCH_URL.matcher(watchUrl).matches() || timeoutMs <= 0L) {
            return 0L;
        }
        final int timeout = (int) Math.min(timeoutMs, Integer.MAX_VALUE);
        final RequestConfig cfg = RequestConfig.custom()
                .setConnectTimeout(timeout)
                .setSocketTimeout(timeout)
                .setConnectionRequestTimeout(timeout)
                .build();
        final long deadline = System.currentTimeMillis() + timeoutMs;
        try (CloseableHttpClient http = HttpClients.custom().setDefaultRequestConfig(cfg).build()) {
            final HttpGet get = new HttpGet(watchUrl);
            get.setHeader("Accept-Language", "en");
            final Long found = http.execute(get, resp -> {
                if (resp.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                    return null;
                }
                return scan(new InputStreamReader(resp.getEntity().getContent(),
                        StandardCharsets.UTF_8), deadline);
            });
            if (found == null || found <= 0L) {
                LOGGER.debug("No duration on the watch page ({})", watchUrl);
                return 0L;
            }
            return found;
        } catch (final Throwable t) {
            LOGGER.debug("Failed to read the watch page ({})", watchUrl, t);
            return 0L;
        }
    }

    /**
     * 尺が現れるまで読み進める。
     *
     * <p>締切を毎チャンク見るのは、<b>socket のタイムアウトが総転送時間を縛らない</b>ため
     * (あれは 1 回の読み取りが止まった時間の上限)。1MB 超のページを細く長く受け取り続けると、
     * どの読み取りも止まらないまま予算を食い潰せる。解決プールは 2 本しかないのでそこを縛る。
     *
     * @param reader   ページ本文
     * @param deadline これを過ぎたら読むのをやめる時刻 (壁時計 ms)
     * @return 尺 (ms)。見つからなければ {@code null}
     */
    private static Long scan(Reader reader, long deadline) throws java.io.IOException {
        final StringBuilder page = new StringBuilder();
        final char[] buffer = new char[CHUNK_CHARS];
        int scannedTo = 0;
        int read;
        while (page.length() < MAX_SCAN_CHARS && (read = reader.read(buffer)) > 0) {
            page.append(buffer, 0, read);
            final Long seconds = seconds(page, Math.max(0, scannedTo - SCAN_OVERLAP));
            if (seconds != null) {
                return seconds * 1000L;
            }
            scannedTo = page.length();
            if (System.currentTimeMillis() > deadline) {
                LOGGER.debug("Gave up reading the watch page after {} chars", page.length());
                break;
            }
        }
        final Matcher iso = ISO_DURATION.matcher(page);
        if (iso.find()) {
            final long total = group(iso, 1) * 3600L + group(iso, 2) * 60L + group(iso, 3);
            return total > 0L ? total * 1000L : null;
        }
        return null;
    }

    /** {@code "lengthSeconds":"439"} を {@code from} 以降から探す (閉じ引用符まで揃っている時だけ採る)。 */
    private static Long seconds(StringBuilder page, int from) {
        int at = page.indexOf(SECONDS_MARKER, from);
        while (at >= 0) {
            final int start = at + SECONDS_MARKER.length();
            final int end = page.indexOf("\"", start);
            if (end < 0) {
                return null; // まだ値の途中。次のチャンクで読み直す
            }
            final String value = page.substring(start, end);
            if (!value.isEmpty() && value.chars().allMatch(Character::isDigit)) {
                try {
                    final long parsed = Long.parseLong(value);
                    if (parsed > 0L) {
                        return parsed;
                    }
                } catch (final NumberFormatException ignored) {
                    // 桁があふれるほどの値は尺ではない
                }
            }
            at = page.indexOf(SECONDS_MARKER, at + SECONDS_MARKER.length());
        }
        return null;
    }

    private static long group(Matcher matcher, int index) {
        final String value = matcher.group(index);
        return value == null ? 0L : Long.parseLong(value);
    }
}
