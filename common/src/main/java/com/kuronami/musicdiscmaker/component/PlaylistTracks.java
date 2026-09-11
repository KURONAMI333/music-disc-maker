package com.kuronami.musicdiscmaker.component;

import java.util.ArrayList;
import java.util.List;

//? if >=1.21 {
import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
//?} else {
//?}

/**
 * 未公開の v3 試作盤に書かれた {@code playlist_tracks} component を読み込むためだけの値。
 * 新しい盤にはこの component を付けず、再生・表示・染色も参照しない。試作盤には従来の
 * {@code CUSTOM_TRACK} も併記されているため、現在はその先頭曲を持つ通常盤として扱う。
 */
public record PlaylistTracks(List<CustomTrackData> tracks) {

    /** 細工された同期値による巨大確保を防ぐ、旧形式の読み込み上限。 */
    private static final int LEGACY_WIRE_LIMIT = 256;

    /**
     * 旧データの空要素を捨て、同期上限で切って変更不能にする。
     */
    public PlaylistTracks {
        final List<CustomTrackData> cleaned = new ArrayList<>();
        if (tracks != null) {
            for (final CustomTrackData track : tracks) {
                if (track == null || track.isEmpty()) {
                    continue;
                }
                if (cleaned.size() >= LEGACY_WIRE_LIMIT) {
                    break;
                }
                cleaned.add(track);
            }
        }
        tracks = List.copyOf(cleaned);
    }

    //? if >=1.21 {

    public static final Codec<PlaylistTracks> CODEC =
            CustomTrackData.CODEC.listOf().xmap(PlaylistTracks::new, PlaylistTracks::tracks);

    /** 旧 component の同期 codec。 */
    public static final StreamCodec<ByteBuf, PlaylistTracks> STREAM_CODEC = StreamCodec.of(
            (buf, value) -> {
                ByteBufCodecs.VAR_INT.encode(buf, value.tracks().size());
                for (final CustomTrackData track : value.tracks()) {
                    CustomTrackData.STREAM_CODEC.encode(buf, track);
                }
            },
            buf -> {
                final int count = ByteBufCodecs.VAR_INT.decode(buf);
                if (count < 0 || count > LEGACY_WIRE_LIMIT) {
                    throw new IllegalStateException("music_disc_maker: bad playlist length " + count);
                }
                final List<CustomTrackData> read = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    read.add(CustomTrackData.STREAM_CODEC.decode(buf));
                }
                return new PlaylistTracks(read);
            });
    //?} else {
    //?}
}
