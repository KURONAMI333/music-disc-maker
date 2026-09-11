package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.apache.http.HttpStatus;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;

/**
 * YouTube の oEmbed から曲名と投稿者だけを取る。<b>再生用の player API とは別のエンドポイント</b>
 * なので、player が bot 判定で拒まれても曲名は取れる (2026-08-18 実測: 認証なしで 200)。
 *
 * <p>用途は 1 つだけ — YouTube で鳴らせなかった時に、<b>別のソースを検索するための語</b>を得ること。
 *
 * <h2>200 が返っても「観られる動画」ではない</h2>
 * RIAA 削除済みの {@code Gc4HGQHgeFE} (再生は {@code This video requires login.} で失敗する) も
 * oEmbed は 200 でメタ情報を返した。<b>存在しない ID だけが 404 になる。</b>
 * したがって oEmbed の成否を「再生できるか」の判断に使ってはいけない。
 *
 * <p>同じ理由で、返ってくる文字列が曲名とは限らない (上の例のタイトルは
 * {@code "LOOK AT YOURSELF AFTER WATCHING THIS.mp4"})。曲名として使えるかの判断は
 * {@link TrackMatch} の照合に委ねる。
 */
final class YoutubeOEmbed {

    private static final Logger LOGGER = LoggerFactory.getLogger(YoutubeOEmbed.class);

    private static final String ENDPOINT = "https://www.youtube.com/oembed";
    /**
     * 問い合わせてよい URL の形。{@code MusicLoaderImpl#normalizeYoutubeUrl} が作る素の watch URL
     * だけを通す。<b>利用者が入れた文字列をそのままクエリに載せない</b>ため、ここで形を固定する。
     */
    private static final Pattern WATCH_URL =
            Pattern.compile("^https://www\\.youtube\\.com/watch\\?v=[A-Za-z0-9_-]{11}$");

    private YoutubeOEmbed() {
    }

    /**
     * 曲名と投稿者を取る。
     *
     * @param watchUrl 正規化済みの watch URL
     * @param timeoutMs 1 回の問い合わせに使ってよい時間 (接続・応答それぞれの上限)
     * @return {@code [title, author]}、取れなければ {@code null}
     */
    static String[] fetch(String watchUrl, long timeoutMs) {
        if (watchUrl == null || !WATCH_URL.matcher(watchUrl).matches()) {
            return null;
        }
        if (timeoutMs <= 0L) {
            return null;
        }
        final int timeout = (int) Math.min(timeoutMs, Integer.MAX_VALUE);
        final RequestConfig cfg = RequestConfig.custom()
                .setConnectTimeout(timeout)
                .setSocketTimeout(timeout)
                .setConnectionRequestTimeout(timeout)
                .build();
        try (CloseableHttpClient http = HttpClients.custom().setDefaultRequestConfig(cfg).build()) {
            final HttpGet get = new HttpGet(ENDPOINT + "?url=" + encode(watchUrl) + "&format=json");
            get.setHeader("Accept", "application/json");
            get.setHeader("Accept-Language", "en");
            final String body = http.execute(get, resp -> {
                final int status = resp.getStatusLine().getStatusCode();
                if (status != HttpStatus.SC_OK) {
                    // 存在しない動画は 404。それ以外の非 200 も曲名が取れないという意味では同じ。
                    LOGGER.debug("oEmbed returned status {} for {}", status, watchUrl);
                    return null;
                }
                return EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
            });
            if (body == null || body.isBlank()) {
                return null;
            }
            final JsonBrowser json = JsonBrowser.parse(body);
            final String title = json.get("title").text();
            if (title == null || title.isBlank()) {
                return null;
            }
            return new String[] {title.trim(), json.get("author_name").textOrDefault("").trim()};
        } catch (final Throwable t) {
            LOGGER.warn("Failed to read the YouTube oEmbed metadata ({})", watchUrl, t);
            return null;
        }
    }

    private static String encode(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }
}
