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

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;

/**
 * YouTube の watch ページから元動画の尺と配信曲メタ情報を取る。
 *
 * <p>本文は最大 {@link #MAX_SCAN_CHARS} 文字、通信は呼び出し側の締切までで打ち切る。
 * URLは正規化済みwatch URLだけを受け付け、redirectは追わず、本文のvideoIdも入力と一致した時だけ
 * 採用する。取れない場合は空の結果へ落とし、元の解決を失敗させない。
 */
final class YoutubeWatchPage {

    private static final Logger LOGGER = LoggerFactory.getLogger(YoutubeWatchPage.class);

    private static final Pattern WATCH_URL = Pattern.compile(
            "^https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})$");
    private static final String VIDEO_DETAILS_MARKER = "\"videoDetails\":";
    private static final Pattern ISO_DURATION = Pattern.compile(
            "(?i)itemprop=\"duration\"\\s+content=\"PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?\"");

    static final int MAX_SCAN_CHARS = 2_000_000;
    private static final int CHUNK_CHARS = 32_768;

    private YoutubeWatchPage() {
    }

    /**
     * 元動画の情報を1回のwatch取得で読む。
     *
     * @param watchUrl 正規化済みwatch URL
     * @param timeoutMs 接続、読み取り、全体に使ってよい時間
     * @return 取得結果。失敗時は {@link Result#EMPTY}
     */
    static Result fetch(String watchUrl, long timeoutMs) {
        final Matcher url = watchUrl == null ? null : WATCH_URL.matcher(watchUrl);
        if (url == null || !url.matches() || timeoutMs <= 0L) {
            return Result.EMPTY;
        }
        final String expectedVideoId = url.group(1);
        final int timeout = (int) Math.min(timeoutMs, Integer.MAX_VALUE);
        final RequestConfig cfg = RequestConfig.custom()
                .setConnectTimeout(timeout)
                .setSocketTimeout(timeout)
                .setConnectionRequestTimeout(timeout)
                .setRedirectsEnabled(false)
                .build();
        final long deadline = System.currentTimeMillis() + timeoutMs;
        try (CloseableHttpClient http = HttpClients.custom()
                .disableRedirectHandling()
                .setDefaultRequestConfig(cfg)
                .build()) {
            final HttpGet get = new HttpGet(watchUrl);
            get.setHeader("Accept-Language", "en");
            final Result found = http.execute(get, response -> {
                if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                    return Result.EMPTY;
                }
                return scan(new InputStreamReader(response.getEntity().getContent(),
                        StandardCharsets.UTF_8), expectedVideoId, deadline);
            });
            if (found == null || found.equals(Result.EMPTY)) {
                LOGGER.debug("No matching video details on the watch page ({})", watchUrl);
                return Result.EMPTY;
            }
            return found;
        } catch (final Throwable thrown) {
            LOGGER.debug("Failed to read the watch page ({})", watchUrl, thrown);
            return Result.EMPTY;
        }
    }

    /** 従来の尺だけを使う呼び出し口。 */
    static long durationMs(String watchUrl, long timeoutMs) {
        return fetch(watchUrl, timeoutMs).durationMs();
    }

    /** テスト用を兼ねた本文parser。通信を行わない。 */
    static Result scan(Reader reader, String expectedVideoId, long deadline)
            throws java.io.IOException {
        if (reader == null || expectedVideoId == null
                || !expectedVideoId.matches("[A-Za-z0-9_-]{11}")) {
            return Result.EMPTY;
        }
        final StringBuilder page = new StringBuilder();
        final char[] buffer = new char[CHUNK_CHARS];
        while (page.length() < MAX_SCAN_CHARS && System.currentTimeMillis() <= deadline) {
            final int maximum = Math.min(buffer.length, MAX_SCAN_CHARS - page.length());
            final int read = reader.read(buffer, 0, maximum);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                continue;
            }
            page.append(buffer, 0, read);
            final Result result = videoDetails(page, expectedVideoId);
            if (result != null) {
                return result;
            }
        }
        if (System.currentTimeMillis() > deadline) {
            LOGGER.debug("Gave up reading the watch page after {} chars", page.length());
        }
        final Matcher iso = ISO_DURATION.matcher(page);
        if (iso.find()) {
            final long total = group(iso, 1) * 3600L + group(iso, 2) * 60L + group(iso, 3);
            return total > 0L ? new Result(total * 1000L, "", "", "") : Result.EMPTY;
        }
        return Result.EMPTY;
    }

    /** videoDetailsが未完ならnull、完成して不採用ならEMPTYを返す。 */
    private static Result videoDetails(StringBuilder page, String expectedVideoId) {
        final int marker = page.indexOf(VIDEO_DETAILS_MARKER);
        if (marker < 0) {
            return null;
        }
        final int start = page.indexOf("{", marker + VIDEO_DETAILS_MARKER.length());
        if (start < 0) {
            return null;
        }
        final int end = jsonObjectEnd(page, start);
        if (end < 0) {
            return null;
        }
        try {
            final JsonBrowser details = JsonBrowser.parse(page.substring(start, end + 1));
            final String videoId = details.get("videoId").textOrDefault("");
            if (!expectedVideoId.equals(videoId)) {
                return Result.EMPTY;
            }
            final long seconds = parsePositiveLong(details.get("lengthSeconds").textOrDefault(""));
            final String title = bounded(details.get("title").textOrDefault(""),
                    YoutubeMusicMetadata.MAX_TITLE_LENGTH);
            final String author = bounded(details.get("author").textOrDefault(""),
                    YoutubeMusicMetadata.MAX_AUTHOR_LENGTH);
            final String description = bounded(details.get("shortDescription").textOrDefault(""),
                    YoutubeMusicMetadata.MAX_DESCRIPTION_LENGTH);
            return new Result(seconds * 1000L, title, author, description);
        } catch (final Throwable ignored) {
            return Result.EMPTY;
        }
    }

    /** JSON文字列内のbraceを数えない、最初のobjectの終端検出。 */
    private static int jsonObjectEnd(CharSequence text, int start) {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            final char current = text.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    quoted = false;
                }
                continue;
            }
            if (current == '"') {
                quoted = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static String bounded(String value, int maximumLength) {
        return value == null || value.length() > maximumLength ? "" : value.trim();
    }

    private static long parsePositiveLong(String value) {
        try {
            final long parsed = Long.parseLong(value);
            return parsed > 0L && parsed <= Long.MAX_VALUE / 1000L ? parsed : 0L;
        } catch (final NumberFormatException ignored) {
            return 0L;
        }
    }

    private static long group(Matcher matcher, int index) {
        final String value = matcher.group(index);
        return value == null ? 0L : Long.parseLong(value);
    }

    record Result(long durationMs, String title, String author, String shortDescription) {
        static final Result EMPTY = new Result(0L, "", "", "");

        Result {
            title = title == null ? "" : title;
            author = author == null ? "" : author;
            shortDescription = shortDescription == null ? "" : shortDescription;
        }
    }
}
