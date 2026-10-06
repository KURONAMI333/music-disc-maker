package com.kuronami.musicdiscmaker.compat.sophisticatedcore;

import java.lang.reflect.Field;
import java.util.UUID;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.network.ForgeNetwork;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

/**
 * disc handler API 導入前の SC (DeceasedCraft の 1.2.106.1235) のみの補完。
 * 旧 PlayDiscMessage は item ID だけを送り、盤ごとの track を運ばない。
 * 旧 startPlayingDisc に keep-alive 登録を任せた後、既存 MDM payload で実盤を送る。
 * client の旧固定音源は別 hook で抑止し、packet の到着順で実音声を上書きさせない。
 * 新 API の登録経路と通常盤には介入しない。
 */
public final class LegacySophisticatedCoreCompat {

    private static final double BROADCAST_RADIUS = 128.0;
    private static final int FALLBACK_TICKS = 6000;

    private LegacySophisticatedCoreCompat() {
    }

    /** 旧 wrapper の再生 lambda が keep-alive と通常の NBT を設定した後だけ呼ぶ。 */
    public static void afterPlay(Object wrapper, ServerLevel serverLevel, Level playingLevel, UUID storageUuid) {
        if (SophisticatedCoreCompat.hasDiscHandlerApi()) {
            return;
        }
        try {
            final ItemStack disc = (ItemStack) wrapper.getClass().getMethod("getDisc").invoke(wrapper);
            if (!CustomMusicDiscItem.hasTrack(disc)) {
                return;
            }
            final CustomTrackData track = CustomMusicDiscItem.getTrack(disc);
            if (track.isEmpty()) {
                return;
            }
            final Class<?> wrapperType = Class.forName(
                    "net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.JukeboxUpgradeWrapper", false,
                    wrapper.getClass().getClassLoader());
            final Entity entity = (Entity) readField(wrapperType, wrapper, "entityPlaying");
            final BlockPos placedPos = (BlockPos) readField(wrapperType, wrapper, "posPlaying");
            if (entity == null && placedPos == null) {
                return;
            }
            final Vec3 soundPos = entity != null ? entity.position() : Vec3.atCenterOf(placedPos);
            final BlockPos clientPos = entity != null ? BlockPos.containing(soundPos) : placedPos;
            final int entityId = entity != null ? entity.getId() : -1;
            final ItemStack upgrade = (ItemStack) wrapper.getClass().getMethod("getUpgradeStack").invoke(wrapper);
            final int ticks = lengthTicks(track);
            upgrade.getOrCreateTag().putLong("discLength", ticks);
            upgrade.getOrCreateTag().putLong("discFinishTime", playingLevel.getGameTime() + ticks);
            for (var listener : serverLevel.players()) {
                if (listener.distanceToSqr(soundPos) < BROADCAST_RADIUS * BROADCAST_RADIUS) {
                    com.kuronami.musicdiscmaker.audio.local.LocalAudioServer.allow(listener, track);
                }
            }
            ForgeNetwork.CHANNEL.send(
                    PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                            soundPos.x, soundPos.y, soundPos.z, BROADCAST_RADIUS, serverLevel.dimension())),
                    new BackpackPlayDiscPayload(storageUuid, track, clientPos, entityId));
        } catch (ReflectiveOperationException | LinkageError failure) {
            MusicDiscMaker.LOGGER.debug("Legacy Sophisticated Core disc hook unavailable", failure);
        }
    }

    /** 旧 packet の固定 silent 音源だけ抑止。実盤は MDM payload が運ぶ。 */
    public static boolean suppressNativePlay(Object packet) {
        if (SophisticatedCoreCompat.hasDiscHandlerApi()) {
            return false;
        }
        try {
            final Class<?> packetType = Class.forName(
                    "net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.PlayDiscMessage", false,
                    packet.getClass().getClassLoader());
            return (int) readField(packetType, packet, "musicDiscItemId")
                    == Item.getId(ModItems.CUSTOM_MUSIC_DISC.get());
        } catch (ReflectiveOperationException | LinkageError failure) {
            MusicDiscMaker.LOGGER.debug("Legacy Sophisticated Core native hook unavailable", failure);
            return false;
        }
    }

    private static Object readField(Class<?> owner, Object instance, String name) throws ReflectiveOperationException {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static int lengthTicks(CustomTrackData track) {
        final long ms = track.durationMs();
        if (ms <= 0) {
            return FALLBACK_TICKS;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, ms / 50L + (ms % 50L == 0 ? 0L : 1L)));
    }
}
