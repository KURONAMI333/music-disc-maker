package com.kuronami.musicdiscmaker.audio.cache;

import java.net.URI;
import java.util.Locale;

import com.kuronami.musicdiscmaker.component.CustomTrackData;

/**
 * どの曲をキャッシュしてよいかの判定。MC の API を参照しないので headless テストで固定できる。
 *
 * <h2>対象外にするもの</h2>
 * <ul>
 *   <li><b>SoundCloud</b> — 利用規約が永続キャッシュを名指しで禁じている
 *       ("Your app must not include file-save functionality, or otherwise designed to cache,
 *       download or persistently store any User Content")。kura 裁定でここだけ除外。
 *       他のソース (YouTube / Bandcamp / Vimeo / HTTP 直リンク) は対象。</li>
 *   <li><b>ラジオ / ライブ</b> — 終端が無いので「完走」の判定ができない。Twitch もここに落ちる。</li>
 *   <li><b>尺が範囲外</b> — 完走の判定基準が「申告尺の 98%」なので、尺そのものが信用できないと
 *       判定が丸ごと無効になる。{@code durationMs} は disc の DataComponent 由来
 *       = <b>攻撃者が制御しうる値</b>で、server が再導出していない。極端に短い値
 *       (例 1ms) を入れると必要バイト数が 0 になり、<b>バッファ枯渇で打ち切られた音声が
 *       「完走」として恒久的に焼かれる</b>。極端に長い値は掛け算が桁あふれして同じことが起きる。
 *       正規の作成経路 ({@code DiscFabrication}) が通す範囲だけを受ける。</li>
 * </ul>
 *
 * <p>Spotify のディスクは解決の時点で YouTube の uri が焼かれている ({@code TrackInfo.uri}) ので、
 * ここでは YouTube として扱われる = キャッシュ対象。裁定どおり。
 */
public final class AudioCachePolicy {

    /** これより短い曲はキャッシュしない (98% 判定が意味を持つ最低限)。 */
    private static final long MIN_DURATION_MS = 5_000L;
    /** これより長い尺は無限長ストリームとみなす ({@code DiscFabrication} の判定と同じ 12 時間)。 */
    private static final long MAX_DURATION_MS = 43_200_000L;

    private AudioCachePolicy() {
    }

    /** キャッシュしてよい曲か。 */
    public static boolean cacheable(CustomTrackData track) {
        if (track == null || track.isEmpty()) {
            return false;
        }
        return cacheable(track.url(), track.durationMs(), track.radio());
    }

    public static boolean cacheable(String url, long durationMs, boolean radio) {
        if (url == null || url.isBlank() || radio) {
            return false;
        }
        if (durationMs < MIN_DURATION_MS || durationMs > MAX_DURATION_MS) {
            return false;
        }
        return !isSoundCloud(url);
    }

    /**
     * SoundCloud 由来の URL か。ホスト名で見る (permalink は {@code soundcloud.com}、
     * ストリーム実体は {@code sndcdn.com})。URL として解釈できないものは安全側で SoundCloud 扱いにしない
     * — 判定できない = HTTP 直リンク等なので、除外理由が無い。
     */
    public static boolean isSoundCloud(String url) {
        final String host = hostOf(url);
        if (host == null) {
            return false;
        }
        return matchesDomain(host, "soundcloud.com") || matchesDomain(host, "sndcdn.com");
    }

    private static boolean matchesDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    private static String hostOf(String url) {
        try {
            final String host = URI.create(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (final RuntimeException ex) {
            return null;
        }
    }
}
