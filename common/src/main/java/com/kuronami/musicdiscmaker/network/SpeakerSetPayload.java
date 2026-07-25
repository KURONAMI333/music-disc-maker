package com.kuronami.musicdiscmaker.network;

import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * server → client: 音源 (強化版ジュークボックス) にぶら下がる有効スピーカー集合。
 *
 * <p>{@link PlayDiscPayload} から意図的に分離してある。client の
 * {@code ClientPlaybackManager#startPlayback} は「同じ URL・非シーク」の再送を dedup で握りつぶすため、
 * 再生 packet を再送しても集合の変化は伝わらない。逆に集合を再生 packet に相乗りさせると、
 * 曲送り (プレイリスト) のたびに集合を運ぶ無駄と payload 肥大が出る。曲の切り替わりと聴取モデルは
 * 直交させる。
 *
 * <p>曲情報を一切持たないので、プレイリスト (P6) が乗ってもこの payload は変わらない。
 */
public record SpeakerSetPayload(BlockPos sourcePos, List<SpeakerEntry> speakers) implements CustomPacketPayload {

    /** 1 packet に載せるスピーカーの上限 (config 上限より十分大きい防御値)。 */
    private static final int MAX_SPEAKERS = 64;

    public static final Type<SpeakerSetPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "speaker_set"));

    public static final StreamCodec<ByteBuf, SpeakerSetPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, SpeakerSetPayload::sourcePos,
            SpeakerEntry.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_SPEAKERS)), SpeakerSetPayload::speakers,
            SpeakerSetPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
