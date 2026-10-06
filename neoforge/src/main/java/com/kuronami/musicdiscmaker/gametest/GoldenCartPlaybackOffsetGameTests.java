package com.kuronami.musicdiscmaker.gametest;

//? if >=1.21 {
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.entity.GoldenJukeboxMinecartEntity;
import com.kuronami.musicdiscmaker.event.GoldenCartPlayback;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
//? if >=26.1 {
import net.minecraft.world.item.JukeboxSong;
//?} else {
/*import net.minecraft.world.item.EitherHolder;
*///?}
//? if <1.21.11 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
*///?}

//? if <1.21.11 {
/*@GameTestHolder(MusicDiscMaker.MODID)
*///?}
public final class GoldenCartPlaybackOffsetGameTests {
    private static final String TEMPLATE = "empty8x3x8";
    private static final long TRACK_DURATION_MS = 45_000L;

    private GoldenCartPlaybackOffsetGameTests() {
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    *///?}
    public static void playingSeekAndPauseResumeUseThePausedOffsetAsBase(GameTestHelper helper) {
        final var level = helper.getLevel();
        final BlockPos cartPos = helper.absolutePos(new BlockPos(2, 1, 2));
        final GoldenJukeboxMinecartEntity cart = new GoldenJukeboxMinecartEntity(
                level, cartPos.getX() + 0.5, cartPos.getY(), cartPos.getZ() + 0.5);
        helper.assertTrue(level.addFreshEntity(cart), "golden jukebox minecart was not added");

        final ItemStack disc = disc(helper);
        cart.getItemStacks().set(GoldenJukeboxMinecartEntity.SLOT_MEDIA, disc);
        GoldenCartPlayback.play(cart);
        helper.assertTrue(GoldenCartPlayback.isPlaying(cart), "cart did not start playing");

        GoldenCartPlayback.seekTo(cart, 35_017L);
        helper.assertTrue(GoldenCartPlayback.currentOffsetMs(cart) == 35_017L,
                "playing seek changed the requested millisecond offset immediately");
        final long seekStart = level.getGameTime();

        helper.runAfterDelay(3, () -> {
            final long seekTicks = level.getGameTime() - seekStart;
            helper.assertTrue(seekTicks > 0L
                            && GoldenCartPlayback.currentOffsetMs(cart) == 35_017L + seekTicks * 50L,
                    "playing seek did not retain its exact base plus elapsed server ticks");

            GoldenCartPlayback.pause(cart);
            helper.assertTrue(!GoldenCartPlayback.isPlaying(cart), "pause left the cart playing");
            final long pausedAt = GoldenCartPlayback.currentOffsetMs(cart);
            GoldenCartPlayback.play(cart);
            helper.assertTrue(GoldenCartPlayback.isPlaying(cart)
                            && GoldenCartPlayback.currentOffsetMs(cart) == pausedAt,
                    "resume counted the paused position twice");
            final long resumeStart = level.getGameTime();

            helper.runAfterDelay(2, () -> {
                final long resumeTicks = level.getGameTime() - resumeStart;
                helper.assertTrue(resumeTicks > 0L
                                && GoldenCartPlayback.currentOffsetMs(cart) == pausedAt + resumeTicks * 50L,
                        "resumed playback did not continue from the saved exact offset");

                GoldenCartPlayback.pause(cart);
                GoldenCartPlayback.seekTo(cart, 35_017L);
                helper.assertTrue(GoldenCartPlayback.currentOffsetMs(cart) == 35_017L,
                        "paused seek changed its requested millisecond offset");
                GoldenCartPlayback.play(cart);
                helper.assertTrue(GoldenCartPlayback.currentOffsetMs(cart) == 35_017L,
                        "resume after paused seek counted the seek position twice");

                GoldenCartPlayback.seekTo(cart, 30_000L);
                helper.assertTrue(GoldenCartPlayback.currentOffsetMs(cart) == 30_000L,
                        "playing seek to 30 seconds double-counted its base");
                final long nearEndSeekStart = level.getGameTime();
                helper.runAfterDelay(2, () -> {
                    final long nearEndTicks = level.getGameTime() - nearEndSeekStart;
                    final long nearEndOffset = GoldenCartPlayback.currentOffsetMs(cart);
                    helper.assertTrue(nearEndTicks > 0L
                                    && nearEndOffset == 30_000L + nearEndTicks * 50L
                                    && nearEndOffset < TRACK_DURATION_MS
                                    && GoldenCartPlayback.isPlaying(cart)
                                    && !cart.deck().isAutomationFinished(),
                            "30-second seek ended the 45-second track as if its offset were 60 seconds");

                    GoldenCartPlayback.seekTo(cart, TRACK_DURATION_MS);
                    helper.runAfterDelay(2, () -> {
                        helper.assertTrue(!GoldenCartPlayback.isPlaying(cart)
                                        && cart.deck().isAutomationFinished(),
                                "natural end did not stop and mark the completed cart track");
                        cart.discard();
                        helper.succeed();
                    });
                });
            });
        });
    }

    /**
     * 盤を積んだまま壊したトロッコの item が、保存形式へ書けて同じ内容で読み戻せること。
     * 1.21.1〜1.21.10 の ENTITY_DATA は id の無い tag を保存時に拒否するので、ここが例外になると
     * 地面・チェスト・player data の保存ごと失敗する (通信だけでは検査されない)。
     */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    *///?}
    public static void dropStackSavesAndReloadsWithItsMedia(GameTestHelper helper) {
        final var level = helper.getLevel();
        final BlockPos cartPos = helper.absolutePos(new BlockPos(2, 1, 2));
        final GoldenJukeboxMinecartEntity cart = new GoldenJukeboxMinecartEntity(
                level, cartPos.getX() + 0.5, cartPos.getY(), cartPos.getZ() + 0.5);
        helper.assertTrue(level.addFreshEntity(cart), "golden jukebox minecart was not added");
        cart.getItemStacks().set(GoldenJukeboxMinecartEntity.SLOT_MEDIA, disc(helper));
        final ItemStack drop;
        try {
            final var method = GoldenJukeboxMinecartEntity.class.getDeclaredMethod("createDropStack");
            method.setAccessible(true);
            drop = (ItemStack) method.invoke(cart);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("createDropStack is not reachable", error);
        } finally {
            cart.discard();
        }
        helper.assertTrue(drop.has(DataComponents.ENTITY_DATA), "cart drop lost its entity data");
        final var ops = level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
        final var saved = ItemStack.CODEC.encodeStart(ops, drop);
        helper.assertTrue(saved.result().isPresent(), "cart drop could not be saved: " + saved);
        final var restored = ItemStack.CODEC.parse(ops, saved.result().orElseThrow());
        helper.assertTrue(restored.result().isPresent() && ItemStack.matches(drop, restored.result().orElseThrow()),
                "cart drop did not reload with the same contents: " + restored);
        helper.succeed();
    }

    private static ItemStack disc(GameTestHelper helper) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), new CustomTrackData(
                "https://example.invalid/cart-offset", "Offset", "GameTest",
                TRACK_DURATION_MS, "", false));
        final var song = helper.getLevel().registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG)
                .getOrThrow(SilentSongs.pick(TRACK_DURATION_MS));
        //? if >=26.1 {
        disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(song));
        //?} elif >=1.21.11 {
        /*disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(new EitherHolder<>(song)));
        *///?}
        //? if <1.21.11 {
        /*disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(new EitherHolder<>(song), false));
        *///?}
        return disc;
    }
}
//?}
