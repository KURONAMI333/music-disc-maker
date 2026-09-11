package com.kuronami.musicdiscmaker.gametest;

import java.util.List;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlock;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.event.GoldenSourceRegistry;
import com.kuronami.musicdiscmaker.event.GoldenSourceRetirements;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.block.entity.BlockEntity;
//? if >=1.21.2 {
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
//?}
//? if <26.1 {
/*import net.minecraft.world.item.EitherHolder;
*///?}
//? if <1.21.2 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}
public final class GoldenPlaybackStateGameTests {
    private static final BlockPos POS = new BlockPos(1, 1, 1);

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void capturedPlaybackKeepsSelectionAndDoesNotRegisterOrResumeStoppedMedia(GameTestHelper helper) {
        final var source = jukebox(helper);
        final var identity = source.sourceIdentity();
        source.setItem(0, album(helper));
        final CompoundTag captured = source.saveWithFullMetadata(helper.getLevel().registryAccess());
        captured.putInt("albumTrack", 1);
        captured.putInt("playlistTrack", 0);
        captured.putString("playbackState", "PLAYING");
        captured.putLong("playbackStartGameTime", helper.getLevel().getGameTime() - 600L);
        final var playback = com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback
                .read(helper.getLevel(), BlockPos.ZERO, captured).orElseThrow();
        helper.assertTrue(playback.custom() != null && "B".equals(playback.custom().title())
                        && playback.cursor().discIndex() == 1 && playback.cursor().trackIndex() == 0,
                "captured Albumが2枚目の通常盤を保持していない");
        helper.assertTrue(playback.offsetMs() == 30000L && identity.equals(playback.sourceId()),
                "captured playbackが保存した途中位置またはsource IDを失った");
        final var registered = GoldenSourceRegistry.lookup(helper.getLevel(), identity);
        helper.assertTrue(registered.status() == GoldenSourceRegistry.Status.UNIQUE && registered.source() == source,
                "読み取り用のdetached Goldenがsource registryへ侵入した");
        final CompoundTag progressing = captured.copy();
        progressing.putInt("albumTrack", 0);
        progressing.putInt("playlistTrack", 0);
        progressing.putBoolean("repeat", false);
        final var second = com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback
                .advance(helper.getLevel(), BlockPos.ZERO, progressing, 120000L).orElseThrow();
        helper.assertTrue(second.cursor().discIndex() == 1 && second.cursor().trackIndex() == 0
                        && second.offsetMs() == 0 && "B".equals(second.custom().title()),
                "捕獲中のAlbumが次の通常盤へ進まない");
        final CompoundTag progressingAtSecond = progressing.copy();
        progressingAtSecond.putInt("albumTrack", 1);
        final var restored = com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback
                .advance(helper.getLevel(), BlockPos.ZERO, progressingAtSecond, 17500L).orElseThrow();
        helper.assertTrue(restored.offsetMs() == 17500L && restored.sourceId().equals(identity),
                "解体用の保存データへ曲中位置と音源 ID が残らない");
        helper.assertTrue(com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback
                        .advance(helper.getLevel(), BlockPos.ZERO, progressingAtSecond, 120000L).isEmpty(),
                "repeat OFF の捕獲アルバムが末尾で止まらない");
        final CompoundTag looping = captured.copy();
        looping.putBoolean("repeat", true);
        final var looped = com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback
                .advance(helper.getLevel(), BlockPos.ZERO, looping, 120000L).orElseThrow();
        helper.assertTrue(looped.cursor().discIndex() == 0 && looped.cursor().trackIndex() == 0,
                "repeat ON の捕獲アルバムが先頭へ戻らない");
        final var afterAdvance = GoldenSourceRegistry.lookup(helper.getLevel(), identity);
        helper.assertTrue(afterAdvance.status() == GoldenSourceRegistry.Status.UNIQUE && afterAdvance.source() == source,
                "捕獲再生の更新が元のワールド音源を置換した");
        for (String state : List.of("PAUSED", "STOPPED")) {
            captured.putString("playbackState", state);
            helper.assertTrue(com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback
                            .read(helper.getLevel(), BlockPos.ZERO, captured).isEmpty(),
                    "captured " + state + "を組立で再生し直した");
        }
        source.setItem(0, new ItemStack(net.minecraft.world.item.Items.MUSIC_DISC_CAT));
        final CompoundTag vanillaTag = source.saveWithFullMetadata(helper.getLevel().registryAccess());
        final var vanilla = com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback.read(helper.getLevel(),
                BlockPos.ZERO, vanillaTag).orElseThrow();
        helper.assertTrue(vanilla.custom() == null && vanilla.vanilla() != null
                        && "minecraft:music_disc.cat".equals(vanilla.vanilla().soundEventId()),
                "captured vanilla盤をcustomまたは無音盤として誤読した");
        final var midVanilla = com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback.advance(
                helper.getLevel(), BlockPos.ZERO, vanillaTag, 15750L).orElseThrow();
        helper.assertTrue(midVanilla.offsetMs() == 15750L && midVanilla.sourceId().equals(vanilla.sourceId())
                        && midVanilla.cursor().equals(vanilla.cursor()),
                "捕獲バニラ盤の途中位置保存が音源や再生世代を変更した");
        final CompoundTag repeatingVanilla = vanillaTag.copy();
        repeatingVanilla.putBoolean("repeat", true);
        final var repeatedVanilla = com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback.advance(
                helper.getLevel(), BlockPos.ZERO, repeatingVanilla, vanilla.vanilla().durationMs()).orElseThrow();
        helper.assertTrue(repeatedVanilla.offsetMs() == 0L
                        && repeatedVanilla.vanilla().equals(vanilla.vanilla())
                        && repeatedVanilla.cursor().generation() > vanilla.cursor().generation(),
                "捕獲バニラ盤が曲末で新しい再生世代の先頭へリピートしない");
        vanillaTag.putBoolean("repeat", false);
        helper.assertTrue(com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback.advance(
                        helper.getLevel(), BlockPos.ZERO, vanillaTag, vanilla.vanilla().durationMs()).isEmpty(),
                "repeat OFF の捕獲バニラ盤が曲末で止まらない");
        helper.succeed();
    }

