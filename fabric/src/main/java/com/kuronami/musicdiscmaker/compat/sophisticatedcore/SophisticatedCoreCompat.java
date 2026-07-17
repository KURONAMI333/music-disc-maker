package com.kuronami.musicdiscmaker.compat.sophisticatedcore;

import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Sophisticated Backpacks の (非公式) Fabric port 互換の配線 (Fabric)。
 *
 * <p>公式 (Forge/NeoForge) は {@code IDiscHandler} 公開 API があるが、port にはまだ無い。
 * 代わりに {@code JukeboxUpgradeWrapper.playDisc} に @Pseudo mixin で TAIL 割り込みし、port が
 * 無音 jukebox_song を鳴らした「後に」MDM のストリーミングを上乗せする (mixin → このクラスの
 * {@link #streamToNearby})。client 側は {@code StorageSoundHandler.playStorageSound} に同じ
 * storageUuid で登録するので、TAIL の登録が後勝ちし、port の停止がこの音声も止める。
 *
 * <p>ペイロード自体は SC 非依存なので無条件登録してよい (port 不在環境では mixin が当たらず
 * このコードに到達しないだけ)。
 */
public final class SophisticatedCoreCompat {

    private static final double BROADCAST_RADIUS = 128.0;

    private SophisticatedCoreCompat() {
    }

    /** server 起動時: backpack 再生ペイロードを S2C 登録する (無条件)。 */
    public static void registerPayload() {
        PayloadTypeRegistry.playS2C().register(
                BackpackPlayDiscPayload.TYPE, BackpackPlayDiscPayload.STREAM_CODEC);
    }

    /** client 起動時: backpack 再生ペイロードの受信を登録する (無条件)。 */
    public static void registerClientReceiver() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                BackpackPlayDiscPayload.TYPE,
                (payload, context) -> context.client().execute(
                        () -> SophisticatedCoreCompatClient.play(payload)));
    }

    /**
     * mixin から呼ばれる: storageUuid で識別される backpack の位置で custom disc を
     * ストリーミング再生させる packet を周囲のプレイヤーへ送る。
     *
     * @param entityId backpack を背負う entity の id (固定ストレージなら -1)
     */
    public static void streamToNearby(ServerLevel level, Vec3 soundPos, java.util.UUID storageUuid,
            CustomTrackData track, int entityId) {
        final BlockPos clientPos = BlockPos.containing(soundPos);
        final BackpackPlayDiscPayload payload =
                new BackpackPlayDiscPayload(storageUuid, track, clientPos, entityId);
        for (final ServerPlayer player : PlayerLookup.around(level, soundPos, BROADCAST_RADIUS)) {
            ServerPlayNetworking.send(player, payload);
        }
    }
}
