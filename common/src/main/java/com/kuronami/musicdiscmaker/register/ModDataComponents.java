package com.kuronami.musicdiscmaker.register;

//? if >=1.21 {
import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.color.DiscDye;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.DiscDyeData;
import com.kuronami.musicdiscmaker.component.PlaylistTracks;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.DyeColor;

/** custom music disc に載せる DataComponent。 */
public final class ModDataComponents {

    public static final RegistrationProvider<DataComponentType<?>> COMPONENTS =
            RegistrationProvider.get(Registries.DATA_COMPONENT_TYPE, MusicDiscMaker.MODID);

    public static final RegistryHolder<DataComponentType<CustomTrackData>> CUSTOM_TRACK =
            COMPONENTS.register("custom_track",
                    () -> DataComponentType.<CustomTrackData>builder()
                            .persistent(CustomTrackData.CODEC)
                            .networkSynchronized(CustomTrackData.STREAM_CODEC)
                            .build());

    // 染料は id 文字列で永続化する (ordinal だと並びを触った時に既存ディスクの色が変わる)。
    // 未知の id は「壊れた保存データ」として扱い、既定色を発明せずエラーにする。
    // 部分結果に WHITE を添えてあるが、MC 側がそれを promote するかは版依存で未確認。
    private static final Codec<DiscDye> DYE_CODEC = Codec.STRING.comapFlatMap(
            id -> {
                final DiscDye dye = DiscDye.byId(id);
                return dye != null
                        ? DataResult.success(dye)
                        : DataResult.error(() -> "Unknown music_disc_maker dye id: " + id, DiscDye.WHITE);
            },
            DiscDye::id);

    // 同期経路は既存wire形式の「UTF-8文字列を2本」を維持し、空文字だけを未指定側として拡張する。
    // 非空の未知idは従来どおりWHITEへ丸め、両方空はDiscDyeDataの不変条件で拒否する。
    private static final StreamCodec<ByteBuf, DiscDye> OPTIONAL_DYE_STREAM_CODEC = ByteBufCodecs.STRING_UTF8.map(
            id -> {
                if (id.isEmpty()) {
                    return null;
                }
                final DiscDye dye = DiscDye.byId(id);
                return dye != null ? dye : DiscDye.WHITE;
            },
            dye -> dye == null ? "" : dye.id());

    private static final Codec<DyeColor> DYE_COLOR_CODEC = Codec.STRING.comapFlatMap(
            id -> {
                final DyeColor color = DyeColor.byName(id, null);
                return color != null
                        ? DataResult.success(color)
                        : DataResult.error(() -> "Unknown music_disc_maker album color: " + id, DyeColor.WHITE);
            },
            DyeColor::getName);

    private static final StreamCodec<ByteBuf, DyeColor> DYE_COLOR_STREAM_CODEC = ByteBufCodecs.STRING_UTF8.map(
            id -> {
                final DyeColor color = DyeColor.byName(id, null);
                return color != null ? color : DyeColor.WHITE;
            },
            DyeColor::getName);

    private static final Codec<DiscDyeData> DISC_DYE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            DYE_CODEC.optionalFieldOf("board").forGetter(dye -> Optional.ofNullable(dye.board())),
            DYE_CODEC.optionalFieldOf("accent").forGetter(dye -> Optional.ofNullable(dye.accent()))
    ).apply(instance, (board, accent) -> new DiscDyeData(board.orElse(null), accent.orElse(null))));

    private static final StreamCodec<ByteBuf, DiscDyeData> DISC_DYE_STREAM_CODEC = StreamCodec.composite(
            OPTIONAL_DYE_STREAM_CODEC, DiscDyeData::board,
            OPTIONAL_DYE_STREAM_CODEC, DiscDyeData::accent,
            DiscDyeData::new);

    /**
     * 染色データ。各領域に指定された染料 id だけを持ち、実際の色は {@code DiscPalette} が計算する。
     *
     * <p>{@link CustomTrackData} に相乗りさせず別 component にしてある。理由は 2 つ:
     * 既存 component の必須フィールドを増やすと v2 のディスクが読めなくなること、
     * 別 component なら「component が無い」がそのまま「未染色」の判定になり、
     * 既定色を発明しなくて済むこと。
     */
    public static final RegistryHolder<DataComponentType<DiscDyeData>> DISC_DYE =
            COMPONENTS.register("disc_dye",
                    () -> DataComponentType.<DiscDyeData>builder()
                            .persistent(DISC_DYE_CODEC)
                            .networkSynchronized(DISC_DYE_STREAM_CODEC)
                            .build());

    /**
     * ブームボックスが咥えているディスク。器 (アイテム) 側に載せる。
     *
     * <p>{@link com.kuronami.musicdiscmaker.component.DiscDyeData} と同じ理由で独立した component に
     * してある: component が無い = 空、がそのまま成立し、既定の中身を発明しなくて済む。
     */
    public static final RegistryHolder<DataComponentType<BoomboxContents>> BOOMBOX_CONTENTS =
            COMPONENTS.register("boombox_contents",
                    () -> DataComponentType.<BoomboxContents>builder()
                            .persistent(BoomboxContents.CODEC)
                            .networkSynchronized(BoomboxContents.STREAM_CODEC)
                            .build());

    /**
     * 未公開の v3 試作盤を通常盤として読み込めるようにする decode-only component。
     * 新規保存・再生・表示からは参照しない。registry id は旧スタックを壊さないため固定する。
     */
    public static final RegistryHolder<DataComponentType<PlaylistTracks>> PLAYLIST_TRACKS =
            COMPONENTS.register("playlist_tracks",
                    () -> DataComponentType.<PlaylistTracks>builder()
                            .persistent(PlaylistTracks.CODEC)
                            .networkSynchronized(PlaylistTracks.STREAM_CODEC)
                            .build());

    /** MDM Album が保存する盤の実物。再生位置は容器の PlaybackCursor が持つ。 */
    public static final RegistryHolder<DataComponentType<AlbumContents>> ALBUM_CONTENTS =
            COMPONENTS.register("album_contents",
                    () -> DataComponentType.<AlbumContents>builder()
                            .persistent(AlbumContents.CODEC)
                            .networkSynchronized(AlbumContents.STREAM_CODEC)
                            .build());

    /**
     * Album の外装色。component が無い既存 Album は未染色として扱う。
     * 中身の {@link AlbumContents} と独立させるので、色を変えても盤・順序・名前を再符号化しない。
     */
    public static final RegistryHolder<DataComponentType<DyeColor>> ALBUM_COLOR =
            COMPONENTS.register("album_color",
                    () -> DataComponentType.<DyeColor>builder()
                            .persistent(DYE_COLOR_CODEC)
                            .networkSynchronized(DYE_COLOR_STREAM_CODEC)
                            .build());

    private ModDataComponents() {
    }

    public static void init() {
    }
}

//?} else {
//?}
