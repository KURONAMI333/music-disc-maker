package com.kuronami.musicdiscmaker.component;

import java.util.Optional;
import java.util.UUID;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** 組立時の保存データを通常Goldenと同じ復元規則で読む。worldへのBE登録や再生は行わない。 */
public record CapturedGoldenPlayback(UUID sourceId, PlaybackCursor cursor, CustomTrackData custom,
        VanillaTrackData vanilla, long offsetMs, int range, int volume, boolean directional) {

    public static UUID sourceIdentity(ServerLevel level, BlockPos localPos, CompoundTag tag) {
        return load(level, localPos, tag).peekSourceIdentity();
    }

    public static Optional<CapturedGoldenPlayback> advance(ServerLevel level, BlockPos localPos,
            CompoundTag tag, long elapsedMs) {
        final GoldenJukeboxBlockEntity detached = load(level, localPos, tag);
        final VanillaTrackData vanilla = detached.currentTrack() == null
                ? vanillaTrack(level, detached.effectiveDisc()) : null;
        detached.advanceCapturedPlayback(tag, level.getGameTime(), elapsedMs, vanilla);
        return read(level, localPos, tag);
    }

    public static Optional<CapturedGoldenPlayback> read(ServerLevel level, BlockPos localPos, CompoundTag tag) {
        final GoldenJukeboxBlockEntity detached = load(level, localPos, tag);
        //? if >=1.21.2 {
        final long start = tag.getLong("playbackStartGameTime").orElse(-1L);
        //?} else {
        /*final long start = tag.contains("playbackStartGameTime") ? tag.getLong("playbackStartGameTime") : -1L;
        *///?}

        if (detached.playbackCursor().state() != PlaybackCursor.State.PLAYING) return Optional.empty();
        final CustomTrackData custom = detached.currentTrack();
        final VanillaTrackData vanilla = custom == null ? vanillaTrack(level, detached.effectiveDisc()) : null;
        if (custom == null && vanilla == null) return Optional.empty();
        final long duration = custom != null ? custom.durationMs() : vanilla.durationMs();
        // 負の起点も、新しいworldで途中位置を保存した正当なPLAYING状態なら有効。
        final long elapsed = Math.max(0L, (level.getGameTime() - start) * 50L);
        final long offset = duration > 0L ? Math.min(elapsed, duration) : elapsed;
        return Optional.of(new CapturedGoldenPlayback(detached.peekSourceIdentity(), detached.playbackCursor(),
                custom, vanilla, offset, detached.getRangeBlocks(), detached.getVolumePercent(), detached.isDirectional()));
    }

    private static GoldenJukeboxBlockEntity load(ServerLevel level, BlockPos localPos, CompoundTag tag) {
        final GoldenJukeboxBlockEntity detached = new GoldenJukeboxBlockEntity(localPos,
                ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        //? if >=1.21.2 {
        detached.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), tag));
        //?} elif >=1.21 {
        /*detached.loadWithComponents(tag, level.registryAccess());
        *///?} else {
        /*detached.load(tag);
        *///?}
        return detached;
    }

    private static VanillaTrackData vanillaTrack(ServerLevel level, ItemStack disc) {
        if (disc.isEmpty() || disc.is(ModItems.CUSTOM_MUSIC_DISC.get())) return null;
        //? if >=26.1 {
        final var song = net.minecraft.world.item.JukeboxSong.fromStack(disc);
        //?} elif >=1.21 {
        /*final var song = net.minecraft.world.item.JukeboxSong.fromStack(level.registryAccess(), disc);
        *///?} else {
        /*if (!(disc.getItem() instanceof net.minecraft.world.item.RecordItem record)) return null;
        return new VanillaTrackData(record.getSound().getLocation().toString(), record.getLengthInTicks() * 50L);
        *///?}
        //? if >=1.21 {
        return song.map(holder -> {
            //? if >=1.21.11 {
            final String id = holder.value().soundEvent().value().location().toString();
            //?} else {
            /*final String id = holder.value().soundEvent().value().getLocation().toString();
            *///?}
            return new VanillaTrackData(id, holder.value().lengthInTicks() * 50L);
        }).orElse(null);
        //?}
    }
}
