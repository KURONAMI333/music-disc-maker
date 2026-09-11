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
        long startOffsetMs, int rangeBlocks, int volumePercent, boolean directional, PlaybackSourceStamp identity, com.kuronami.musicdiscmaker.component.VanillaTrackData vanilla, com.kuronami.musicdiscmaker.network.MovingSpeakerState receivers) implements ModPayload {

    public ContraptionPlayDiscPayload(int entity, BlockPos pos, CustomTrackData track, long offset,
            int range, int volume, boolean directional, com.kuronami.musicdiscmaker.network.PlaybackSourceStamp identity,
            com.kuronami.musicdiscmaker.component.VanillaTrackData vanilla) {
        this(entity, pos, track, offset, range, volume, directional, identity, vanilla,
                com.kuronami.musicdiscmaker.network.MovingSpeakerState.empty(pos));
    }

    public ContraptionPlayDiscPayload(int entity, BlockPos pos, CustomTrackData track, long offset,
            int range, int volume, boolean directional, com.kuronami.musicdiscmaker.network.PlaybackSourceStamp identity) {
        this(entity, pos, track, offset, range, volume, directional, identity, null);
    }

    public ContraptionPlayDiscPayload(int entity, BlockPos pos, CustomTrackData track, long offset,
            int range, int volume, boolean directional) {
        this(entity, pos, track, offset, range, volume, directional,
                com.kuronami.musicdiscmaker.network.PlaybackSourceStamp.NONE);
    }

    // With neither custom nor vanilla media, this explicitly stops the actor's source lease.
    public static ContraptionPlayDiscPayload stop(int entity, BlockPos pos,
            com.kuronami.musicdiscmaker.network.PlaybackSourceStamp identity) {
        return new ContraptionPlayDiscPayload(entity, pos, CustomTrackData.EMPTY, 0L, 0, 0, false, identity);
    }

    private static void writeVanilla(net.minecraft.network.FriendlyByteBuf buf,
            com.kuronami.musicdiscmaker.component.VanillaTrackData track) {
        buf.writeBoolean(track != null);
        if (track != null) { buf.writeUtf(track.soundEventId(), 256); buf.writeVarLong(track.durationMs()); }
    }

    private static com.kuronami.musicdiscmaker.component.VanillaTrackData readVanilla(net.minecraft.network.FriendlyByteBuf buf) {
        return buf.readBoolean() ? new com.kuronami.musicdiscmaker.component.VanillaTrackData(
                buf.readUtf(256), buf.readVarLong()) : null;
    }

    public boolean isStop() { return track.isEmpty() && vanilla == null; }

    public static final ResourceLocation ID =
            new ResourceLocation(MusicDiscMaker.MODID, "create_contraption_play");

    public static ContraptionPlayDiscPayload read(FriendlyByteBuf buf) {
        return new ContraptionPlayDiscPayload(buf.readVarInt(), buf.readBlockPos(), CustomTrackData.read(buf),
                buf.readVarLong(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), PlaybackSourceStamp.read(buf), readVanilla(buf), MovingSpeakerState.read(buf));
    }


    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(contraptionEntityId);
        buf.writeBlockPos(localPos);
        track.write(buf);
        buf.writeVarLong(startOffsetMs);
        buf.writeVarInt(rangeBlocks);
        buf.writeVarInt(volumePercent);
        buf.writeBoolean(directional);
        identity.write(buf);
        writeVanilla(buf, vanilla);
        receivers.write(buf);
    }
}
