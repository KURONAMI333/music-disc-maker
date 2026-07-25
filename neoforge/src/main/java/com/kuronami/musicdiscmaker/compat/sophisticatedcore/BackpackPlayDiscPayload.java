package com.kuronami.musicdiscmaker.compat.sophisticatedcore;

import java.util.UUID;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * server → client: Sophisticated Backpacks の Jukebox Upgrade から custom disc 再生開始。
 * vanilla jukebox の {@code PlayDiscPayload} と違い、SC のサウンドライフサイクル (停止・keep-alive) に
 * 乗せるため storageUuid を運ぶ。client は DiscSoundInstance を SC の {@code StorageSoundHandler} に
 * storageUuid で登録するので、SC の停止パケットがそのまま MDM の LavaPlayer 音声を止める。
 *
 * <p>{@code entityId >= 0} ならその entity (backpack を背負うプレイヤー等) に音源を追従させる。
 * 設置ストレージなど固定の場合は {@code -1} で {@code pos} を使う。
 *
 * <p>このペイロード自体は SC に依存しない (UUID/track/pos/entityId のみ) ので無条件に登録してよい。
 */
public record BackpackPlayDiscPayload(UUID storageUuid, CustomTrackData track, BlockPos pos, int entityId) implements CustomPacketPayload {

    public static final Type<BackpackPlayDiscPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "sc_backpack_play"));

    public static final StreamCodec<ByteBuf, BackpackPlayDiscPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, BackpackPlayDiscPayload::storageUuid,
            CustomTrackData.STREAM_CODEC, BackpackPlayDiscPayload::track,
            BlockPos.STREAM_CODEC, BackpackPlayDiscPayload::pos,
            ByteBufCodecs.VAR_INT, BackpackPlayDiscPayload::entityId,
            BackpackPlayDiscPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
