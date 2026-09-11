package com.kuronami.musicdiscmaker.gametest;

import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.color.DiscDye;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.DiscDyeData;
import com.kuronami.musicdiscmaker.component.VanillaTrackData;
import com.kuronami.musicdiscmaker.event.GoldenSourceRegistry;
import com.kuronami.musicdiscmaker.event.GoldenSourceRetirements;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Forge 1.20.1 の実 registry と BlockEntity/NBT 経路を通す回帰試験。 */
@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
public final class GoldenLegacyPlaybackGameTests {
    private static final BlockPos POS = new BlockPos(1, 1, 1);
    private static final long TRACK_MS = 1_000L;

    private GoldenLegacyPlaybackGameTests() {
    }

    private static GoldenJukeboxBlockEntity jukebox(GameTestHelper helper) {
        helper.setBlock(POS, ModBlocks.GOLDEN_JUKEBOX.get());
        return (GoldenJukeboxBlockEntity) helper.getBlockEntity(POS);
    }

    private static ItemStack customDisc() {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        final CustomTrackData first = track("https://example.invalid/a", "A");
        CustomMusicDiscItem.setTrack(disc, first);
        return disc;
    }

    private static CustomTrackData track(String url, String title) {
        return new CustomTrackData(url, title, "GameTest", TRACK_MS, "", false);
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void vanillaTrackDescriptorUsesRecordSoundAndDuration(GameTestHelper helper) {
        final GoldenJukeboxBlockEntity be = jukebox(helper);
        be.setItem(0, new ItemStack(Items.MUSIC_DISC_13));

        final VanillaTrackData track = be.currentVanillaTrack();
        helper.assertTrue(track != null, "vanilla record did not produce a playback descriptor");
        helper.assertTrue("minecraft:music_disc.13".equals(track.soundEventId()),
                "vanilla record produced the wrong SoundEvent id: " + track.soundEventId());
        helper.assertTrue(track.durationMs() == be.trackDurationMs(),
                "descriptor duration differs from the existing duration path");

        be.setItem(0, customDisc());
        helper.assertTrue(be.currentVanillaTrack() == null,
                "custom disc silent song was exposed as a vanilla playback descriptor");
        be.setItem(0, ItemStack.EMPTY);
        helper.assertTrue(be.currentVanillaTrack() == null,
                "empty jukebox exposed a vanilla playback descriptor");
        helper.succeed();
    }

    private static void finishCurrentTrack(GameTestHelper helper, GoldenJukeboxBlockEntity be) {
        be.seekTo(TRACK_MS);
        GoldenJukeboxBlockEntity.serverTick(helper.getLevel(), be.getBlockPos(), be.getBlockState(), be);
    }

    private static GoldenJukeboxBlockEntity reload(GameTestHelper helper, GoldenJukeboxBlockEntity be) {
        final CompoundTag saved = be.saveWithFullMetadata();
        be.onBlockRemoved();
        final BlockEntity loaded = BlockEntity.loadStatic(be.getBlockPos(), be.getBlockState(), saved);
        helper.assertTrue(loaded instanceof GoldenJukeboxBlockEntity, "saved golden jukebox did not reload");
        final GoldenJukeboxBlockEntity restored = (GoldenJukeboxBlockEntity) loaded;
        restored.setLevel(helper.getLevel());
        helper.getLevel().setBlockEntity(restored);
        GoldenJukeboxBlockEntity.serverTick(
                helper.getLevel(), restored.getBlockPos(), restored.getBlockState(), restored);
        return restored;
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void pausedStateSurvivesNbtRoundTrip(GameTestHelper helper) {
        GoldenJukeboxBlockEntity be = jukebox(helper);
        be.setItem(0, customDisc());
        be.seekTo(300L);
        be.setPaused(true);
        final long offset = be.currentElapsedMs();

        be = reload(helper, be);
        helper.assertTrue(be.isPaused() && !be.isVanillaPlaying(), "NBT reload resumed a paused disc");
        helper.assertTrue(be.currentElapsedMs() == offset, "NBT reload lost the paused offset");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void stoppedStateSurvivesNbtRoundTrip(GameTestHelper helper) {
        GoldenJukeboxBlockEntity be = jukebox(helper);
        be.setItem(0, customDisc());
        finishCurrentTrack(helper, be);

        be = reload(helper, be);
        helper.assertTrue(be.isStopped() && !be.isVanillaPlaying(), "NBT reload restarted a stopped disc");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void albumNbtKeepsDiscMetadata(GameTestHelper helper) {
        final ItemStack disc = customDisc();
        disc.setHoverName(Component.literal("Saved disc"));
        final DiscDyeData dye = new DiscDyeData(DiscDye.BLUE, DiscDye.ORANGE);
        CustomMusicDiscItem.setDye(disc, dye);
        final ItemStack album = new ItemStack(Items.CHEST);
        AlbumContents.store(album, new AlbumContents(List.of(disc)));

        final AlbumContents restored = AlbumContents.of(ItemStack.of(album.save(new CompoundTag())));
        final ItemStack restoredDisc = restored.discAt(0);
        helper.assertTrue(restored.size() == 1, "album NBT lost its disc");
        helper.assertTrue(restoredDisc.hasCustomHoverName()
                        && "Saved disc".equals(restoredDisc.getHoverName().getString()),
                "album NBT lost the disc custom name");
        helper.assertTrue("A".equals(CustomMusicDiscItem.getTrack(restoredDisc).title()),
                "album NBT lost custom track metadata");
        helper.assertTrue(dye.equals(CustomMusicDiscItem.getDye(restoredDisc)),
                "album NBT lost disc dye metadata");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void albumRejectsRecursiveContainerPredicate(GameTestHelper helper) {
        final ItemStack nested = new ItemStack(Items.CHEST);
        AlbumContents.store(nested, new AlbumContents(List.of(customDisc())));
        final boolean accepted = AlbumContents.canInsert(
                nested,
                stack -> true,
                stack -> !AlbumContents.of(stack).isEmpty() || stack.is(ModItems.BOOMBOX.get()));
        helper.assertTrue(!accepted, "recursive album passed the insertion predicate");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void albumRejectsOversizedStoredList(GameTestHelper helper) {
        final ListTag oversized = new ListTag();
        for (int i = 0; i <= AlbumContents.STORAGE_DECODE_LIMIT; i++) {
            oversized.add(new CompoundTag());
        }
        try {
            AlbumContents.fromNbt(oversized);
            helper.fail("oversized album NBT was accepted");
        } catch (IllegalArgumentException expected) {
            helper.succeed();
        }
    }
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void sourceIdentitySurvivesRelocationAndLegacyData(GameTestHelper helper) {
        final var source = jukebox(helper);
        source.setVolumePercent(73);
        final java.util.UUID identity = source.sourceIdentity();
        helper.assertTrue(identity.equals(source.sourceIdentity()), "source ID changed on repeated lookup");
        final CompoundTag saved = source.saveWithFullMetadata();
        final BlockPos movedPos = source.getBlockPos().offset(3, 0, 0);
        final var moved = (GoldenJukeboxBlockEntity) BlockEntity.loadStatic(movedPos, source.getBlockState(), saved);
        helper.assertTrue(moved != null && identity.equals(moved.peekSourceIdentity()), "source ID did not survive relocation");
        moved.setLevel(helper.getLevel());
        helper.assertTrue(identity.equals(moved.sourceIdentity()), "relocation generated a new identity");
        helper.assertTrue(moved.getVolumePercent() == 73, "identity persistence changed existing volume");
        for (boolean malformed : new boolean[] {false, true}) {
            final CompoundTag legacy = saved.copy();
            legacy.remove("source_id");
            if (malformed) legacy.putString("source_id", "invalid-uuid");
            final var restored = (GoldenJukeboxBlockEntity) BlockEntity.loadStatic(movedPos, source.getBlockState(), legacy);
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

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void sourceRegistryTracksConflictRemovalAndReload(GameTestHelper helper) {
        final BlockPos secondPos = new BlockPos(4, 1, 1);
        final GoldenJukeboxBlockEntity first = jukebox(helper);
        helper.setBlock(secondPos, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity second = (GoldenJukeboxBlockEntity) helper.getBlockEntity(secondPos);
        helper.assertTrue(first != null && second != null, "Forge registry試験の実world Golden 2台を生成できていない");

        final java.util.UUID sharedId = first.sourceIdentity();
        final CompoundTag sharedState = first.saveWithFullMetadata();
        second.load(sharedState);
        final GoldenSourceRegistry.Resolution conflict = GoldenSourceRegistry.lookup(helper.getLevel(), sharedId);
        helper.assertTrue(conflict.status() == GoldenSourceRegistry.Status.CONFLICT && conflict.source() == null,
                "Forge同じsource IDの実world Golden 2台をCONFLICTとして検出しない");

        second.setRemoved();
        final GoldenSourceRegistry.Resolution firstOnly = GoldenSourceRegistry.lookup(helper.getLevel(), sharedId);
        helper.assertTrue(firstOnly.status() == GoldenSourceRegistry.Status.UNIQUE && firstOnly.source() == first,
                "Forge片方removed後に残ったGoldenをUNIQUEとして解決しない");
        first.setRemoved();
        final GoldenSourceRegistry.Resolution none = GoldenSourceRegistry.lookup(helper.getLevel(), sharedId);
        helper.assertTrue(none.status() == GoldenSourceRegistry.Status.UNKNOWN && none.source() == null,
                "Forge両方removed後もsource IDがUNKNOWNにならない");

        second.clearRemoved();
        final GoldenSourceRegistry.Resolution secondOnly = GoldenSourceRegistry.lookup(helper.getLevel(), sharedId);
        helper.assertTrue(secondOnly.status() == GoldenSourceRegistry.Status.UNIQUE && secondOnly.source() == second,
                "Forge clearRemoved後の実world GoldenをUNIQUEとして再登録しない");
        final java.util.UUID absentId = java.util.UUID.randomUUID();
        helper.assertTrue(GoldenSourceRegistry.lookup(helper.getLevel(), absentId).status()
                        == GoldenSourceRegistry.Status.UNKNOWN,
                "Forge未登録の別source IDをUNKNOWNとして返さない");

        final java.util.UUID replacementId = java.util.UUID.randomUUID();
        final CompoundTag replacementState = sharedState.copy();
        replacementState.putString("source_id", replacementId.toString());
        second.load(replacementState);
        helper.assertTrue(GoldenSourceRegistry.lookup(helper.getLevel(), sharedId).status()
                        == GoldenSourceRegistry.Status.UNKNOWN,
                "Forge実world BEへのNBT reload後も旧source ID登録が残った");
        final GoldenSourceRegistry.Resolution replacement =
                GoldenSourceRegistry.lookup(helper.getLevel(), replacementId);
        helper.assertTrue(replacement.status() == GoldenSourceRegistry.Status.UNIQUE
                        && replacement.source() == second,
                "Forge実world BEへのNBT reload後に新source IDへ登録されない");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
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
                "Forge retirement CODEC往復で同位置の2 UUIDを保持しない");
        restored.forget(firstId);
        helper.assertTrue(restored.removedAt(oldPosition).equals(java.util.Set.of(secondId)),
                "Forge片方forgetが同位置の残りUUIDまで消した");
        restored.recordRemoval(secondId, newPosition);
        helper.assertTrue(restored.removedAt(oldPosition).isEmpty()
                        && restored.removedAt(newPosition).equals(java.util.Set.of(secondId)),
                "Forge同じUUIDの再記録で旧位置を空にして新位置へ移さない");

        restored = retirementRoundTrip(helper, restored);
        helper.assertTrue(restored.removedAt(oldPosition).isEmpty()
                        && restored.removedAt(newPosition).equals(java.util.Set.of(secondId)),
                "Forge再配置後のretirementが2回目のCODEC往復で変化した");
        final CompoundTag malformed = (CompoundTag) GoldenSourceRetirements.CODEC
                .encodeStart(NbtOps.INSTANCE, restored).result().orElse(null);
        helper.assertTrue(malformed != null, "Forge不正UUID混入試験用retirementをencodeできない");
        final CompoundTag retired = malformed.getCompound("retired");
        retired.putLong("invalid-uuid", oldPosition.asLong());
        final GoldenSourceRetirements tolerant = GoldenSourceRetirements.CODEC
                .parse(NbtOps.INSTANCE, malformed).result().orElse(null);
        helper.assertTrue(tolerant != null
                        && tolerant.removedAt(oldPosition).isEmpty()
                        && tolerant.removedAt(newPosition).equals(java.util.Set.of(secondId)),
                "Forge不正UUIDキーが合法retirementを壊した、または不正記録を採用した");

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
                "Forge A→B claimがCODEC往復後にpredecessorまたは位置claimを失った");

        aliases.claimReplacement(generationA, unrelatedD);
        helper.assertTrue(aliases.predecessorsOf(generationB).contains(generationA)
                        && !aliases.predecessorsOf(unrelatedD).contains(generationA),
                "Forge同じretired UUIDの二重claimが最初のA→Bを上書きした");
        aliases.recordRemoval(generationB, newPosition);
        aliases.claimReplacement(generationB, generationA);
        helper.assertTrue(aliases.unclaimedAt(newPosition).contains(generationB)
                        && !aliases.predecessorsOf(generationA).contains(generationB),
                "Forge A→Bがある状態でcycle B→Aを受理した");
        aliases.claimReplacement(generationB, generationC);
        aliases = retirementRoundTrip(helper, aliases);
        helper.assertTrue(aliases.predecessorsOf(generationC)
                        .equals(java.util.Set.of(generationA, generationB)),
                "Forge A→B→C claimがCODEC往復後に推移的predecessorを失った");

        aliases.forget(generationB);
        helper.assertTrue(aliases.predecessorsOf(generationB).contains(generationA)
                        && aliases.predecessorsOf(generationC).isEmpty(),
                "Forge forget(B)がA→Bまで消した、またはCからA/Bを切り離していない");

        final GoldenSourceRetirements legacyData = new GoldenSourceRetirements();
        legacyData.recordRemoval(generationA, oldPosition);
        final CompoundTag legacyEncoded = (CompoundTag) GoldenSourceRetirements.CODEC
                .encodeStart(NbtOps.INSTANCE, legacyData).result().orElse(null);
        helper.assertTrue(legacyEncoded != null, "Forge successors欠落旧dataのencodeに失敗した");
        legacyEncoded.remove("successors");
        final GoldenSourceRetirements legacyDecoded = GoldenSourceRetirements.CODEC
                .parse(NbtOps.INSTANCE, legacyEncoded).result().orElse(null);
        helper.assertTrue(legacyDecoded != null
                        && legacyDecoded.removedAt(oldPosition).contains(generationA)
                        && legacyDecoded.predecessorsOf(generationB).isEmpty(),
                "Forge successorsキーの無い旧retirement dataを読めない");
        helper.succeed();
    }

    private static GoldenSourceRetirements retirementRoundTrip(
            GameTestHelper helper, GoldenSourceRetirements original) {
        final var encoded = GoldenSourceRetirements.CODEC
                .encodeStart(NbtOps.INSTANCE, original).result().orElse(null);
        helper.assertTrue(encoded != null, "Forge retirement CODEC encodeに失敗した");
        final GoldenSourceRetirements decoded = GoldenSourceRetirements.CODEC
                .parse(NbtOps.INSTANCE, encoded).result().orElse(null);
        helper.assertTrue(decoded != null, "Forge retirement CODEC decodeに失敗した");
        return decoded;
    }


}
