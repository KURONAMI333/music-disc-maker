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
        long startOffsetMs, int rangeBlocks, int volumePercent, boolean directional)
        implements CustomPacketPayload {

    public static final Type<ContraptionPlayDiscPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "create_contraption_play"));

    // composite は 6 要素までなので、7 要素目 (directional) を足した時点で手書きにする。
    // 読み書きの順序は 1 箇所で対応しているので、フィールドを足す時はここだけを見ればよい。
    public static final StreamCodec<ByteBuf, ContraptionPlayDiscPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                ByteBufCodecs.VAR_INT.encode(buf, payload.contraptionEntityId());
                BlockPos.STREAM_CODEC.encode(buf, payload.localPos());
                CustomTrackData.STREAM_CODEC.encode(buf, payload.track());
                ByteBufCodecs.VAR_LONG.encode(buf, payload.startOffsetMs());
                ByteBufCodecs.VAR_INT.encode(buf, payload.rangeBlocks());
                ByteBufCodecs.VAR_INT.encode(buf, payload.volumePercent());
                ByteBufCodecs.BOOL.encode(buf, payload.directional());
            },
            buf -> new ContraptionPlayDiscPayload(
                    ByteBufCodecs.VAR_INT.decode(buf),
                    BlockPos.STREAM_CODEC.decode(buf),
                    CustomTrackData.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.BOOL.decode(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
