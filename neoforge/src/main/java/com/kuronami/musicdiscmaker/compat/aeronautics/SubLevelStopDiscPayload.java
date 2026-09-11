package com.kuronami.musicdiscmaker.compat.aeronautics;

//? if <1.21.2 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.network.PlaybackSourceStamp;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record SubLevelStopDiscPayload(BlockPos plotPos, PlaybackSourceStamp identity) implements CustomPacketPayload {
    public static final Type<SubLevelStopDiscPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "sable_sublevel_stop"));
    public static final StreamCodec<ByteBuf, SubLevelStopDiscPayload> STREAM_CODEC = StreamCodec.of(
            (buf, payload) -> {
                BlockPos.STREAM_CODEC.encode(buf, payload.plotPos());
                payload.identity().write(new FriendlyByteBuf(buf));
            },
            buf -> new SubLevelStopDiscPayload(BlockPos.STREAM_CODEC.decode(buf),
                    PlaybackSourceStamp.read(new FriendlyByteBuf(buf))));
    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
*///?}
