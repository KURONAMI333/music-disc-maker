package com.kuronami.musicdiscmaker.gametest;

//? if <1.21.2 {
/*import java.util.List;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.compat.additionaladditions.AdditionalAdditionsAlbumSupport;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import one.dqu.additionaladditions.feature.album.AlbumContents;
import one.dqu.additionaladditions.registry.AAMisc;
import one.dqu.additionaladditions.registry.AAItems;

@GameTestHolder("mdm_aa_integration")
public final class AdditionalAdditionsIntegrationGameTests {
    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    public static void realAlbumVariantsKeepMixedDiscContents(GameTestHelper helper) {
        helper.assertTrue(net.neoforged.fml.ModList.get().isLoaded("additionaladditions"), "Additional Additions is not loaded");
        LoadedAlbums.variants(helper);
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    public static void goldenRestoresRealAlbumSelectionAndPausedOffset(GameTestHelper helper) {
        helper.assertTrue(net.neoforged.fml.ModList.get().isLoaded("additionaladditions"), "Additional Additions is not loaded");
        LoadedAlbums.golden(helper);
    }

    // Keep optional types out of automatic test discovery in the ordinary run.
    private static final class LoadedAlbums {
        private static ItemStack disc(String suffix) {
            final var stack = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
            stack.set(ModDataComponents.CUSTOM_TRACK.get(), new CustomTrackData(
                    "https://example.invalid/aa/" + suffix, "AA " + suffix, "test author", 120000L,
                    "https://example.invalid/cover.png", false));
            return stack;
        }

        static void variants(GameTestHelper helper) {
            final var discs = List.of(disc("first"), new ItemStack(Items.MUSIC_DISC_CAT), disc("last"));
            final var albums = BuiltInRegistries.ITEM.getTag(AAMisc.ALBUMS_TAG).orElseThrow();
            helper.assertTrue(albums.size() == 17, "Expected the 17 real AA 10.0.5 album variants");
            for (final var item : albums) {
                final var album = new ItemStack(item);
                helper.assertTrue(AdditionalAdditionsAlbumSupport.isAlbum(album), "Runtime provider did not recognize an empty AA album");
                helper.assertTrue(AdditionalAdditionsAlbumSupport.trackCount(album) == 0, "Empty AA album is not empty");
                album.set(AAMisc.ALBUM_CONTENTS_COMPONENT.get(), new AlbumContents(discs));
                final var restored = ItemStack.parse(helper.getLevel().registryAccess(),
                        album.save(helper.getLevel().registryAccess())).orElseThrow();
                helper.assertTrue(AdditionalAdditionsAlbumSupport.trackCount(restored) == 3, "AA album lost tracks after save/load");
                for (int i = 0; i < discs.size(); i++) {
                    final var actual = AdditionalAdditionsAlbumSupport.trackAt(restored, i);
                    helper.assertTrue(ItemStack.isSameItemSameComponents(actual, discs.get(i))
                            && actual.getCount() == discs.get(i).getCount(), "AA album changed track order or metadata at " + i);
                }
            }
            helper.succeed();
        }

        static void golden(GameTestHelper helper) {
            final var level = helper.getLevel();
            final var pos = new BlockPos(1, 1, 1);
            helper.setBlock(pos, ModBlocks.GOLDEN_JUKEBOX.get());
            final var source = (GoldenJukeboxBlockEntity) level.getBlockEntity(helper.absolutePos(pos));
            final var album = new ItemStack(AAItems.ALBUM.get());
            album.set(AAMisc.ALBUM_CONTENTS_COMPONENT.get(), new AlbumContents(List.of(disc("first"), disc("second"), new ItemStack(Items.MUSIC_DISC_CAT))));
            source.setItem(0, album);
            helper.assertTrue(source.navigableTrackCount() == 3, "GUI navigation count did not include the real AA album");
            helper.assertTrue(source.currentTrack() != null && source.currentTrack().url().endsWith("first"), "Golden did not start the real AA album");
            source.previousTrack();
            helper.assertTrue(source.playbackCursor().discIndex() == 2 && source.currentVanillaTrack() != null
                    && "minecraft:music_disc.cat".equals(source.currentVanillaTrack().soundEventId()),
                    "Previous did not wrap to the real album's vanilla disc");
            source.nextTrack();
            helper.assertTrue(source.currentTrack().url().endsWith("first"), "Next did not wrap the real album");
            source.nextTrack();
            source.setRepeat(true);
            source.setPaused(true);
            source.previousTrack();
            helper.assertTrue(source.isPaused() && source.currentTrack().url().endsWith("first"), "Previous resumed a paused AA album");
            source.nextTrack();
            helper.assertTrue(source.isPaused() && source.currentTrack().url().endsWith("second"), "Next resumed a paused AA album");
            source.seekTo(7650L);
            final var saved = source.saveWithFullMetadata(level.registryAccess());
            final var restored = (GoldenJukeboxBlockEntity) BlockEntity.loadStatic(source.getBlockPos(), source.getBlockState(), saved, level.registryAccess());
            helper.assertTrue(restored != null, "Golden could not reload the real AA album");
            restored.setLevel(level);
            helper.assertTrue(restored.navigableTrackCount() == 3, "GUI navigation count was lost after save/load");
            helper.assertTrue(restored.isPaused() && restored.isRepeat()
                    && restored.playbackCursor().discIndex() == 1 && restored.currentElapsedMs() == 7650L,
                    "Golden lost AA selection, repeat, or paused offset");
            helper.assertTrue(restored.currentTrack() != null && restored.currentTrack().equals(source.currentTrack()), "Golden restored a different AA track");
            helper.assertTrue(ItemStack.isSameItemSameComponents(restored.getItem(0), album), "Golden changed the AA album component");
            source.clearContent();
            helper.succeed();
        }
    }
}
*///?}
