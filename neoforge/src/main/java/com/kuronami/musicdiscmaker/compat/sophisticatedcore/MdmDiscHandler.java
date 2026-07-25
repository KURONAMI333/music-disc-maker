package com.kuronami.musicdiscmaker.compat.sophisticatedcore;

import java.util.Optional;
import java.util.UUID;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.p3pp3rf1y.sophisticatedcore.api.IDiscHandler;
import net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.ServerStorageSoundHandler;

/**
 * Sophisticated Core の {@code IDiscHandler} 実装。MDM の custom disc を SB の Jukebox Upgrade で
 * 受け付け・再生できるようにする。
 *
 * <p>再生は MDM の LavaPlayer 経路 ({@link BackpackPlayDiscPayload} → client DiscSoundInstance)。
 * 終了/停止/keep-alive のライフサイクルは SC の {@link ServerStorageSoundHandler} に委譲するので、
 * SB 側の停止操作 (ディスク取り出し・upgrade 撤去) がそのまま MDM の音声を止める。
 */
public class MdmDiscHandler implements IDiscHandler<CustomTrackData> {

    /** durationMs を取れないストリーム (ライブ等) の暫定尺。これを過ぎると SC が次曲へ送る。 */
    private static final int FALLBACK_TICKS = 6000; // 5 分
    private static final double BROADCAST_RADIUS = 128.0;

    @Override
    public Optional<CustomTrackData> getSongInfo(ItemStack itemStack, Level level) {
        final CustomTrackData track = itemStack.get(ModDataComponents.CUSTOM_TRACK.get());
        return (track != null && !track.isEmpty()) ? Optional.of(track) : Optional.empty();
    }

    @Override
    public boolean supports(ItemStack itemStack) {
        return itemStack.is(ModItems.CUSTOM_MUSIC_DISC.get()) && itemStack.has(ModDataComponents.CUSTOM_TRACK.get());
    }

    @Override
    public Optional<Integer> getMusicLengthInTicks(ItemStack itemStack, Level level) {
        return getSongInfo(itemStack, level).map(MdmDiscHandler::lengthTicks);
    }

    @Override
    public void playDisc(ServerLevel serverLevel, BlockPos position, UUID storageUuid, ItemStack discItemStack, Runnable onFinished) {
        // 設置ストレージ等、固定位置の再生源。entityId=-1 で client は pos を使う。
        playAt(serverLevel, Vec3.atCenterOf(position), position, -1, storageUuid, discItemStack, onFinished);
    }

    @Override
    public void playDisc(ServerLevel serverLevel, Vec3 position, UUID storageUuid, ItemStack discItemStack, int entityId, Runnable onFinished) {
        // backpack を背負うプレイヤー等。entityId を渡し、client は音源をその entity に追従させる。
        playAt(serverLevel, position, BlockPos.containing(position), entityId, storageUuid, discItemStack, onFinished);
    }

    private void playAt(ServerLevel serverLevel, Vec3 soundPos, BlockPos clientPos, int entityId, UUID storageUuid, ItemStack disc, Runnable onFinished) {
        getSongInfo(disc, serverLevel).ifPresent(track -> {
            final int ticks = lengthTicks(track);
            // SC のサウンドライフサイクルに登録: finishTime 経過で onFinished (playlist 進行)、keep-alive 失効や
            // 明示停止で SC が StopDiscPlaybackPayload を送る → client 側で MDM の DiscSoundInstance も止まる。
            ServerStorageSoundHandler.putSoundInfo(serverLevel, storageUuid, onFinished, soundPos,
                    serverLevel.getGameTime() + ticks);
            PacketDistributor.sendToPlayersNear(serverLevel, null, soundPos.x, soundPos.y, soundPos.z, BROADCAST_RADIUS,
                    new BackpackPlayDiscPayload(storageUuid, track, clientPos, entityId));
        });
    }

    @Override
    public Optional<ItemStack> getRandomDisc(RandomSource randomSource) {
        return Optional.empty(); // MDM は random disc を提供しない
    }

    @Override
    public int getMusicDiscSize() {
        return 0; // random disc 抽選の重みに寄与しない
    }

    private static int lengthTicks(CustomTrackData track) {
        final long ms = track.durationMs();
        return ms > 0 ? (int) (ms / 50) : FALLBACK_TICKS;
    }
}
