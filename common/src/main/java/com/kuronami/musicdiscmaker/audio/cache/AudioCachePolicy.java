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
 *   <li><b>尺が不明 (0 以下)</b> — 完走の判定基準が無い。</li>
 * </ul>
 *
 * <p>Spotify のディスクは解決の時点で YouTube の uri が焼かれている ({@code TrackInfo.uri}) ので、
 * ここでは YouTube として扱われる = キャッシュ対象。裁定どおり。
 */
public final class AudioCachePolicy {

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
        if (url == null || url.isBlank() || radio || durationMs <= 0L) {
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
