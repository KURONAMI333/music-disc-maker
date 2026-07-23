package com.kuronami.musicdiscmaker.compat.aeronautics;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
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
 */
public record SubLevelPlayDiscPayload(BlockPos plotPos, CustomTrackData track, long startOffsetMs,
        int rangeBlocks, int volumePercent) implements CustomPacketPayload {

    public static final Type<SubLevelPlayDiscPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "sable_sublevel_play"));

    public static final StreamCodec<ByteBuf, SubLevelPlayDiscPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SubLevelPlayDiscPayload::plotPos,
            CustomTrackData.STREAM_CODEC, SubLevelPlayDiscPayload::track,
            ByteBufCodecs.VAR_LONG, SubLevelPlayDiscPayload::startOffsetMs,
            ByteBufCodecs.VAR_INT, SubLevelPlayDiscPayload::rangeBlocks,
            ByteBufCodecs.VAR_INT, SubLevelPlayDiscPayload::volumePercent,
            SubLevelPlayDiscPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
