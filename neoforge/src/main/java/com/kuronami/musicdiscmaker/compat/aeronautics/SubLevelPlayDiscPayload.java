package com.kuronami.musicdiscmaker.compat.aeronautics;

//? if >=1.21.2 {
//?} else {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/^*
 * server → client: Create Aeronautics (Sable 変換式) の物理 sub-level に載った MDM 音源ブロックの再生開始。
 * server 側 {@link SableServerAudio} が sub-level を tracking 中の player へ、その sub-level plot 内の
 * ブロック座標 ({@code plotPos}) を送る。client は {@code plotPos} を含む {@code ClientSubLevel} を
 * {@code Sable.HELPER.getContainingClient} で解決し {@link SableSubLevelAnchor} を組んで追従再生する。
 *
 * <p>捕獲式 (Create 本家) の {@code ContraptionPlayDiscPayload} と同じく、このペイロード自体は Sable 型を
 * 一切含まない (plotPos:BlockPos + track/offset/range/volume の vanilla 型のみ)。よって Sable 非導入環境でも
 * 無条件に登録してよい (送信されないだけ)。Sable 型に触れるのは client 受信側の {@link SableSubLevelAnchor}
 * 構築のみ。
 *
 * <p>{@code plotPos} は sub-level の plot (off-map の実ブロック領域) 内での実 BlockPos。ブロックは捕獲式と
 * 違い実在し tick し続けるため、原 world セルは組立で空になり client の {@code StaticAnchor} は自己消音する。
 * このペイロードが plot 座標へ張り直した変換式再生へ引き継ぐ。
 ^/
public record SubLevelPlayDiscPayload(BlockPos plotPos, CustomTrackData track, long startOffsetMs,
        int rangeBlocks, int volumePercent, boolean directional,
        com.kuronami.musicdiscmaker.network.PlaybackSourceStamp identity) implements CustomPacketPayload {

    public SubLevelPlayDiscPayload(BlockPos pos, CustomTrackData track, long offset, int range, int volume, boolean directional) {
        this(pos, track, offset, range, volume, directional,
                com.kuronami.musicdiscmaker.network.PlaybackSourceStamp.NONE);
    }

    public static final Type<SubLevelPlayDiscPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "sable_sublevel_play"));

    public static final StreamCodec<ByteBuf, SubLevelPlayDiscPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                BlockPos.STREAM_CODEC.encode(buf, payload.plotPos());
                CustomTrackData.STREAM_CODEC.encode(buf, payload.track());
                ByteBufCodecs.VAR_LONG.encode(buf, payload.startOffsetMs());
                ByteBufCodecs.VAR_INT.encode(buf, payload.rangeBlocks());
                ByteBufCodecs.VAR_INT.encode(buf, payload.volumePercent());
                ByteBufCodecs.BOOL.encode(buf, payload.directional());
                payload.identity().write(new net.minecraft.network.FriendlyByteBuf(buf));
            },
            buf -> new SubLevelPlayDiscPayload(
                    BlockPos.STREAM_CODEC.decode(buf),
                    CustomTrackData.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf),
                    com.kuronami.musicdiscmaker.network.PlaybackSourceStamp.read(new net.minecraft.network.FriendlyByteBuf(buf))));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

*///?}
