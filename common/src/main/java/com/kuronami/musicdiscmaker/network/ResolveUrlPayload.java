package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** client → server: maker(pos) の URL を解決して曲情報を取得させる。 */
public record ResolveUrlPayload(BlockPos pos, String url) implements CustomPacketPayload {

    public static final Type<ResolveUrlPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "resolve_url"));

    public static final StreamCodec<ByteBuf, ResolveUrlPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ResolveUrlPayload::pos,
            ByteBufCodecs.stringUtf8(2048), ResolveUrlPayload::url,
            ResolveUrlPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
