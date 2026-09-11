package com.kuronami.musicdiscmaker.event;

//? if >=1.21.1 && <1.21.2 {
/*import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
*///?}
public final class ActiveDiscSavedDataStorageGameTests {
    //? if >=1.21.1 && <1.21.2 {
    /*private static final String FIXTURE = "/data/music_disc_maker/compat/v2.2.4/neoforge-1.21.1/active-discs-1.21.1.snbt";

    private ActiveDiscSavedDataStorageGameTests() {
    }

    // Read a .dat written by the published v2 JAR, then save and reload through v3 storage.
    @GameTest(template = "empty8x3x8", batch = "v224ActiveDiscSavedDataStorage")
    public static void publishedV224FixtureSurvivesSavedDataStorageRoundTrip(GameTestHelper helper) {
        final ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID,
                        "gametest_active_disc_storage_" + UUID.randomUUID().toString().replace('-', '_')));
        final BlockPos retainedPos = new BlockPos(90, 70, 90);
        Path directory = null;
        try {
            final CompoundTag fixtureTag = TagParser.parseTag(readFixture());
            final ActiveDiscSaveData fixture = ActiveDiscSaveData.CODEC.parse(NbtOps.INSTANCE, fixtureTag).getOrThrow();
            helper.assertTrue(fixture.entries().size() == 1, "公開fixtureのentry数が1件ではない");
            final ActiveDiscRegistry.Playing expected = fixture.entries().iterator().next();
            assertFixture(helper, expected);

            final Path world = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            directory = Files.createDirectories(world.resolve("gametest-active-disc-storage-" + UUID.randomUUID()).normalize());
            helper.assertTrue(directory.getParent().equals(world), "隔離storageがGameTest world直下ではない");
            final Path dataFile = directory.resolve(ActiveDiscSaveData.storageDataName() + ".dat");
            final String savedFixture = FIXTURE.substring(0, FIXTURE.lastIndexOf('/') + 1)
                    + "music_disc_maker_active_discs.dat";
            try (InputStream in = ActiveDiscSavedDataStorageGameTests.class.getResourceAsStream(savedFixture)) {
                if (in == null) throw new IOException("旧公開版の保存ファイルが無い: " + savedFixture);
                Files.copy(in, dataFile);
            }
            helper.assertTrue(Files.isRegularFile(dataFile), "旧公開版の.datが無い");

            final DimensionDataStorage writer = storage(directory, helper);
            final ActiveDiscSaveData oldLoaded = writer.computeIfAbsent(
                    ActiveDiscSaveData.storageFactory(), ActiveDiscSaveData.storageDataName());
            helper.assertTrue(oldLoaded != fixture && oldLoaded.entries().size() == 1,
                    "production storage loaderが公開fixtureを新instanceとして読めていない");
            final ActiveDiscRegistry.Playing loaded = oldLoaded.entries().iterator().next();
            assertFixture(helper, loaded);

            // Save the imported v2 data through v3 after adding another entry.
            helper.assertTrue(!oldLoaded.isDirty(), "読込直後のdataがdirtyになっている");
            oldLoaded.put(retainedPos, loaded.track(), 123L);
            helper.assertTrue(oldLoaded.isDirty(), "putが保存対象をdirtyにしていない");
            writer.save();
            IOUtilities.waitUntilIOWorkerComplete();
            helper.assertTrue(Files.isRegularFile(dataFile), "v3 dirty/save後に.datが無い");

            final ActiveDiscSaveData reloaded = storage(directory, helper).computeIfAbsent(
                    ActiveDiscSaveData.storageFactory(), ActiveDiscSaveData.storageDataName());
            helper.assertTrue(reloaded != oldLoaded && reloaded.entries().size() == 2,
                    "v3 saveを新storage instanceから読み直せていない");
            final ActiveDiscRegistry.Playing saved = reloaded.entries().stream()
                    .filter(p -> p.pos().equals(loaded.pos())).findFirst().orElseThrow();
            assertFixture(helper, saved);
            helper.assertTrue(reloaded.entries().stream().anyMatch(p -> p.pos().equals(retainedPos)
                    && p.startMillis() == 123L && p.track().equals(loaded.track())),
                    "v3で追加したentryがディスクに保存されていない");

            final CustomTrackData retainedTrack = new CustomTrackData("https://example.invalid/retained",
                    "retained", "test", 1L, "", false);
            ActiveDiscRegistry.start(dim, retainedPos, retainedTrack, 123L);
            ActiveDiscRegistry.restoreFrom(dim, reloaded.entries());
            final ActiveDiscRegistry.Playing retained = ActiveDiscRegistry.current(dim, retainedPos);
            helper.assertTrue(retained != null && retained.track().equals(retainedTrack) && retained.startMillis() == 123L,
                    "restoreFromが同次元の既存entryを上書きした");
            final ActiveDiscRegistry.Playing restored = ActiveDiscRegistry.current(dim, saved.pos());
            helper.assertTrue(restored != null, "storage再読込entryがregistryへ復元されていない");
            assertFixture(helper, restored);
            ActiveDiscRegistry.restoreFrom(dim, reloaded.entries());
            helper.assertTrue(ActiveDiscRegistry.current(dim, saved.pos()) == restored,
                    "二度目のrestoreが復元済みentryを置換した");
            helper.succeed();
        } catch (Exception failure) {
            helper.fail("ActiveDisc SavedData storage round-tripに失敗: " + failure);
        } finally {
            ActiveDiscRegistry.clear(dim);
        }
    }

    private static DimensionDataStorage storage(Path directory, GameTestHelper helper) {
        return new DimensionDataStorage(directory.toFile(), DataFixers.getDataFixer(), helper.getLevel().registryAccess());
    }

    private static void assertFixture(GameTestHelper helper, ActiveDiscRegistry.Playing playing) {
        final CustomTrackData t = playing.track();
        helper.assertTrue(playing.pos().equals(new BlockPos(-321, 72, 654)), "fixture posが変わった");
        helper.assertTrue(playing.startMillis() == 9_876_543_210L, "fixture startが変わった");
        helper.assertTrue("https://example.invalid/v224-fixture?x=1".equals(t.url()), "fixture urlが変わった");
        helper.assertTrue("v2.2.4 fixture title".equals(t.title()), "fixture titleが変わった");
        helper.assertTrue("v2.2.4 fixture author".equals(t.author()), "fixture authorが変わった");
        helper.assertTrue(t.durationMs() == 187_654L, "fixture durationが変わった");
        helper.assertTrue("https://example.invalid/v224-cover.png".equals(t.thumbnailUrl()), "fixture thumbnailが変わった");
        helper.assertTrue(t.radio(), "fixture radioが変わった");
    }

    private static String readFixture() throws IOException {
        try (InputStream in = ActiveDiscSavedDataStorageGameTests.class.getResourceAsStream(FIXTURE)) {
            if (in == null) throw new IOException("公開fixtureが無い: " + FIXTURE);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    *///?}
}
