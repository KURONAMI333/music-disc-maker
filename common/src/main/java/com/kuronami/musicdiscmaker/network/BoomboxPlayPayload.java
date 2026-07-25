package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * server → client: entity(entityId) が手に持っているブームボックスの再生。
 *
 * <p>ブロックと違い chunk を持たないので、宛先は「その entity を追跡中の player + 本人」。
 * server は再生中のあいだ一定間隔で撃ち続ける (keep-alive)。これが 3 役を兼ねる:
 * ① 再生開始 ② 後から近づいた player への late-join (次の keep-alive で拾える)
 * ③ client 側の生存確認 — 一定時間届かなければ「持ち主がしまった/落とした」とみなして自己停止する。
 *
 * <p>client は URL だけで dedup する。keep-alive は毎回 offset が進むので、offset を dedup 条件に
 * 入れると 1 秒ごとに再ストリームが走る。
 */
public record BoomboxPlayPayload(int entityId, CustomTrackData track, long startOffsetMs,
        int rangeBlocks, int volumePercent, boolean directional) implements CustomPacketPayload {

    public static final Type<BoomboxPlayPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "boombox_play"));

    public static final StreamCodec<ByteBuf, BoomboxPlayPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, BoomboxPlayPayload::entityId,
            CustomTrackData.STREAM_CODEC, BoomboxPlayPayload::track,
            ByteBufCodecs.VAR_LONG, BoomboxPlayPayload::startOffsetMs,
            ByteBufCodecs.VAR_INT, BoomboxPlayPayload::rangeBlocks,
            ByteBufCodecs.VAR_INT, BoomboxPlayPayload::volumePercent,
            ByteBufCodecs.BOOL, BoomboxPlayPayload::directional,
            BoomboxPlayPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
