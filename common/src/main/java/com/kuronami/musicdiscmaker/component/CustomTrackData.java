package com.kuronami.musicdiscmaker.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * custom music disc に付与する曲メタのペイロード (1.20.1 は DataComponent 非対応なので item / BE は NBT で保持)。
 * URL / 曲名 / アーティスト / 長さ / サムネ URL / ラジオ判定を保持する。
 *
 * <p>{@code radio}: 無限長ストリーム (icecast/HTTP ラジオ・ライブ配信) を表す。radio 時は
 * {@code durationMs} を 0 sentinel で保存し、UI は尺の代わりに「LIVE」を出す。
 * 「長さ不明」を duration に多重化せず boolean で明示する。全ディスクは固定 7200s の
 * {@code RecordItem} なので、radio は「LIVE 表示 + 瞬断時の自動再接続」を有効化するフラグとして働く。
 *
 * <p>シリアライズ経路:
 * <ul>
 *   <li>{@link #CODEC} — BlockEntity の NBT 永続化 (NbtOps 経由)。radio は optionalFieldOf で既存ディスク互換。</li>
 *   <li>{@link #toNbt()} / {@link #fromNbt(CompoundTag)} — item stack の NBT 保存 (旧ディスクは getBoolean=false)。</li>
 *   <li>{@link #write(FriendlyByteBuf)} / {@link #read(FriendlyByteBuf)} — payload 同期。</li>
 * </ul>
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

    public boolean isEmpty() {
        return url == null || url.isBlank();
    }

    /** 表示用 "M:SS"。 */
    public String formattedDuration() {
        final long totalSeconds = durationMs / 1000L;
        return String.format("%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    // ── NBT (item stack 保存用) ──

    public CompoundTag toNbt() {
        final CompoundTag tag = new CompoundTag();
        tag.putString("url", url);
        tag.putString("title", title);
        tag.putString("author", author);
        tag.putLong("duration_ms", durationMs);
        tag.putString("thumbnail_url", thumbnailUrl);
        tag.putBoolean("radio", radio);
        return tag;
    }

    public static CustomTrackData fromNbt(CompoundTag tag) {
        if (tag == null) {
            return EMPTY;
        }
        // 旧ディスク (radio キー無し) は getBoolean が false を返す = 後方互換。
        return new CustomTrackData(
                tag.getString("url"),
                tag.getString("title"),
                tag.getString("author"),
                tag.getLong("duration_ms"),
                tag.getString("thumbnail_url"),
                tag.getBoolean("radio"));
    }

    // ── network (payload 同期用) ──

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(url);
        buf.writeUtf(title);
        buf.writeUtf(author);
        buf.writeVarLong(durationMs);
        buf.writeUtf(thumbnailUrl);
        buf.writeBoolean(radio);
    }

    public static CustomTrackData read(FriendlyByteBuf buf) {
        return new CustomTrackData(
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readVarLong(),
                buf.readUtf(),
                buf.readBoolean());
    }
}
