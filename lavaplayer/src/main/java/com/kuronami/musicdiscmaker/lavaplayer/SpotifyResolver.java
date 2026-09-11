package com.kuronami.musicdiscmaker.lavaplayer;

import java.nio.charset.StandardCharsets;
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
 */
final class SpotifyResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpotifyResolver.class);

    private static final Pattern TRACK = Pattern.compile(
            "(?i)^https?://open\\.spotify\\.com/(?:intl-[a-z]{2,}/)?track/[A-Za-z0-9]+.*");
    // og タグはソーシャルクローラー向けに SSR される。通常の Chrome UA だと JS シェルが返り og が無い。
    private static final String UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)";
    private static final Pattern CONTENT = Pattern.compile("(?i)\\bcontent\\s*=\\s*[\"']([^\"']*)[\"']");

    private SpotifyResolver() {
    }

    static boolean isSpotifyTrack(String url) {
        return url != null && TRACK.matcher(url.trim()).matches();
    }

    /** @return [曲名, アーティスト]、取得できなければ {@code null}。 */
    static String[] fetchMeta(String url) {
        // タイムアウト未設定だと Spotify 無応答時に解決スレッド (全体で2本) が永久ブロックする。
        final RequestConfig cfg = RequestConfig.custom()
                .setConnectTimeout(10_000)
                .setSocketTimeout(10_000)
                .setConnectionRequestTimeout(10_000)
                .build();
        try (CloseableHttpClient http = HttpClients.custom().setDefaultRequestConfig(cfg).build()) {
            final HttpGet get = new HttpGet(url.trim());
            get.setHeader("User-Agent", UA);
            get.setHeader("Accept-Language", "en");
            final String html = http.execute(get,
                    resp -> EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8));

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
        } catch (final Throwable t) {
            LOGGER.warn("Failed to fetch the Spotify page ({})", url, t);
            return null;
        }
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
