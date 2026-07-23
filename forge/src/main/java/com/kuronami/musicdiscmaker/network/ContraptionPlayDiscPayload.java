package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * server → client: Create 捕獲式 contraption に載った MDM 音源ブロックの再生開始。組立時に server 側の
 * {@code CreateAudioMovementBehaviour.startMoving} が凍結 BE データから track を読み、contraption entity を
 * 追跡中の player へ送る。client は entityId から {@code AbstractContraptionEntity} を解決し
 * {@code ContraptionAnchor} を組んで LavaPlayer 再生する。
 *
 * <p>このペイロード自体は Create 型を一切含まない (contraptionEntityId:int + localPos:BlockPos + track/
 * offset/range/volume の vanilla 型だけ)。よって Create 非依存で登録でき、Create 非導入環境でも問題ない
 * (送信されないだけ)。Create 型に触れるのは client 受信側の {@code ContraptionAnchor} 構築のみ。これは
 * {@link com.kuronami.musicdiscmaker.compat.sophisticatedcore.BackpackPlayDiscPayload} と同型の設計。
 */
public record ContraptionPlayDiscPayload(int contraptionEntityId, BlockPos localPos, CustomTrackData track,
        long startOffsetMs, int rangeBlocks, int volumePercent) implements ModPayload {

    public static final ResourceLocation ID =
            new ResourceLocation(MusicDiscMaker.MODID, "create_contraption_play");

    public static ContraptionPlayDiscPayload read(FriendlyByteBuf buf) {
        return new ContraptionPlayDiscPayload(buf.readVarInt(), buf.readBlockPos(), CustomTrackData.read(buf),
                buf.readVarLong(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(contraptionEntityId);
        buf.writeBlockPos(localPos);
        track.write(buf);
        buf.writeVarLong(startOffsetMs);
        buf.writeVarInt(rangeBlocks);
        buf.writeVarInt(volumePercent);
    }
}
