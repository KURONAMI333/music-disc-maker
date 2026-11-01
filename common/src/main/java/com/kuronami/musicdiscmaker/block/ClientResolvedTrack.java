package com.kuronami.musicdiscmaker.block;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;

/**
 * 製作機で server が URL を解決できなかった時、client が取った曲情報を受け取ってよいかの判定 (純粋関数)。
 *
 * <p>なぜ client に聞くか: 専用サーバーの回線が YouTube に bot 判定されても、プレイヤーの
 * 自宅回線では同じ動画の曲名と尺が取れることがある。盤の尺は server が焼くので、ここで尺が
 * 渡らないと長さ 0 (= ラジオ扱い) の盤しか作れない。
 *
 * <p>client の値は信用しない。<b>元の URL と同じ動画を指していること</b>、<b>YouTube であること</b>、
 * <b>尺が実在すること</b>を確かめ、焼く URL は client の値ではなく server が持つ元の URL にする。
 * これで「client が別の動画の尺を送って、元の URL に貼る」経路を塞ぐ。
 */
public final class ClientResolvedTrack {

    /** YouTube の動画 ID。ジャケット導出 ({@code JacketUrls}) と同じ形に加え、ライブと旧形式も拾う。 */
    private static final Pattern VIDEO_ID =
            Pattern.compile("(?i)(?:youtu\\.be/|/shorts/|/embed/|/live/|/v/|[?&]v=)([A-Za-z0-9_-]{11})");

    private ClientResolvedTrack() {
    }

    /**
     * client に解決を頼む失敗か。YouTube 以外は {@link #accept} が必ず落とすので、取りに行かせない
     * (無駄な取得を増やさない)。
     *
     * @param reason server が記録した失敗理由
     * @param url    server が解決しようとした URL
     */
    public static boolean shouldAskClient(FailureReason reason, String url) {
        if (!isYoutube(url)) {
            return false;
        }
        // BOT_CHECK: 回線の都合なので client なら通る見込みがある。
        // NO_ALTERNATIVE_FOUND: 代替も見つからなかったが、元の動画の尺は client なら取れる。
        // それ以外 (非公開・地域制限・年齢制限・DRM 等) は、client が取れても盤にしてよい理由にならない。
        return reason == FailureReason.BOT_CHECK || reason == FailureReason.NO_ALTERNATIVE_FOUND;
    }

    /**
     * client の値を焼いてよい形に直す。1 つでも検められなければ {@code null} (盤は作らない)。
     *
     * <p>検めること: (a) client の URL が元の URL と同じ動画、(b) YouTube、(c) 尺が 1 秒以上で
     * ラジオ判定の境目 ({@link DiscFabrication#RADIO_DURATION_THRESHOLD_MS}) 以下、(d) 曲名が空でない。
     * 曲名・アーティストの長さとサロゲートの扱いは {@link CustomTrackData} の constructor に任せる
     * (焼く時と同じ規則を二重に書かない)。
     *
     * @param requestedUrl server が持つ元の URL。焼くのは常にこれ
     * @param clientUrl    client が解決した住所
     * @param title        client が取った表示用の曲名
     * @param author       client が取った表示用のアーティスト
     * @param durationMs   client が取った尺
     * @return 焼く形。検められなければ {@code null}
     */
    public static CustomTrackData accept(String requestedUrl, String clientUrl, String title, String author,
            long durationMs) {
        if (!isYoutube(requestedUrl)) {
            return null;
        }
        if (!normalize(requestedUrl).equals(normalize(clientUrl))) {
            return null;
        }
        // 0 以下 (尺不明・ライブ) はラジオ扱いになって曲送り・シークが止まるので焼かない。
        // 上限は「これを超えたらラジオ」と同じ境目を使い、判定の 2 箇所がずれないようにする。
        if (durationMs <= 0L || durationMs > DiscFabrication.RADIO_DURATION_THRESHOLD_MS) {
            return null;
        }
        final String cleanTitle = title == null ? "" : title.strip();
        if (cleanTitle.isEmpty()) {
            // 曲名の無い盤は画面でもツールチップでも見分けが付かない。壊れた値として扱う。
            return null;
        }
        final String cleanAuthor = author == null ? "" : author.strip();
        // サムネは載せない: 他人の client が取得する URL を client の言い値で決めさせない。
        // 空なら JacketUrls が動画 ID から YouTube の画像を導出する。
        return new CustomTrackData(requestedUrl.strip(), cleanTitle, cleanAuthor, durationMs, "", false);
    }

    /** 同じ動画を指すかを見るための正規化。YouTube は動画 ID、それ以外は前後の空白を落とした文字列。 */
    static String normalize(String url) {
        if (url == null) {
            return "";
        }
        final String trimmed = url.strip();
        final String id = videoId(trimmed);
        return id == null ? trimmed : "youtube:" + id;
    }

    /** URL が YouTube か (DNS は引かない)。 */
    static boolean isYoutube(String url) {
        final String host = host(url);
        return host.equals("youtube.com") || host.equals("youtu.be") || host.endsWith(".youtube.com");
    }

    private static String videoId(String url) {
        final Matcher matcher = VIDEO_ID.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * URL のホスト名。scheme が無ければ https とみなす ({@code UrlGuard} と同じ扱い)。
     * 解析できない時は空 (fail-closed)。
     */
    private static String host(String url) {
        if (url == null) {
            return "";
        }
        final String trimmed = url.strip();
        if (trimmed.isEmpty()) {
            return "";
        }
        final String withScheme = trimmed.contains("://") ? trimmed : "https://" + trimmed;
        try {
            final String host = java.net.URI.create(withScheme).getHost();
            if (host == null) {
                return "";
            }
            final String lower = host.toLowerCase(Locale.ROOT);
            return lower.startsWith("www.") ? lower.substring(4) : lower;
        } catch (final IllegalArgumentException ex) {
            return "";
        }
    }
}
