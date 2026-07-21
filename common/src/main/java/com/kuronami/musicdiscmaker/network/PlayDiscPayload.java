package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * server → client: jukebox(pos) で custom disc 再生開始。各 client が独立に LavaPlayer で再生する。
 * {@code startOffsetMs} は再生開始位置 (ミリ秒)。挿入時は 0、後から chunk に入った player への
 * 追従再生では経過時間を入れて途中から同期させる。
 *
 * <p>{@code rangeBlocks} / {@code volumePercent} は強化版ジュークボックス由来の per-block 設定。
 * バニラ jukebox 経路は {@link #vanilla(BlockPos, CustomTrackData, long)} が sentinel
 * ({@code rangeBlocks=0} = client config の playbackRange をそのまま使う、{@code volumePercent=100})
 * を入れるので、既存挙動は変わらない。強化版では実効可聴範囲 = min(rangeBlocks, maxPlaybackRange) を
 * client 側で適用する (maxPlaybackRange 既定 256 = playbackRange と分離した cap)。
 */
public record PlayDiscPayload(BlockPos jukeboxPos, CustomTrackData track, long startOffsetMs,
        int rangeBlocks, int volumePercent) implements ModPayload {

    public static final ResourceLocation ID = new ResourceLocation(MusicDiscMaker.MODID, "play_disc");

    /** バニラ jukebox 経路用: per-block 設定なし (rangeBlocks=0 → client config, volumePercent=100)。 */
    public static PlayDiscPayload vanilla(BlockPos pos, CustomTrackData track, long startOffsetMs) {
        return new PlayDiscPayload(pos, track, startOffsetMs, 0, 100);
    }

    public static PlayDiscPayload read(FriendlyByteBuf buf) {
        return new PlayDiscPayload(buf.readBlockPos(), CustomTrackData.read(buf), buf.readVarLong(),
                buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(jukeboxPos);
        track.write(buf);
        buf.writeVarLong(startOffsetMs);
        buf.writeVarInt(rangeBlocks);
        buf.writeVarInt(volumePercent);
    }
}
