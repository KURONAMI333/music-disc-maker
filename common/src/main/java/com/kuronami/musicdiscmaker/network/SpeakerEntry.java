package com.kuronami.musicdiscmaker.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * {@link SpeakerSetPayload} が運ぶスピーカー 1 台の情報。位置と、送信時点の音量・可聴範囲の
 * スナップショット。
 *
 * <p>スナップショットは「client にスピーカーの chunk がロードされていない」時のフォールバック。
 * ロード済みなら client 側 {@code SpeakerBlockEntity} から毎 tick ライブ再読するので、スライダー操作は
 * 再送を待たずに反映される (強化版ジュークボックスの音量/範囲と同じ仕組み)。
 */
public record SpeakerEntry(BlockPos pos, int volumePercent, int rangeBlocks) {

    public static final StreamCodec<ByteBuf, SpeakerEntry> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SpeakerEntry::pos,
            ByteBufCodecs.VAR_INT, SpeakerEntry::volumePercent,
            ByteBufCodecs.VAR_INT, SpeakerEntry::rangeBlocks,
            SpeakerEntry::new);
}
