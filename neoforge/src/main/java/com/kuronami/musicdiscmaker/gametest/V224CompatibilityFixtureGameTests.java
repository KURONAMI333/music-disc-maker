package com.kuronami.musicdiscmaker.gametest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.event.ActiveDiscRegistry;
import com.kuronami.musicdiscmaker.event.ActiveDiscSaveData;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.level.block.entity.BlockEntity;
//? if <1.21.2 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}
public final class V224CompatibilityFixtureGameTests {

    private static final String FIXTURE_ROOT =
            "/data/music_disc_maker/compat/v2.2.4/neoforge-1.21.1/";
    private static final BlockPos EXPECTED_POS = new BlockPos(-321, 72, 654);
    private static final long EXPECTED_START_MILLIS = 9_876_543_210L;

    private V224CompatibilityFixtureGameTests() {
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", batch = "v224CompatibilityFixture")
    *///?}
    public static void publishedV224CodecsRemainReadable(GameTestHelper helper) {
        try {
            final CustomTrackData track = CustomTrackData.CODEC.parse(
                    NbtOps.INSTANCE, parse(read("custom-track-1.21.1.snbt"))).getOrThrow();
            assertTrack(helper, track);

            final ActiveDiscSaveData active = ActiveDiscSaveData.CODEC.parse(
                    NbtOps.INSTANCE, parse(read("active-discs-1.21.1.snbt"))).getOrThrow();
            helper.assertTrue(active.entries().size() == 1,
                    "公開2.2.4 ActiveDisc fixtureの1件を復元できていない");
            final ActiveDiscRegistry.Playing playing = active.entries().iterator().next();
            helper.assertTrue(EXPECTED_POS.equals(playing.pos()),
                    "公開2.2.4 ActiveDisc fixtureの座標が変わった");
            helper.assertTrue(playing.startMillis() == EXPECTED_START_MILLIS,
                    "公開2.2.4 ActiveDisc fixtureの開始時刻が変わった");
            assertTrack(helper, playing.track());
            helper.succeed();
        } catch (Exception e) {
            helper.fail("公開2.2.4 fixtureを読めない: " + e);
        }
    }

    // The published Golden fixtures use the 1.21.1 CompoundTag BlockEntity load API.
    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", batch = "v224GoldenCompatibilityFixture")
    public static void publishedV224GoldenStatesRemainReadable(GameTestHelper helper) {
        try {
            final GoldenJukeboxBlockEntity stopped = loadGolden(
                    helper, new BlockPos(1, 1, 1), "golden-stopped-empty-1.21.1.snbt");
            assertSettings(helper, stopped);
            helper.assertTrue(!stopped.hasDisc() && stopped.isStopped(),
                    "公開2.2.4の空停止状態をSTOPPEDとして復元できていない");

            final GoldenJukeboxBlockEntity playing = loadGolden(
                    helper, new BlockPos(3, 1, 1), "golden-playing-1.21.1.snbt");
            assertSettings(helper, playing);
            assertDisc(helper, playing, "playing");
            helper.assertTrue(playing.playbackCursor().state() == PlaybackCursor.State.PLAYING,
                    "公開2.2.4の再生中状態をPLAYINGとして復元できていない");

            final GoldenJukeboxBlockEntity paused = loadGolden(
                    helper, new BlockPos(5, 1, 1), "golden-paused-1.21.1.snbt");
            assertSettings(helper, paused);
            assertDisc(helper, paused, "paused");
            helper.assertTrue(paused.playbackCursor().state() == PlaybackCursor.State.PAUSED,
                    "公開2.2.4の一時停止状態をPAUSEDとして復元できていない");
            helper.assertTrue(paused.currentElapsedMs() >= 7_650L,
                    "公開2.2.4のpausedOffsetを復元できていない");
            helper.succeed();
        } catch (Exception e) {
            helper.fail("公開2.2.4 Golden fixtureを読めない: " + e);
        }
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", batch = "v224GoldenResaveCompatibilityFixture")
    public static void publishedV224GoldenCanBeSavedAgainWithoutLosingMedia(GameTestHelper helper) {
        try {
            final String[] states = {"stopped-empty", "playing", "paused"};
            for (int index = 0; index < states.length; index++) {
                final String state = states[index];
                final GoldenJukeboxBlockEntity original = loadGolden(helper,
                        new BlockPos(1 + index * 2, 1, 1), "golden-" + state + "-1.21.1.snbt");
                final java.util.UUID source = original.sourceIdentity();
                final PlaybackCursor cursor = original.playbackCursor();
                final CompoundTag saved = original.saveWithFullMetadata(helper.getLevel().registryAccess());
                final BlockEntity loaded = BlockEntity.loadStatic(original.getBlockPos(),
                        original.getBlockState(), saved, helper.getLevel().registryAccess());
                helper.assertTrue(loaded instanceof GoldenJukeboxBlockEntity,
                        "v3再保存後にGoldenとして復元できない: " + state);
                final GoldenJukeboxBlockEntity restored = (GoldenJukeboxBlockEntity) loaded;
                assertSettings(helper, restored);
                helper.assertTrue(cursor.equals(restored.playbackCursor()),
                        "v3再保存後に再生状態・曲位置・世代が変化した: " + state);
                helper.assertTrue(source.equals(restored.peekSourceIdentity()),
                        "旧データに付与した音源UUIDが再保存で失われた: " + state);
                if (!original.hasDisc()) {
                    helper.assertFalse(restored.hasDisc(), "旧空Goldenに再保存後の盤が出現した");
                    continue;
                }
                assertDisc(helper, restored, state);
                final var encodedItem = original.getDisc().save(helper.getLevel().registryAccess());
                final var restoredItem = net.minecraft.world.item.ItemStack.parse(
                        helper.getLevel().registryAccess(), encodedItem).orElseThrow();
                helper.assertTrue(net.minecraft.world.item.ItemStack.isSameItemSameComponents(
                        original.getDisc(), restoredItem), "旧盤のDataComponentが再保存で変化した: " + state);
                helper.assertTrue(original.getDisc().getCount() == restoredItem.getCount(),
                        "旧盤の個数が再保存で変化した: " + state);
                if (state.equals("paused")) {
                    helper.assertTrue(saved.getLong("pausedOffset") == 7_650L,
                            "旧盤の一時停止位置が再保存で変化した");
                }
            }
            helper.succeed();
        } catch (Exception e) {
            helper.fail("公開2.2.4 Goldenのv3再保存に失敗: " + e);
        }
    }

    private static GoldenJukeboxBlockEntity loadGolden(GameTestHelper helper, BlockPos relativePos,
            String fixture) throws Exception {
        helper.setBlock(relativePos, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity placed = helper.getBlockEntity(relativePos);
        final CompoundTag tag = parse(read(fixture));
        final BlockEntity loaded = BlockEntity.loadStatic(placed.getBlockPos(), placed.getBlockState(),
                tag, helper.getLevel().registryAccess());
        helper.assertTrue(loaded instanceof GoldenJukeboxBlockEntity,
                "公開2.2.4 Golden fixtureからBlockEntityを生成できていない");
        final GoldenJukeboxBlockEntity golden = (GoldenJukeboxBlockEntity) loaded;
        golden.setLevel(helper.getLevel());
        helper.getLevel().setBlockEntity(golden);
        return golden;
    }

    private static void assertSettings(GameTestHelper helper, GoldenJukeboxBlockEntity golden) {
        helper.assertTrue(golden.getRangeBlocks() == 173, "公開2.2.4 Golden fixtureのrangeが変わった");
        helper.assertTrue(golden.getVolumePercent() == 137, "公開2.2.4 Golden fixtureのvolumeが変わった");
        helper.assertTrue(golden.isRepeat(), "公開2.2.4 Golden fixtureのrepeatが変わった");
        helper.assertFalse(golden.isDirectional(), "公開2.2.4 Golden fixtureのdirectionalが変わった");
    }

    private static void assertDisc(GameTestHelper helper, GoldenJukeboxBlockEntity golden, String state) {
        helper.assertTrue(golden.hasDisc(), "公開2.2.4 Golden fixtureの盤を復元できていない");
        helper.assertTrue(golden.getDisc().is(ModItems.CUSTOM_MUSIC_DISC.get()),
                "公開2.2.4 Golden fixtureのitem IDが変わった");
        final CustomTrackData track = golden.getDisc().get(ModDataComponents.CUSTOM_TRACK.get());
        helper.assertTrue(track != null, "公開2.2.4 Golden fixtureのcustom_trackを復元できていない");
        helper.assertTrue(("https://example.invalid/v224-golden-" + state).equals(track.url()),
                "公開2.2.4 Golden fixtureの盤urlが変わった");
        helper.assertTrue(("v2.2.4 Golden " + state).equals(track.title()),
                "公開2.2.4 Golden fixtureの盤情報が変わった");
        helper.assertTrue("fixture generator".equals(track.author()),
                "公開2.2.4 Golden fixtureの盤authorが変わった");
        helper.assertTrue(track.durationMs() == 187_654L,
                "公開2.2.4 Golden fixtureの盤duration_msが変わった");
        helper.assertTrue("https://example.invalid/v224-golden-cover.png".equals(track.thumbnailUrl()),
                "公開2.2.4 Golden fixtureの盤thumbnail_urlが変わった");
        helper.assertFalse(track.radio(), "公開2.2.4 Golden fixtureの盤radioが変わった");
    }
    *///?}

    private static String read(String name) throws IOException {
        try (InputStream in = V224CompatibilityFixtureGameTests.class
                .getResourceAsStream(FIXTURE_ROOT + name)) {
            if (in == null) {
                throw new IOException("fixture resourceが無い: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static CompoundTag parse(String snbt) throws Exception {
        //? if >=1.21.2 {
        return TagParser.parseCompoundFully(snbt);
        //?} else {
        /*return TagParser.parseTag(snbt);
        *///?}
    }

    private static void assertTrack(GameTestHelper helper, CustomTrackData track) {
        helper.assertTrue("https://example.invalid/v224-fixture?x=1".equals(track.url()),
                "公開2.2.4 CustomTrackData fixtureのurlが変わった");
        helper.assertTrue("v2.2.4 fixture title".equals(track.title()),
                "公開2.2.4 CustomTrackData fixtureのtitleが変わった");
        helper.assertTrue("v2.2.4 fixture author".equals(track.author()),
                "公開2.2.4 CustomTrackData fixtureのauthorが変わった");
        helper.assertTrue(track.durationMs() == 187_654L,
                "公開2.2.4 CustomTrackData fixtureのduration_msが変わった");
        helper.assertTrue("https://example.invalid/v224-cover.png".equals(track.thumbnailUrl()),
                "公開2.2.4 CustomTrackData fixtureのthumbnail_urlが変わった");
        helper.assertTrue(track.radio(),
                "公開2.2.4 CustomTrackData fixtureのradioが変わった");
    }
}
