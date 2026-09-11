package com.kuronami.musicdiscmaker.compat.sophisticatedcore;
//? if <1.21.2 {

/*import java.util.UUID;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
//? if >=1.21 {
//?} else {
/^import com.kuronami.musicdiscmaker.network.ModPayload;
^/
//?}

//? if >=1.21 {
import io.netty.buffer.ByteBuf;
//?} else {
//?}
import net.minecraft.core.BlockPos;
//? if >=1.21 {
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//?} else {
/^import net.minecraft.network.FriendlyByteBuf;
^/
//?}
import net.minecraft.resources.ResourceLocation;

//? if >=1.21 {
public record BackpackPlayDiscPayload(UUID storageUuid, CustomTrackData track, BlockPos pos, int entityId) implements CustomPacketPayload {
//?} else {
/^public record BackpackPlayDiscPayload(UUID storageUuid, CustomTrackData track, BlockPos pos, int entityId)
        implements ModPayload {
^/
//?}

    //? if >=1.21 {
    public static final Type<BackpackPlayDiscPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "sc_backpack_play"));
    //?} else {
/^    public static final ResourceLocation ID = new ResourceLocation(MusicDiscMaker.MODID, "backpack_play_disc");
    ^/
    //?}

    //? if >=1.21 {
    public static final StreamCodec<ByteBuf, BackpackPlayDiscPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, BackpackPlayDiscPayload::storageUuid,
            CustomTrackData.STREAM_CODEC, BackpackPlayDiscPayload::track,
            BlockPos.STREAM_CODEC, BackpackPlayDiscPayload::pos,
            ByteBufCodecs.VAR_INT, BackpackPlayDiscPayload::entityId,
            BackpackPlayDiscPayload::new);
    //?} else {
/^    public static BackpackPlayDiscPayload read(FriendlyByteBuf buf) {
        return new BackpackPlayDiscPayload(
                buf.readUUID(), CustomTrackData.read(buf), buf.readBlockPos(), buf.readVarInt());
    }
    ^/
    //?}

    @Override
    //? if >=1.21 {
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    //?} else {
/^    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(storageUuid);
        track.write(buf);
        buf.writeBlockPos(pos);
        buf.writeVarInt(entityId);
    ^/
    //?}
    }
}

*///?}
