package com.kuronami.musicdiscmaker.compat.sophisticatedcore;

import java.util.UUID;

import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Sophisticated Backpacks の (非公式) Fabric port 互換の server 側配線 (Fabric 1.20.1)。
 *
 * <p>公式 (Forge/NeoForge) は {@code IDiscHandler} 公開 API があるが、port にはまだ無い。
 * 代わりに {@code JukeboxUpgradeWrapper.playDisc} に @Pseudo mixin で TAIL 割り込みし、port が
 * 無音 jukebox_song を鳴らした「後に」MDM のストリーミングを上乗せする (mixin → {@link #streamToNearby})。
 * client 側は {@code StorageSoundHandler.playStorageSound} に同じ storageUuid で登録するので、
 * TAIL の登録が後勝ちし、port の停止がこの音声も止める。
 *
 * <p>このクラスは server 側でだけ参照される (mixin の inject から)。client 受信は
 * {@code MusicDiscMakerFabricClient} で配線し、{@link SophisticatedCoreCompatClient} が再生する
 * (dedicated server で client 専用型をロードしないため)。
 */
public final class SophisticatedCoreCompat {

    private static final double BROADCAST_RADIUS = 128.0;

    private SophisticatedCoreCompat() {
    }

    /**
     * mixin から呼ばれる (server thread): storageUuid で識別される backpack の位置で custom disc を
     * ストリーミング再生させる packet を周囲のプレイヤーへ送る。
     *
     * @param entityId backpack を背負う entity の id (固定ストレージなら -1)
     */
    public static void streamToNearby(ServerLevel level, Vec3 soundPos, UUID storageUuid,
            CustomTrackData track, int entityId) {
        final BlockPos clientPos = BlockPos.containing(soundPos);
        final BackpackPlayDiscPayload payload =
                new BackpackPlayDiscPayload(storageUuid, track, clientPos, entityId);
        for (final ServerPlayer player : PlayerLookup.around(level, soundPos, BROADCAST_RADIUS)) {
            final FriendlyByteBuf buf = PacketByteBufs.create();
            payload.write(buf);
            ServerPlayNetworking.send(player, BackpackPlayDiscPayload.ID, buf);
        }
    }
}
