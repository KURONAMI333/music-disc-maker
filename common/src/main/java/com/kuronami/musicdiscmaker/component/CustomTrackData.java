package com.kuronami.musicdiscmaker.component;

import java.util.Locale;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

//? if >=1.21.2 {
//?} elif >=1.21 {
/*import io.netty.buffer.ByteBuf;
*/
//?} else {
//?}
import net.minecraft.network.FriendlyByteBuf;
//? if >=1.21.2 {
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
//?} elif >=1.21 {
/*import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
*/
//?} else {
/*import net.minecraft.nbt.CompoundTag;
*/
//?}

/**
 * custom music disc に付与する曲メタのペイロード。
 * URL / 曲名 / アーティスト / 長さ / サムネ URL / ラジオ判定を保持する。
 *
 * <p>{@code radio}: 無限長ストリーム (icecast/HTTP ラジオ・ライブ配信) を表す。radio 時は
 * {@code durationMs} を 0 sentinel で保存し (ActiveDiscRegistry の prune 回避と整合)、UI は
 * 尺の代わりに「LIVE」を出す。「長さ不明」を duration に多重化せず boolean で明示する。
 *
 * <p>シリアライズ経路:
 * <ul>
 *   <li>{@link #CODEC} — 永続化 (1.21+ は DataComponent と BE、1.20.1 は BE の NBT)。</li>
 *   <li>{@link #write(FriendlyByteBuf)} / {@link #read(FriendlyByteBuf)} — payload 同期。3版共通。</li>
 * </ul>
 */
