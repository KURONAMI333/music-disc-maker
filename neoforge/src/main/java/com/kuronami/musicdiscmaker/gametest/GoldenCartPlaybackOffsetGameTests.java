package com.kuronami.musicdiscmaker.gametest;

//? if >=1.21 {
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.entity.GoldenJukeboxMinecartEntity;
import com.kuronami.musicdiscmaker.menu.GoldenJukeboxMenu;
import com.kuronami.musicdiscmaker.network.ConfigureJukeboxPayload;
import com.kuronami.musicdiscmaker.network.GoldenCartPlayPayload;
import com.kuronami.musicdiscmaker.network.GoldenCartStopPayload;
import com.kuronami.musicdiscmaker.network.GoldenCartSyncPayload;
import com.kuronami.musicdiscmaker.network.ModNetwork;
import com.kuronami.musicdiscmaker.network.SeekJukeboxPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.phys.AABB;
//? if >=26.1 {
import net.minecraft.world.item.JukeboxSong;
//?} else {
/*import net.minecraft.world.item.EitherHolder;
*///?}
//? if >=1.21.11 {
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
//?} else {
/*import net.minecraft.nbt.CompoundTag;
*///?}
//? if <1.21.11 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
*///?}

/**
 * 金のジュークボックス付きトロッコが「金のジュークボックスが動くだけ」であること (2026-10-09 本人裁定)。
 * 盤を入れると載せた金のジュークボックスが鳴らし、GUI は金のジュークボックスの menu で開き、
 * 画面の操作 (設定・頭出し) は座標ではなく開いている menu の載せた BE へ届き、保存して読み戻せ、
 * 壊すとバニラのチェスト付きトロッコと同じくトロッコと盤を落とす。
 */
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
    public static void insertedDiscPlaysAndTheGoldenJukeboxScreenControlsIt(GameTestHelper helper) {
        final GoldenJukeboxMinecartEntity cart = cart(helper);
        final GoldenJukeboxBlockEntity jukebox = cart.jukebox();
        helper.assertTrue(jukebox.isMounted(), "the cart did not mount a golden jukebox");
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(cart.getX() + 1.0, cart.getY(), cart.getZ());
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            controlledThroughTheGoldenJukeboxScreen(helper, cart, jukebox, player, net);
        } finally {
            Services.swapNetwork(previous);
            player.discard();
            cart.discard();
        }
        helper.succeed();
    }

    private static void controlledThroughTheGoldenJukeboxScreen(GameTestHelper helper,
            GoldenJukeboxMinecartEntity cart, GoldenJukeboxBlockEntity jukebox, ServerPlayer player,
            CapturingNetwork net) {
        cart.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, disc(helper));
        helper.assertTrue(jukebox.hasDisc() && jukebox.isSounding(),
                "inserting a disc into the cart did not start the mounted golden jukebox");
        helper.assertTrue(net.of(GoldenCartPlayPayload.class).stream()
                        .anyMatch(sent -> sent.player() == player
                                && ((GoldenCartPlayPayload) sent.payload()).entityId() == cart.getId()),
                "the mounted golden jukebox did not send its sound to follow the cart");
        final GoldenJukeboxMenu menu = new GoldenJukeboxMenu(7, player.getInventory(), jukebox);
        helper.assertTrue(menu.getType() == ModMenus.GOLDEN_JUKEBOX_CART.get(),
                "the cart did not open the golden jukebox menu under the cart menu type");
        helper.assertTrue(menu.stillValid(player), "a player next to the cart could not use its menu");
        player.containerMenu = menu;
        net.clear();

        // 画面は載せた BE の座標 (世界のブロックではない) を送る。server は開いている menu の BE へ渡す。
        ModNetwork.handleConfigureJukebox(new ConfigureJukeboxPayload(jukebox.getBlockPos(),
                40, 150, true, false, false), player);
        helper.assertTrue(jukebox.getRangeBlocks() == 40 && jukebox.getVolumePercent() == 150
                        && jukebox.isRepeat() && !jukebox.isDirectional(),
                "the golden jukebox screen settings did not reach the cart");
        helper.assertTrue(net.of(GoldenCartSyncPayload.class).stream().anyMatch(sent -> sent.player() == player),
                "the screen of the player using the cart was not refreshed");

        net.clear();
        ModNetwork.handleConfigureJukebox(new ConfigureJukeboxPayload(jukebox.getBlockPos(),
                40, 150, true, true, false), player);
        helper.assertTrue(jukebox.isPaused(), "pause from the golden jukebox screen did not reach the cart");
        helper.assertTrue(net.of(GoldenCartStopPayload.class).stream()
                        .anyMatch(sent -> !((GoldenCartStopPayload) sent.payload()).naturalEnd()),
                "pausing the cart did not stop its sound");

        ModNetwork.handleSeekJukebox(new SeekJukeboxPayload(jukebox.getBlockPos(), 30_000L), player);
        helper.assertTrue(jukebox.isPaused() && jukebox.currentElapsedMs() == 30_000L,
                "a paused seek did not move the cart's resume position");

        player.setPos(cart.getX() + 20.0, cart.getY(), cart.getZ());
        helper.assertTrue(!menu.stillValid(player), "a player far from the cart could still use its menu");
        ModNetwork.handleSeekJukebox(new SeekJukeboxPayload(jukebox.getBlockPos(), 0L), player);
        helper.assertTrue(jukebox.currentElapsedMs() == 30_000L, "a far player still controlled the cart");
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    *///?}
    public static void savesAndReloadsTheMountedJukebox(GameTestHelper helper) {
        withCapturedNetwork(() -> savesAndReloads(helper));
        helper.succeed();
    }

    private static void savesAndReloads(GameTestHelper helper) {
        final var level = helper.getLevel();
        final GoldenJukeboxMinecartEntity cart = cart(helper);
        final GoldenJukeboxBlockEntity jukebox = cart.jukebox();
        cart.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, disc(helper));
        jukebox.setRangeBlocks(48);
        jukebox.setVolumePercent(80);
        jukebox.setRepeat(true);
        jukebox.setPaused(true);
        jukebox.seekTo(12_000L);

        final GoldenJukeboxMinecartEntity restored = new GoldenJukeboxMinecartEntity(
                level, cart.getX(), cart.getY(), cart.getZ());
        //? if >=1.21.11 {
        final TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                level.registryAccess());
        cart.saveWithoutId(output);
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(),
                output.buildResult()));
        //?} else {
        /*restored.load(cart.saveWithoutId(new CompoundTag()));
        *///?}
        cart.discard();

        final GoldenJukeboxBlockEntity reloaded = restored.jukebox();
        helper.assertTrue(reloaded.hasDisc() && reloaded.currentTrack() != null,
                "the reloaded cart lost its disc");
        helper.assertTrue(reloaded.getRangeBlocks() == 48 && reloaded.getVolumePercent() == 80
                        && reloaded.isRepeat() && reloaded.isPaused() && reloaded.currentElapsedMs() == 12_000L,
                "the reloaded cart lost its golden jukebox settings or paused position");
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    *///?}
    public static void breakingDropsTheCartAndTheDisc(GameTestHelper helper) {
        withCapturedNetwork(() -> breaksLikeAChestMinecart(helper));
        helper.succeed();
    }

    private static void breaksLikeAChestMinecart(GameTestHelper helper) {
        final var level = helper.getLevel();
        final GoldenJukeboxMinecartEntity cart = cart(helper);
        cart.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, disc(helper));
        //? if >=1.21.2 {
        cart.destroy(level, level.damageSources().generic());
        //?} else {
        /*cart.destroy(level.damageSources().generic());
        *///?}
        final AABB around = cart.getBoundingBox().inflate(3.0);
        final var drops = level.getEntitiesOfClass(ItemEntity.class, around);
        final boolean cartItem = drops.stream().anyMatch(e -> e.getItem().is(ModItems.GOLDEN_JUKEBOX_MINECART.get()));
        final boolean discItem = drops.stream().anyMatch(e -> e.getItem().is(ModItems.CUSTOM_MUSIC_DISC.get()));
        helper.assertTrue(cart.isRemoved() && cartItem && discItem,
                "breaking the cart did not drop both the cart and its disc: " + drops);
        drops.forEach(ItemEntity::discard);
    }

    /** 同じ level に他のテストの mock player が居ても、MDM の payload を実際には送らない。 */
    private static void withCapturedNetwork(Runnable body) {
        final INetworkHelper previous = Services.swapNetwork(new CapturingNetwork());
        try {
            body.run();
        } finally {
            Services.swapNetwork(previous);
        }
    }

    private static GoldenJukeboxMinecartEntity cart(GameTestHelper helper) {
        final var level = helper.getLevel();
        final BlockPos cartPos = helper.absolutePos(new BlockPos(2, 1, 2));
        final GoldenJukeboxMinecartEntity cart = new GoldenJukeboxMinecartEntity(
                level, cartPos.getX() + 0.5, cartPos.getY(), cartPos.getZ() + 0.5);
        helper.assertTrue(level.addFreshEntity(cart), "golden jukebox minecart was not added");
        return cart;
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
