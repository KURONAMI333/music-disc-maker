package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
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
 *
 * <p>{@code directional} は聴取モデル。true = 従来どおりの positional audio。false = 可聴範囲の中なら
 * 位置に関係なくフラットに聴こえる (BGM モード)。強化版の GUI トグルで切り替わる。BE 同期でも届くが、
 * 再生開始の 1 tick を positional で鳴らさないためと、client 側 BE を読めない経路のためにここでも運ぶ。
 * バニラ jukebox 経路は {@code true} (従来の挙動)。
 */
public record PlayDiscPayload(BlockPos jukeboxPos, CustomTrackData track, long startOffsetMs,
        int rangeBlocks, int volumePercent, boolean directional) implements CustomPacketPayload {

    public static final Type<PlayDiscPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "play_disc"));

    public static final StreamCodec<ByteBuf, PlayDiscPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, PlayDiscPayload::jukeboxPos,
            CustomTrackData.STREAM_CODEC, PlayDiscPayload::track,
            ByteBufCodecs.VAR_LONG, PlayDiscPayload::startOffsetMs,
            ByteBufCodecs.VAR_INT, PlayDiscPayload::rangeBlocks,
            ByteBufCodecs.VAR_INT, PlayDiscPayload::volumePercent,
            ByteBufCodecs.BOOL, PlayDiscPayload::directional,
            PlayDiscPayload::new);

    /**
     * バニラ jukebox 経路 (per-block 設定なし)。range は client config を、volume は 100% を使う。
     * 指向性トグルは強化版だけの機能なので、常に従来どおりの positional ({@code true}) で送る。
     */
    public static PlayDiscPayload vanilla(BlockPos jukeboxPos, CustomTrackData track, long startOffsetMs) {
        return new PlayDiscPayload(jukeboxPos, track, startOffsetMs, 0, 100, true);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
