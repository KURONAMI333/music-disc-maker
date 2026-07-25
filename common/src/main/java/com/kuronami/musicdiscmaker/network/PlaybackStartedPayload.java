package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * client → server: 「この再生の音が実際に鳴り始めた」の 1 回きりの報告。ビート連動の校正に使う。
 *
 * <p>ビート出力の精度を決めているのは tick 粒度 (50ms) ではなく <b>client の音声開始遅延</b>。
 * MC の {@code Channel} は {@code alSourcePlay} の前に 4 秒ぶんの PCM をバッファするので、
 * 「server が再生を指示した時刻」と「実際に音が出た時刻」は秒オーダーでずれ、しかも毎回違う。
 *
 * <p><b>client の時計は載せない。</b> server は packet の到着時刻を「いま {@code audioOffsetMs} の
 * 位置が鳴っている」と読むので、時計合わせも往復遅延の推定も要らない。
 *
 * <p>{@code playbackId} は再生セッションの識別子。シーク・リピート折返し・chunk 再ロードで
 * 更新されるので、古いセッションの報告が新しい再生の位相を壊すことはない。
 */
public record PlaybackStartedPayload(BlockPos jukeboxPos, long playbackId, long audioOffsetMs)
        implements CustomPacketPayload {

    public static final Type<PlaybackStartedPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "playback_started"));

    public static final StreamCodec<ByteBuf, PlaybackStartedPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, PlaybackStartedPayload::jukeboxPos,
            ByteBufCodecs.VAR_LONG, PlaybackStartedPayload::playbackId,
            ByteBufCodecs.VAR_LONG, PlaybackStartedPayload::audioOffsetMs,
            PlaybackStartedPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