    private static GoldenJukeboxBlockEntity jukebox(GameTestHelper helper) {
        helper.setBlock(POS, ModBlocks.GOLDEN_JUKEBOX.get());
        //? if >=1.21.2 {
        return helper.getBlockEntity(POS, GoldenJukeboxBlockEntity.class);
        //?} else {
        /*return helper.getBlockEntity(POS);
        *///?}
    }

    private static ItemStack album(GameTestHelper helper) {
        final CustomTrackData first = new CustomTrackData("https://example.invalid/a", "A", "test", 120_000L, "", false);
        final CustomTrackData last = new CustomTrackData("https://example.invalid/b", "B", "test", 120_000L, "", false);
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        album.set(ModDataComponents.ALBUM_CONTENTS.get(), new AlbumContents(List.of(
                disc(helper, first), disc(helper, last))));
        return album;
    }

    private static ItemStack disc(GameTestHelper helper, CustomTrackData track) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), track);
        final var song = helper.getLevel().registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG)
                .getOrThrow(SilentSongs.pick(track.durationMs()));
        //? if >=26.1 {
        disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(song));
        //?} elif >=1.21.2 {
        /*disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(new EitherHolder<>(song)));
        *///?}
        //? if <1.21.2 {
        /*disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(new EitherHolder<>(song), false));
        *///?}
        return disc;
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void clearContentStopsPlayback(GameTestHelper helper) {
        final var be = jukebox(helper);
        be.setItem(0, album(helper));
        helper.assertTrue(be.isVanillaPlaying(), "test disc did not start");
        final long generation = be.playbackCursor().generation();
        be.clearContent();
        helper.assertTrue(!be.hasDisc(), "clearContent left an item");
        helper.assertTrue(!be.isVanillaPlaying(), "clearContent left playback active");
        helper.assertTrue(!be.getBlockState().getValue(GoldenJukeboxBlock.HAS_RECORD), "clearContent left HAS_RECORD set");
        helper.assertTrue(be.isStopped() && !be.playbackCursor().hasPosition(), "clearContent retained the cursor");
        helper.assertTrue(be.playbackCursor().generation() > generation, "clearContent reused the playback generation");
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void albumEndRestartsWithOneResume(GameTestHelper helper) {
        final var be = jukebox(helper);
        be.setItem(0, album(helper));
        // Seek reaches the boundary deterministically; no wall-clock sleep or remote audio is needed.
        be.seekTo(120_000L);
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
        helper.assertTrue(be.playbackCursor().discIndex() == 1, "first Album disc did not advance");
        be.seekTo(120_000L);
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
        helper.assertTrue(!be.isVanillaPlaying(), "Album did not stop at the tail");
        be.seekTo(0L); // 停止画面の再生ボタンと同じ経路。
        helper.assertTrue(be.isVanillaPlaying(), "one resume did not restart the ended Album");
        helper.assertTrue(be.playbackCursor().discIndex() == 0, "replay did not select the first disc");
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void slowTicksKeepAudioOffsetForSpeakersAndReload(GameTestHelper helper) {
        final var be = jukebox(helper);
        be.setItem(0, album(helper));
        be.seekTo(30_000L);
        // Simulate a tick clock that advanced less than the real audio clock, without changing world time.
        try {
            final var gameOrigin = GoldenJukeboxBlockEntity.class.getDeclaredField("playbackStartGameTime");
            gameOrigin.setAccessible(true);
            gameOrigin.setLong(be, helper.getLevel().getGameTime());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        helper.assertTrue(be.currentElapsedMs() >= 30_000L && be.currentElapsedMs() < 31_000L,
                "Speaker offset used slow world ticks instead of actual audio time");
        final var restored = reload(helper, be, false);
        helper.assertTrue(restored.currentElapsedMs() >= 29_950L && restored.currentElapsedMs() < 31_000L,
                "Reload lost the actual audio offset during slow ticks");
        helper.succeed();
    }

    private static GoldenJukeboxBlockEntity reload(GameTestHelper helper, GoldenJukeboxBlockEntity be,
            boolean legacy) {
        final CompoundTag saved = be.saveWithFullMetadata(helper.getLevel().registryAccess());
        if (legacy) {
            saved.remove("playbackState");
            saved.remove("playbackGeneration");
        }
        be.onBlockRemoved();
        final var restored = (GoldenJukeboxBlockEntity) BlockEntity.loadStatic(
                be.getBlockPos(), be.getBlockState(), saved, helper.getLevel().registryAccess());
        helper.assertTrue(restored != null, "saved jukebox could not be restored");
        restored.setLevel(helper.getLevel());
        helper.getLevel().setBlockEntity(restored);
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), restored.getBlockPos(), restored.getBlockState(), restored);
        return restored;
    }

    private static void finishAlbum(GameTestHelper helper, GoldenJukeboxBlockEntity be) {
        for (int i = 0; i < 2; i++) {
            be.seekTo(120_000L);
            GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
        }
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void stoppedAlbumStaysStoppedAfterReload(GameTestHelper helper) {
        final var be = jukebox(helper);
        be.setItem(0, album(helper));
        finishAlbum(helper, be);
        final var restored = reload(helper, be, false);
        helper.assertTrue(restored.hasDisc() && restored.isStopped(), "reload restarted a stopped Album");
        helper.assertTrue(!restored.isVanillaPlaying(), "reload started the silent song");
        restored.seekTo(0L);
        helper.assertTrue(restored.isVanillaPlaying(), "restored stopped Album could not be replayed");
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void pausedAlbumKeepsDiscAndOffsetAfterReload(GameTestHelper helper) {
        verifyPausedReload(helper, false);
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void legacyPausedAlbumKeepsDiscAndOffset(GameTestHelper helper) {
        verifyPausedReload(helper, true);
    }

    private static void verifyPausedReload(GameTestHelper helper, boolean legacy) {
        final var be = jukebox(helper);
        be.setItem(0, album(helper));
        be.seekTo(120_000L);
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
        be.seekTo(30_000L);
        be.setPaused(true);
        final long offset = be.currentElapsedMs();
        final var restored = reload(helper, be, legacy);
        helper.assertTrue(restored.isPaused() && !restored.isVanillaPlaying(), "reload resumed a paused Album");
        helper.assertTrue(restored.playbackCursor().discIndex() == 1, "reload lost the selected disc");
        helper.assertTrue(restored.currentElapsedMs() == offset, "reload lost the paused offset");
        restored.setPaused(false);
        helper.assertTrue(restored.isVanillaPlaying() && restored.playbackCursor().discIndex() == 1,
                "resume lost the selected disc");
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void legacyStoppedAlbumDoesNotReplayOnLoad(GameTestHelper helper) {
        final var be = jukebox(helper);
        be.setItem(0, album(helper));
        finishAlbum(helper, be);
        final var restored = reload(helper, be, true);
        helper.assertTrue(restored.isStopped() && !restored.isVanillaPlaying(), "legacy stopped data replayed on load");
        helper.succeed();
    }
    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void sourceIdentitySurvivesRelocationAndLegacyData(GameTestHelper helper) {
        final var source = jukebox(helper);
        source.setVolumePercent(73);
        final java.util.UUID identity = source.sourceIdentity();
        helper.assertTrue(identity.equals(source.sourceIdentity()), "source ID changed on repeated lookup");
        final CompoundTag saved = source.saveWithFullMetadata(helper.getLevel().registryAccess());
        final BlockPos movedPos = source.getBlockPos().offset(3, 0, 0);
        final var moved = (GoldenJukeboxBlockEntity) BlockEntity.loadStatic(movedPos, source.getBlockState(), saved, helper.getLevel().registryAccess());
        helper.assertTrue(moved != null && identity.equals(moved.peekSourceIdentity()), "source ID did not survive relocation");
        moved.setLevel(helper.getLevel());
        helper.assertTrue(identity.equals(moved.sourceIdentity()), "relocation generated a new identity");
        helper.assertTrue(moved.getVolumePercent() == 73, "identity persistence changed existing volume");
        for (boolean malformed : new boolean[] {false, true}) {
            final CompoundTag legacy = saved.copy();
            legacy.remove("source_id");
            if (malformed) legacy.putString("source_id", "invalid-uuid");
            final var restored = (GoldenJukeboxBlockEntity) BlockEntity.loadStatic(movedPos, source.getBlockState(), legacy, helper.getLevel().registryAccess());
            helper.assertTrue(restored != null && restored.peekSourceIdentity() == null, "old/invalid data invented a source ID during load");
            boolean rejected = false;
            try { restored.sourceIdentity(); } catch (IllegalStateException expected) { rejected = true; }
            helper.assertTrue(rejected && restored.peekSourceIdentity() == null, "detached object generated a server ID");
            restored.setLevel(helper.getLevel());
            final java.util.UUID assigned = restored.sourceIdentity();
            helper.assertTrue(assigned != null && assigned.equals(restored.sourceIdentity()), "legacy identity was not stable");
        }
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void sourceRegistryTracksConflictRemovalAndReload(GameTestHelper helper) {
        final BlockPos secondPos = new BlockPos(4, 1, 1);
        final GoldenJukeboxBlockEntity first = jukebox(helper);
        helper.setBlock(secondPos, ModBlocks.GOLDEN_JUKEBOX.get());
        //? if >=1.21.2 {
        final GoldenJukeboxBlockEntity second = helper.getBlockEntity(secondPos, GoldenJukeboxBlockEntity.class);
        //?} else {
        /*final GoldenJukeboxBlockEntity second = helper.getBlockEntity(secondPos);
        *///?}
        helper.assertTrue(first != null && second != null, "registry試験の実world Golden 2台を生成できていない");

        final java.util.UUID sharedId = first.sourceIdentity();
        final CompoundTag sharedState = first.saveWithFullMetadata(helper.getLevel().registryAccess());
        loadInstalled(helper, second, sharedState);
        GoldenSourceRetirements.get(helper.getLevel()).recordRemoval(sharedId, first.getBlockPos());
        final GoldenSourceRegistry.Resolution conflict = GoldenSourceRegistry.lookup(helper.getLevel(), sharedId);
        helper.assertTrue(conflict.status() == GoldenSourceRegistry.Status.CONFLICT && conflict.source() == null,
                "同じsource IDの実world Golden 2台をCONFLICTとして検出しない");
        helper.assertTrue(GoldenSourceRetirements.get(helper.getLevel()).removedAt(first.getBlockPos()).contains(sharedId),
                "所有者がCONFLICTの間に破壊記録を消してしまった");

        second.setRemoved();
        final GoldenSourceRegistry.Resolution firstOnly = GoldenSourceRegistry.lookup(helper.getLevel(), sharedId);
        helper.assertTrue(firstOnly.status() == GoldenSourceRegistry.Status.UNIQUE && firstOnly.source() == first,
                "片方removed後に残ったGoldenをUNIQUEとして解決しない");
        helper.assertTrue(!GoldenSourceRetirements.get(helper.getLevel()).removedAt(first.getBlockPos()).contains(sharedId),
                "所有者がUNIQUEに復帰しても破壊記録を消していない");
        first.setRemoved();
        final GoldenSourceRegistry.Resolution none = GoldenSourceRegistry.lookup(helper.getLevel(), sharedId);
        helper.assertTrue(none.status() == GoldenSourceRegistry.Status.UNKNOWN && none.source() == null,
                "両方removed後もsource IDがUNKNOWNにならない");

        second.clearRemoved();
        final GoldenSourceRegistry.Resolution secondOnly = GoldenSourceRegistry.lookup(helper.getLevel(), sharedId);
        helper.assertTrue(secondOnly.status() == GoldenSourceRegistry.Status.UNIQUE && secondOnly.source() == second,
                "clearRemoved後の実world GoldenをUNIQUEとして再登録しない");
        final java.util.UUID absentId = java.util.UUID.randomUUID();
        helper.assertTrue(GoldenSourceRegistry.lookup(helper.getLevel(), absentId).status()
                        == GoldenSourceRegistry.Status.UNKNOWN,
                "未登録の別source IDをUNKNOWNとして返さない");

        final java.util.UUID replacementId = java.util.UUID.randomUUID();
        final CompoundTag replacementState = sharedState.copy();
        replacementState.putString("source_id", replacementId.toString());
        loadInstalled(helper, second, replacementState);
        helper.assertTrue(GoldenSourceRegistry.lookup(helper.getLevel(), sharedId).status()
                        == GoldenSourceRegistry.Status.UNKNOWN,
                "実world BEへのNBT reload後も旧source ID登録が残った");
        final GoldenSourceRegistry.Resolution replacement =
                GoldenSourceRegistry.lookup(helper.getLevel(), replacementId);
        helper.assertTrue(replacement.status() == GoldenSourceRegistry.Status.UNIQUE
                        && replacement.source() == second,
                "実world BEへのNBT reload後に新source IDへ登録されない");
        final Object actor = new Object();
        final Object duplicateActor = new Object();
        try {
            GoldenSourceRegistry.registerMoving(helper.getLevel(), sharedId, actor);
            GoldenSourceRegistry.registerMoving(helper.getLevel(), sharedId, actor);
            helper.assertTrue(GoldenSourceRegistry.lookup(helper.getLevel(), sharedId).status()
                            == GoldenSourceRegistry.Status.MOVING,
                    "Captured source was lost or duplicate registration created a conflict");
            GoldenSourceRetirements.get(helper.getLevel()).recordRemoval(sharedId, second.getBlockPos());
            com.kuronami.musicdiscmaker.event.SpeakerNetwork.findLinkedSpeakers(helper.getLevel(), second);
            helper.assertTrue(!GoldenSourceRetirements.get(helper.getLevel()).predecessorsOf(replacementId).contains(sharedId),
                    "A replacement world block claimed the moving source's speaker links");
            GoldenSourceRegistry.registerMoving(helper.getLevel(), sharedId, duplicateActor);
            helper.assertTrue(GoldenSourceRegistry.lookup(helper.getLevel(), sharedId).status()
                            == GoldenSourceRegistry.Status.CONFLICT, "Duplicate moving identities were not rejected");
            GoldenSourceRegistry.unregisterMoving(helper.getLevel(), duplicateActor);
            GoldenSourceRegistry.unregisterMoving(helper.getLevel(), actor);
            helper.assertTrue(GoldenSourceRegistry.lookup(helper.getLevel(), sharedId).status()
                            == GoldenSourceRegistry.Status.UNKNOWN, "Stopped actor retained moving ownership");
        } finally {
            GoldenSourceRegistry.unregisterMoving(helper.getLevel(), actor);
            GoldenSourceRegistry.unregisterMoving(helper.getLevel(), duplicateActor);
        }
        helper.succeed();
    }

    private static void loadInstalled(GameTestHelper helper, GoldenJukeboxBlockEntity target, CompoundTag state) {
        //? if >=1.21.2 {
        target.loadWithComponents(TagValueInput.create(
                ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), state));
        //?} else {
        /*target.loadWithComponents(state, helper.getLevel().registryAccess());
        *///?}
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void sourceRetirementsRoundTripAndRevive(GameTestHelper helper) {
        final java.util.UUID firstId = java.util.UUID.randomUUID();
        final java.util.UUID secondId = java.util.UUID.randomUUID();
        final BlockPos oldPosition = helper.absolutePos(new BlockPos(1, 1, 1));
        final BlockPos newPosition = helper.absolutePos(new BlockPos(4, 1, 1));
        final GoldenSourceRetirements data = new GoldenSourceRetirements();
        data.recordRemoval(firstId, oldPosition);
        data.recordRemoval(secondId, oldPosition);

        GoldenSourceRetirements restored = retirementRoundTrip(helper, data);
        helper.assertTrue(restored.removedAt(oldPosition).equals(java.util.Set.of(firstId, secondId)),
                "retirement CODEC往復で同位置の2 UUIDを保持しない");
        restored.forget(firstId);
        helper.assertTrue(restored.removedAt(oldPosition).equals(java.util.Set.of(secondId)),
                "片方forgetが同位置の残りUUIDまで消した");
        restored.recordRemoval(secondId, newPosition);
        helper.assertTrue(restored.removedAt(oldPosition).isEmpty()
                        && restored.removedAt(newPosition).equals(java.util.Set.of(secondId)),
                "同じUUIDの再記録で旧位置を空にして新位置へ移さない");

        restored = retirementRoundTrip(helper, restored);
        helper.assertTrue(restored.removedAt(oldPosition).isEmpty()
                        && restored.removedAt(newPosition).equals(java.util.Set.of(secondId)),
                "再配置後のretirementが2回目のCODEC往復で変化した");
        final CompoundTag malformed = (CompoundTag) GoldenSourceRetirements.CODEC
                .encodeStart(NbtOps.INSTANCE, restored).result().orElse(null);
        helper.assertTrue(malformed != null, "不正UUID混入試験用retirementをencodeできない");
        //? if >=1.21.2 {
        final CompoundTag retired = malformed.getCompound("retired").orElse(null);
        //?} else {
        /*final CompoundTag retired = malformed.getCompound("retired");
        *///?}
        helper.assertTrue(retired != null, "encoded retirementにretired mapが無い");
        retired.putLong("invalid-uuid", oldPosition.asLong());
        final GoldenSourceRetirements tolerant = GoldenSourceRetirements.CODEC
                .parse(NbtOps.INSTANCE, malformed).result().orElse(null);
        helper.assertTrue(tolerant != null
                        && tolerant.removedAt(oldPosition).isEmpty()
                        && tolerant.removedAt(newPosition).equals(java.util.Set.of(secondId)),
                "不正UUIDキーが合法retirementを壊した、または不正記録を採用した");

        final java.util.UUID generationA = java.util.UUID.randomUUID();
        final java.util.UUID generationB = java.util.UUID.randomUUID();
        final java.util.UUID generationC = java.util.UUID.randomUUID();
        final java.util.UUID unrelatedD = java.util.UUID.randomUUID();
        GoldenSourceRetirements aliases = new GoldenSourceRetirements();
        aliases.recordRemoval(generationA, oldPosition);
        aliases.claimReplacement(generationA, generationB);
        aliases = retirementRoundTrip(helper, aliases);
        helper.assertTrue(aliases.predecessorsOf(generationB).equals(java.util.Set.of(generationA))
                        && aliases.unclaimedAt(oldPosition).isEmpty(),
                "A→B claimがCODEC往復後にpredecessorまたは位置claimを失った");

        aliases.claimReplacement(generationA, unrelatedD);
        helper.assertTrue(aliases.predecessorsOf(generationB).contains(generationA)
                        && !aliases.predecessorsOf(unrelatedD).contains(generationA),
                "同じretired UUIDの二重claimが最初のA→Bを上書きした");
        aliases.recordRemoval(generationB, newPosition);
        aliases.claimReplacement(generationB, generationA);
        helper.assertTrue(aliases.unclaimedAt(newPosition).contains(generationB)
                        && !aliases.predecessorsOf(generationA).contains(generationB),
                "A→Bがある状態でcycle B→Aを受理した");
        aliases.claimReplacement(generationB, generationC);
        aliases = retirementRoundTrip(helper, aliases);
        helper.assertTrue(aliases.predecessorsOf(generationC)
                        .equals(java.util.Set.of(generationA, generationB)),
                "A→B→C claimがCODEC往復後に推移的predecessorを失った");

        aliases.forget(generationB);
        helper.assertTrue(aliases.predecessorsOf(generationB).contains(generationA)
                        && aliases.predecessorsOf(generationC).isEmpty(),
                "forget(B)がA→Bまで消した、またはCからA/Bを切り離していない");

        final GoldenSourceRetirements legacyData = new GoldenSourceRetirements();
        legacyData.recordRemoval(generationA, oldPosition);
        final CompoundTag legacyEncoded = (CompoundTag) GoldenSourceRetirements.CODEC
                .encodeStart(NbtOps.INSTANCE, legacyData).result().orElse(null);
        helper.assertTrue(legacyEncoded != null, "successors欠落旧dataのencodeに失敗した");
        legacyEncoded.remove("successors");
        final GoldenSourceRetirements legacyDecoded = GoldenSourceRetirements.CODEC
                .parse(NbtOps.INSTANCE, legacyEncoded).result().orElse(null);
        helper.assertTrue(legacyDecoded != null
                        && legacyDecoded.removedAt(oldPosition).contains(generationA)
                        && legacyDecoded.predecessorsOf(generationB).isEmpty(),
                "successorsキーの無い旧retirement dataを読めない");
        helper.succeed();
    }

    private static GoldenSourceRetirements retirementRoundTrip(
            GameTestHelper helper, GoldenSourceRetirements original) {
        final var encoded = GoldenSourceRetirements.CODEC
                .encodeStart(NbtOps.INSTANCE, original).result().orElse(null);
        helper.assertTrue(encoded != null, "retirement CODEC encodeに失敗した");
        final GoldenSourceRetirements decoded = GoldenSourceRetirements.CODEC
                .parse(NbtOps.INSTANCE, encoded).result().orElse(null);
        helper.assertTrue(decoded != null, "retirement CODEC decodeに失敗した");
        return decoded;
    }


}
