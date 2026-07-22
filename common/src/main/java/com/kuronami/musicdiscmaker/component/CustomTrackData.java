package com.kuronami.musicdiscmaker.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * custom music disc に付与する DataComponent のペイロード (MC 1.21 で NBT は廃止)。
 * URL / 曲名 / アーティスト / 長さ / サムネ URL / ラジオ判定を保持する。
 *
 * <p>{@code radio}: 無限長ストリーム (icecast/HTTP ラジオ・ライブ配信) を表す。radio 時は
 * {@code durationMs} を 0 sentinel で保存し (ActiveDiscRegistry の prune 回避と整合)、UI は
 * 尺の代わりに「LIVE」を出す。「長さ不明」を duration に多重化せず boolean で明示する。
 */
public record CustomTrackData(
        String url,
        String title,
        String author,
        long durationMs,
        String thumbnailUrl,
        boolean radio) {

    // 各文字列の上限。url/サムネは長めの実 URL を許容、曲名/アーティストは表示用に短く。
    // 上限なしだと細工された巨大メタデータで writeUtf (32767 byte 上限) が例外→同期で接続断。
    private static final int MAX_URL = 2048;
    private static final int MAX_TEXT = 256;

    public CustomTrackData {
        url = clamp(url, MAX_URL);
        title = clamp(title, MAX_TEXT);
        author = clamp(author, MAX_TEXT);
        thumbnailUrl = clamp(thumbnailUrl, MAX_URL);
    }

    private static String clamp(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static final CustomTrackData EMPTY = new CustomTrackData("", "", "", 0L, "", false);

    // radio は optionalFieldOf: 既存ディスク (radio フィールドなし) を壊さず false 既定でデコードする。
    public static final Codec<CustomTrackData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("url").forGetter(CustomTrackData::url),
            Codec.STRING.fieldOf("title").forGetter(CustomTrackData::title),
            Codec.STRING.fieldOf("author").forGetter(CustomTrackData::author),
            Codec.LONG.fieldOf("duration_ms").forGetter(CustomTrackData::durationMs),
            Codec.STRING.fieldOf("thumbnail_url").forGetter(CustomTrackData::thumbnailUrl),
            Codec.BOOL.optionalFieldOf("radio", false).forGetter(CustomTrackData::radio)
    ).apply(instance, CustomTrackData::new));

    public static final StreamCodec<ByteBuf, CustomTrackData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CustomTrackData::url,
            ByteBufCodecs.STRING_UTF8, CustomTrackData::title,
            ByteBufCodecs.STRING_UTF8, CustomTrackData::author,
            ByteBufCodecs.VAR_LONG, CustomTrackData::durationMs,
            ByteBufCodecs.STRING_UTF8, CustomTrackData::thumbnailUrl,
            ByteBufCodecs.BOOL, CustomTrackData::radio,
            CustomTrackData::new);

    public boolean isEmpty() {
        return url == null || url.isBlank();
    }

    /** 表示用 "M:SS"。 */
    public String formattedDuration() {
        final long totalSeconds = durationMs / 1000L;
        return String.format("%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }
}
