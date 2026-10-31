package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.List;
import java.util.Locale;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureClassifier;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudFormatHandler;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudFormatHandler;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudM3uInfo;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudTrackFormat;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;

/**
 * AAC (fMP4) の HLS を受け付ける SoundCloud の format handler。
 *
 * <h2>なぜ要るか</h2>
 * 同梱の LavaPlayer 2.2.4-fix-j8 は {@code hls+audio/ogg}・{@code hls+audio/mpeg}・
 * {@code progressive+audio/mpeg} の 3 種だけを {@code equals} で受け付ける。SoundCloud は
 * 2026-11 時点で {@code hls + audio/mp4; codecs="mp4a.40.2"} (= AAC の fMP4) しか
 * まともに配信していないため、素の handler では曲の JSON は取れても
 * {@code RuntimeException: Did not detect any supported formats} で全件落ちる。
 *
 * <h2>素の handler に足すだけにしてある理由</h2>
 * 既存 3 種の優先順位は<b>素の handler のまま</b>使う ({@link #chooseBestFormat} が先に委譲する)。
 * ここで自前の一覧を作ると、SoundCloud が ogg を返す日に優先順位が黙って変わる。AAC は
 * <b>他が 1 つも無い時だけ</b>採る。
 *
 * <p>識別子の接頭辞 {@code A:} は素の handler が使っていないもの (素は {@code O:}・{@code U:}・
 * {@code M:}・{@code X:})。{@link #getM3uInfo} がこれを見て AAC の decoder を返す。
 * 実際の再生は {@link SoundCloudAacHlsAudioTrack} が担う (初期化セグメントの差し込みと、
 * client_id を付けない CDN 取得が要るため、素の m3u track では鳴らない)。
 */
final class SoundCloudAacFormatHandler implements SoundCloudFormatHandler {

    /** AAC (fMP4) の HLS に付ける識別子の接頭辞。 */
    static final String AAC_PREFIX = "A:";

    private static final String PROTOCOL_HLS = "hls";
    private static final String MIME_MP4 = "audio/mp4";

    private final DefaultSoundCloudFormatHandler delegate = new DefaultSoundCloudFormatHandler();

    @Override
    public SoundCloudTrackFormat chooseBestFormat(List<SoundCloudTrackFormat> formats) {
        try {
            return delegate.chooseBestFormat(formats);
        } catch (RuntimeException unsupported) {
            final SoundCloudTrackFormat aac = findHlsMp4(formats);
            if (aac != null) {
                return aac;
            }
            if (!formats.isEmpty() && formats.stream().allMatch(SoundCloudAacFormatHandler::isEncrypted)) {
                // 素の文言 ("Did not detect any supported formats") は利用者にもログを読む側にも
                // 「ライブラリが古い」としか伝わらない。DRM は URL を替えても直らないので、
                // そう言い切れる時だけ専用の目印を付ける。
                throw new FriendlyException(FailureClassifier.DRM_MARKER,
                        FriendlyException.Severity.COMMON, null);
            }
            throw unsupported;
        }
    }

    @Override
    public String buildFormatIdentifier(SoundCloudTrackFormat format) {
        return isHlsMp4(format)
                ? AAC_PREFIX + format.getLookupUrl()
                : delegate.buildFormatIdentifier(format);
    }

    @Override
    public SoundCloudM3uInfo getM3uInfo(String identifier) {
        if (identifier != null && identifier.startsWith(AAC_PREFIX)) {
            return new SoundCloudM3uInfo(identifier.substring(AAC_PREFIX.length()),
                    SoundCloudAacSegmentDecoder::new);
        }
        return delegate.getM3uInfo(identifier);
    }

    @Override
    public String getMp3LookupUrl(String identifier) {
        return delegate.getMp3LookupUrl(identifier);
    }

    /**
     * AAC の HLS か。
     *
     * <p>{@code mime} は {@code audio/mp4; codecs="mp4a.40.2"} のようにパラメータ付きで返る。
     * {@code equals} で比べると必ず外れるので前方一致で見る。
     *
     * @param format 判定する形式 ({@code null} 可)
     * @return AAC の HLS なら {@code true}
     */
    static boolean isHlsMp4(SoundCloudTrackFormat format) {
        if (format == null) {
            return false;
        }
        final String mimeType = format.getMimeType();
        return PROTOCOL_HLS.equals(format.getProtocol()) && mimeType != null
                && (mimeType.equals(MIME_MP4) || mimeType.startsWith(MIME_MP4 + ";"));
    }

    private static SoundCloudTrackFormat findHlsMp4(List<SoundCloudTrackFormat> formats) {
        for (final SoundCloudTrackFormat format : formats) {
            if (isHlsMp4(format)) {
                return format;
            }
        }
        return null;
    }

    /**
     * DRM で保護された形式か。SoundCloud は保護された曲の transcoding を
     * {@code encrypted-hls} のような protocol 名で返す。
     *
     * @param format 判定する形式 ({@code null} 可)
     * @return 保護されていれば {@code true}
     */
    private static boolean isEncrypted(SoundCloudTrackFormat format) {
        if (format == null) {
            return false;
        }
        final String protocol = format.getProtocol();
        return protocol != null && protocol.toLowerCase(Locale.ROOT).contains("encrypted");
    }
}
