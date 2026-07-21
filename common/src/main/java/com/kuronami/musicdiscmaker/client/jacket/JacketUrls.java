package com.kuronami.musicdiscmaker.client.jacket;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.kuronami.musicdiscmaker.component.CustomTrackData;

/** ジャケット画像 URL の解決。保存済み thumbnailUrl を優先し、無ければ YouTube ID から導出する。 */
public final class JacketUrls {

    // 再生 URL から 11 桁 video ID を拾う (YouTube の場合のみ)。
    private static final Pattern YT_VIDEO_ID =
            Pattern.compile("(?i)(?:youtu\\.be/|/shorts/|/embed/|[?&]v=)([A-Za-z0-9_-]{11})");

    private JacketUrls() {
    }

    /** ジャケットに使う画像 URL。無ければ空文字。 */
    public static String effectiveUrl(CustomTrackData track) {
        if (track == null || track.isEmpty()) {
            return "";
        }
        final String stored = track.thumbnailUrl();
        if (stored != null && !stored.isBlank()) {
            return stored;
        }
        // thumbnailUrl 未保存の旧ディスク向けフォールバック: YouTube の再生 URL からジャケットを導出。
        final String ytId = youtubeId(track.url());
        if (ytId != null) {
            return "https://i.ytimg.com/vi/" + ytId + "/hqdefault.jpg";
        }
        return "";
    }

    private static String youtubeId(String url) {
        if (url == null) {
            return null;
        }
        final Matcher m = YT_VIDEO_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }
}
