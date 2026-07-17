package com.kuronami.musicdiscmaker.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * custom music disc に付与する DataComponent のペイロード (MC 1.21 で NBT は廃止)。
 * URL / 曲名 / アーティスト / 長さ / サムネ URL を保持する。
 */
public record CustomTrackData(
        String url,
        String title,
        String author,
        long durationMs,
        String thumbnailUrl) {

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

    public static final CustomTrackData EMPTY = new CustomTrackData("", "", "", 0L, "");

    public static final Codec<CustomTrackData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("url").forGetter(CustomTrackData::url),
            Codec.STRING.fieldOf("title").forGetter(CustomTrackData::title),
            Codec.STRING.fieldOf("author").forGetter(CustomTrackData::author),
            Codec.LONG.fieldOf("duration_ms").forGetter(CustomTrackData::durationMs),
            Codec.STRING.fieldOf("thumbnail_url").forGetter(CustomTrackData::thumbnailUrl)
    ).apply(instance, CustomTrackData::new));

    public static final StreamCodec<ByteBuf, CustomTrackData> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CustomTrackData::url,
            ByteBufCodecs.STRING_UTF8, CustomTrackData::title,
            ByteBufCodecs.STRING_UTF8, CustomTrackData::author,
            ByteBufCodecs.VAR_LONG, CustomTrackData::durationMs,
            ByteBufCodecs.STRING_UTF8, CustomTrackData::thumbnailUrl,
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
