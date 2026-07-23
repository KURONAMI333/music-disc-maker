package com.kuronami.musicdiscmaker.compat.create;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * server → client: Create contraption に載った MDM 音源ブロックの再生開始。組立時に server 側の
 * {@link CreateAudioMovementBehaviour#startMoving} が凍結 BE データから track を読み、contraption
 * entity を追跡中の player へ送る。client は entityId から {@code AbstractContraptionEntity} を解決し
 * {@link ContraptionAnchor} を組んで LavaPlayer 再生する。
 *
 * <p>このペイロード自体は Create 型を一切含まない (entityId:int + localPos:BlockPos + track/offset/
 * range/volume)。よって common/network と同じ vanilla 型だけで表現でき、Create 非導入環境でも登録して
 * 問題ない (送信されないだけ)。Create 型に触れるのは client 受信側の {@link ContraptionAnchor} 構築のみ。
 * これは {@code BackpackPlayDiscPayload} (SC 非依存ペイロード) と同型の設計。
 */
public record ContraptionPlayDiscPayload(int contraptionEntityId, BlockPos localPos, CustomTrackData track,
        long startOffsetMs, int rangeBlocks, int volumePercent) implements CustomPacketPayload {

    public static final Type<ContraptionPlayDiscPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "create_contraption_play"));

    public static final StreamCodec<ByteBuf, ContraptionPlayDiscPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ContraptionPlayDiscPayload::contraptionEntityId,
            BlockPos.STREAM_CODEC, ContraptionPlayDiscPayload::localPos,
            CustomTrackData.STREAM_CODEC, ContraptionPlayDiscPayload::track,
            ByteBufCodecs.VAR_LONG, ContraptionPlayDiscPayload::startOffsetMs,
            ByteBufCodecs.VAR_INT, ContraptionPlayDiscPayload::rangeBlocks,
            ByteBufCodecs.VAR_INT, ContraptionPlayDiscPayload::volumePercent,
            ContraptionPlayDiscPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
