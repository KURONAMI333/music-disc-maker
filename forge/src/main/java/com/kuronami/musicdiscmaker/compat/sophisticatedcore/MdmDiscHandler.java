package com.kuronami.musicdiscmaker.compat.sophisticatedcore;

import java.util.Optional;
import java.util.UUID;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.network.ForgeNetwork;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import net.p3pp3rf1y.sophisticatedcore.api.IDiscHandler;
import net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.ServerStorageSoundHandler;

/**
 * Sophisticated Core の {@code IDiscHandler} 実装 (Forge 1.20.1)。MDM の custom disc を SB の
 * Jukebox Upgrade で受け付け・再生できるようにする。1.20.1 は曲メタを NBT で持つので
 * {@link CustomMusicDiscItem} 経由で扱う。
 *
 * <p>再生は MDM の LavaPlayer 経路 ({@link BackpackPlayDiscPayload} → client DiscSoundInstance)。
 * 終了/停止/keep-alive は SC の {@link ServerStorageSoundHandler} に委譲するので、SB 側の停止操作が
 * そのまま MDM の音声を止める。
 */
public class MdmDiscHandler implements IDiscHandler<CustomTrackData> {

    /** durationMs を取れないストリーム (ライブ等) の暫定尺。 */
    private static final int FALLBACK_TICKS = 6000; // 5 分
    private static final double BROADCAST_RADIUS = 128.0;

    @Override
    public Optional<CustomTrackData> getSongInfo(ItemStack itemStack, Level level) {
        if (!CustomMusicDiscItem.hasTrack(itemStack)) {
            return Optional.empty();
        }
        final CustomTrackData track = CustomMusicDiscItem.getTrack(itemStack);
        return track.isEmpty() ? Optional.empty() : Optional.of(track);
    }

    @Override
    public boolean supports(ItemStack itemStack) {
        return itemStack.is(ModItems.CUSTOM_MUSIC_DISC.get()) && CustomMusicDiscItem.hasTrack(itemStack);
    }

    @Override
    public Optional<Integer> getMusicLengthInTicks(ItemStack itemStack, Level level) {
        return getSongInfo(itemStack, level).map(MdmDiscHandler::lengthTicks);
    }

    @Override
    public void playDisc(ServerLevel serverLevel, BlockPos position, UUID storageUuid, ItemStack discItemStack, Runnable onFinished) {
        playAt(serverLevel, Vec3.atCenterOf(position), position, -1, storageUuid, discItemStack, onFinished);
    }

    @Override
    public void playDisc(ServerLevel serverLevel, Vec3 position, UUID storageUuid, ItemStack discItemStack, int entityId, Runnable onFinished) {
        playAt(serverLevel, position, BlockPos.containing(position), entityId, storageUuid, discItemStack, onFinished);
    }

    private void playAt(ServerLevel serverLevel, Vec3 soundPos, BlockPos clientPos, int entityId, UUID storageUuid, ItemStack disc, Runnable onFinished) {
        getSongInfo(disc, serverLevel).ifPresent(track -> {
            ServerStorageSoundHandler.putSoundInfo(serverLevel, storageUuid, onFinished, soundPos,
                    serverLevel.getGameTime() + lengthTicks(track));
            ForgeNetwork.CHANNEL.send(
                    PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                            soundPos.x, soundPos.y, soundPos.z, BROADCAST_RADIUS, serverLevel.dimension())),
                    new BackpackPlayDiscPayload(storageUuid, track, clientPos, entityId));
        });
    }

    @Override
    public Optional<ItemStack> getRandomDisc(RandomSource randomSource) {
        return Optional.empty();
    }

    @Override
    public int getMusicDiscSize() {
        return 0;
    }

    private static int lengthTicks(CustomTrackData track) {
        final long ms = track.durationMs();
        return ms > 0 ? (int) (ms / 50) : FALLBACK_TICKS;
    }
}
