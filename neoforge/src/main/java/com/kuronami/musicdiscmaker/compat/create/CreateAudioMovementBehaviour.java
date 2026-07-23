package com.kuronami.musicdiscmaker.compat.create;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * MDM 音源ブロック (強化版ジュークボックス) が Create 捕獲式 contraption に組み込まれた時、凍結された
 * BlockEntity データから再生中の custom disc を読み取り、contraption を追跡中の client へ
 * {@link ContraptionPlayDiscPayload} を送る。client は {@link ContraptionAnchor} で音源を追従再生する。
 *
 * <p>捕獲式では組立時に原ブロックが world から抜かれ AIR 化されるため、固定 BlockPos に張り付いた
 * 従来の {@code StaticAnchor} 再生は自己消音する。この behaviour が「組立 = startMoving」を契機に
 * contraption 追従の再生へ引き継ぐ。server 主導 (client では tick を要求しない)。
 */
public final class CreateAudioMovementBehaviour implements MovementBehaviour {

    @Override
    public void startMoving(MovementContext context) {
        // server 側のみ: 凍結 BE から track を読み、client へ contraption 追従再生を指示する。
        if (context.world == null || context.world.isClientSide()) {
            return;
        }
        final CompoundTag beData = context.blockEntityData;
        if (beData == null || beData.isEmpty()) {
            return;
        }
        final CustomTrackData track = readTrack(context, beData);
        if (track == null || track.isEmpty()) {
            return; // ディスク無し / vanilla ディスク / 空 track
        }
        final long offsetMs = readOffsetMs(context, beData, track);
        final int range = beData.contains("range") ? beData.getInt("range") : GoldenJukeboxBlockEntity.RANGE_DEFAULT;
        final int volume = beData.contains("volume") ? beData.getInt("volume") : GoldenJukeboxBlockEntity.VOLUME_DEFAULT;

        final ContraptionPlayDiscPayload payload = new ContraptionPlayDiscPayload(
                context.contraption.entity.getId(), context.localPos, track, offsetMs, range, volume);
        PacketDistributor.sendToPlayersTrackingEntity(context.contraption.entity, payload);
    }

    /** 凍結 BE の "inventory" から disc を復元し custom track を取り出す。custom disc でなければ null。 */
    private static CustomTrackData readTrack(MovementContext context, CompoundTag beData) {
        if (!beData.contains("inventory")) {
            return null;
        }
        final NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(beData.getCompound("inventory"), items, context.world.registryAccess());
        final ItemStack disc = items.get(GoldenJukeboxBlockEntity.SLOT_DISC);
        if (disc.is(ModItems.CUSTOM_MUSIC_DISC.get()) && disc.has(ModDataComponents.CUSTOM_TRACK.get())) {
            final CustomTrackData track = disc.get(ModDataComponents.CUSTOM_TRACK.get());
            return track != null && !track.isEmpty() ? track : null;
        }
        return null;
    }

    /**
     * 凍結時点の再生経過 (ms)。{@link GoldenJukeboxBlockEntity#currentElapsedMs()} と同じ計算を凍結
     * NBT から行う: 一時停止中は保存 offset、再生中は (現 gameTime - playbackStartGameTime)×50 を尺で
     * クランプ。
     */
    private static long readOffsetMs(MovementContext context, CompoundTag beData, CustomTrackData track) {
        if (beData.getBoolean("paused")) {
            return Math.max(0L, beData.getLong("pausedOffset"));
        }
        final long startGameTime = beData.contains("playbackStartGameTime")
                ? beData.getLong("playbackStartGameTime") : -1L;
        if (startGameTime < 0L) {
            return 0L;
        }
        long elapsed = Math.max(0L, (context.world.getGameTime() - startGameTime) * 50L);
        final long dur = track.durationMs();
        if (dur > 0L) {
            elapsed = Math.min(elapsed, dur);
        }
        return elapsed;
    }
}