public record CustomTrackData(
        String url,
        String title,
        String author,
        long durationMs,
        String thumbnailUrl,
        boolean radio,
        String sourceKind,
        String localHash) {

    // 各文字列の上限。url/サムネは長めの実 URL を許容、曲名/アーティストは表示用に短く。
    // 上限なしだと細工された巨大メタデータで writeUtf (32767 byte 上限) が例外→同期で接続断。
    private static final int MAX_URL = 2048;
    private static final int MAX_TEXT = 256;

    public CustomTrackData {
        sourceKind = "local".equals(sourceKind) ? "local" : "url";
        localHash = localHash == null ? "" : localHash;
        if ("local".equals(sourceKind) && !localHash.matches("[a-f0-9]{64}")) {
            // 保存値・creative の stack・通信のどこから来ても例外にしない (1.20.1 は NBT を
            // そのまま読むので、投げると server tick ごと落ちる)。参照の無い空ディスクとして扱う。
            sourceKind = "url";
            localHash = "";
            url = "";
        }
        if ("local".equals(sourceKind)) { url = ""; radio = false; thumbnailUrl = ""; }
        else localHash = "";
        url = clamp(url, MAX_URL);
        title = clamp(title, MAX_TEXT);
        author = clamp(author, MAX_TEXT);
        thumbnailUrl = clamp(thumbnailUrl, MAX_URL);
    }

    /** 既存のURLディスク作成呼出と保存データを維持する。 */
    public CustomTrackData(String url, String title, String author, long durationMs, String thumbnailUrl, boolean radio) {
        this(url, title, author, durationMs, thumbnailUrl, radio, "url", "");
    }

    public static CustomTrackData local(String hash, String title, String author, long durationMs) {
        return new CustomTrackData("", title, author, durationMs, "", false, "local", hash);
    }

    public boolean isLocal() { return "local".equals(sourceKind); }

    /** 曲名だけを差し替えた写し (製作機での付け直し)。 */
    public CustomTrackData withTitle(String newTitle) {
        return new CustomTrackData(url, newTitle, author, durationMs, thumbnailUrl, radio, sourceKind, localHash);
    }

    /** 再生世代・重複抑止の比較鍵。URL入力として解釈しない。 */
    public String sourceKey() { return isLocal() ? "local:" + localHash : url; }

    private static String clamp(String s, int max) {
        if (s == null) {
            return "";
        }
        // UTF-16単位の上限は保つが、補助文字のペアは分断しない。
        // 分断するとUTF-8同期で置換され、小さい盤でも往復一致検査が失敗する。
        int end = Math.min(s.length(), max);
        if (end < s.length() && Character.isHighSurrogate(s.charAt(end - 1))
                && Character.isLowSurrogate(s.charAt(end))) end--;
        // 旧clampで分断して保存された値と、元から孤立した値もconstructorで正規化する。
        // 実UTF-8同期が孤立サロゲートを '?' へ置換するため、同期前の値も同じ形へ揃える。
        StringBuilder normalized = null;
        for (int i = 0; i < end; i++) {
            final char current = s.charAt(i);
            if (Character.isHighSurrogate(current) && i + 1 < end
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                if (normalized != null) normalized.append(current).append(s.charAt(i + 1));
                i++;
                continue;
            }
            if (Character.isSurrogate(current)) {
                if (normalized == null) normalized = new StringBuilder(end).append(s, 0, i);
                normalized.append('?');
            } else if (normalized != null) {
                normalized.append(current);
            }
        }
        return normalized != null ? normalized.toString()
                : end == s.length() ? s : s.substring(0, end);
    }

    public static final CustomTrackData EMPTY = new CustomTrackData("", "", "", 0L, "", false);

    // radio は optionalFieldOf: 既存ディスク (radio フィールドなし) を壊さず false 既定でデコードする。
    public static final Codec<CustomTrackData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("url").forGetter(CustomTrackData::url),
            Codec.STRING.fieldOf("title").forGetter(CustomTrackData::title),
            Codec.STRING.fieldOf("author").forGetter(CustomTrackData::author),
            Codec.LONG.fieldOf("duration_ms").forGetter(CustomTrackData::durationMs),
            Codec.STRING.fieldOf("thumbnail_url").forGetter(CustomTrackData::thumbnailUrl),
            Codec.BOOL.optionalFieldOf("radio", false).forGetter(CustomTrackData::radio),
            Codec.STRING.optionalFieldOf("source_kind", "url").forGetter(CustomTrackData::sourceKind),
            Codec.STRING.optionalFieldOf("local_hash", "").forGetter(CustomTrackData::localHash)
    ).apply(instance, CustomTrackData::new));

    //? if >=1.21 {
    public static final StreamCodec<ByteBuf, CustomTrackData> STREAM_CODEC = new StreamCodec<>() {
        @Override public CustomTrackData decode(ByteBuf buf) { return read(new FriendlyByteBuf(buf)); }
        @Override public void encode(ByteBuf buf, CustomTrackData value) { value.write(new FriendlyByteBuf(buf)); }
    };
    //?} else {
    //?}

    public boolean isEmpty() {
        return !isLocal() && url.isBlank();
    }

    /**
     * ツールチップに出す配信元の表示名 (「YouTube」「SoundCloud」等)。
     * 表に出す名前を持たないホストはホスト名をそのまま返し、URL が空なら空文字を返す。
     */
    public String sourceName() {
        if (isLocal()) return "File";
        final String host = host();
        if (host.isEmpty()) {
            return "";
        }
        if (host.equals("youtube.com") || host.equals("youtu.be") || host.endsWith(".youtube.com")) {
            return "YouTube";
        }
        if (host.equals("soundcloud.com") || host.endsWith(".soundcloud.com")) {
            return "SoundCloud";
        }
        if (host.equals("bandcamp.com") || host.endsWith(".bandcamp.com")) {
            return "Bandcamp";
        }
        if (host.equals("spotify.com") || host.endsWith(".spotify.com")) {
            return "Spotify";
        }
        if (host.equals("vimeo.com") || host.endsWith(".vimeo.com")) {
            return "Vimeo";
        }
        if (host.equals("twitch.tv") || host.endsWith(".twitch.tv")) {
            return "Twitch";
        }
        return host;
    }

    private String host() {
        if (url == null || url.isBlank()) {
            return "";
        }
        final String trimmed = url.trim();
        final int schemeEnd = trimmed.indexOf("://");
        if (schemeEnd < 0) {
            return "";
        }
        final String rest = trimmed.substring(schemeEnd + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            final char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == ':') {
                end = i;
                break;
            }
        }
        final String host = rest.substring(0, end).toLowerCase(Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    /** 表示用 "M:SS"。 */
    public String formattedDuration() {
        final long totalSeconds = durationMs / 1000L;
        return String.format("%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(url);
        buf.writeUtf(title);
        buf.writeUtf(author);
        buf.writeVarLong(durationMs);
        buf.writeUtf(thumbnailUrl);
        buf.writeBoolean(radio);
        buf.writeUtf(sourceKind, 16);
        buf.writeUtf(localHash, 64);
    }

    public static CustomTrackData read(FriendlyByteBuf buf) {
        return new CustomTrackData(
                buf.readUtf(),
                buf.readUtf(),
                buf.readUtf(),
                buf.readVarLong(),
                buf.readUtf(),
                buf.readBoolean(),
                buf.readUtf(16),
                buf.readUtf(64));
    }
    //? if >=1.21 {
    //?} else {
/*    public CompoundTag toNbt() {
        final CompoundTag tag = new CompoundTag();
        tag.putString("url", url);
        tag.putString("title", title);
        tag.putString("author", author);
        tag.putLong("duration_ms", durationMs);
        tag.putString("thumbnail_url", thumbnailUrl);
        tag.putBoolean("radio", radio);
        tag.putString("source_kind", sourceKind);
        tag.putString("local_hash", localHash);
        return tag;
    }

    public static CustomTrackData fromNbt(CompoundTag tag) {
        if (tag == null) {
            return EMPTY;
        }
        return new CustomTrackData(
                tag.getString("url"),
                tag.getString("title"),
                tag.getString("author"),
                tag.getLong("duration_ms"),
                tag.getString("thumbnail_url"),
                tag.getBoolean("radio"),
                tag.getString("source_kind"),
                tag.getString("local_hash"));
    }
    */
    //?}
}


