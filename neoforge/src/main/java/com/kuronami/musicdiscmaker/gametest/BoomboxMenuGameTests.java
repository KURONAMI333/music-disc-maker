package com.kuronami.musicdiscmaker.gametest;

import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.block.BoomboxBlockEntity;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
//? if >=26.1 {
import net.minecraft.world.inventory.ContainerInput;
//?} else {
/*import net.minecraft.world.inventory.ClickType;
*///?}
//? if <1.21.2 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}

/** BoomboxMenu の手持ち保存、source保護、同期予算を server 実経路で確認する。 */
public final class BoomboxMenuGameTests {
    private BoomboxMenuGameTests() { }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void storesAndTakesSingleMedium(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack boombox = new ItemStack(ModItems.BOOMBOX.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, boombox);
        final BoomboxMenu menu = new BoomboxMenu(101, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        final ItemStack disc = disc(1);
        menu.setCarried(disc.copy());
        pickup(menu, 0, player);
        helper.assertTrue(menu.getCarried().isEmpty() && ItemStack.matches(contents(boombox).disc(), disc)
                        && menu.currentTrack() != null && "track-1".equals(menu.currentTrack().title()),
                "通常投入で媒体がBoomboxへ保存されない");
        helper.assertTrue(!menu.quickMoveStack(player, 0).isEmpty() && contents(boombox).disc().isEmpty(),
                "shift-clickでBoomboxから媒体を取り出せない");
        player.getInventory().setItem(9, disc.copy());
        helper.assertTrue(!menu.quickMoveStack(player, 1).isEmpty() && ItemStack.matches(contents(boombox).disc(), disc),
                "shift-clickで媒体をBoomboxへ投入できない");
        player.getInventory().setItem(2, disc(5));
        swap(menu, 0, 2, player);
        helper.assertTrue("track-5".equals(contents(boombox).disc().get(ModDataComponents.CUSTOM_TRACK.get()).title())
                        && "track-1".equals(player.getInventory().getItem(2).get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                "数字キーswapが媒体とhotbarのmetadataを保全しない");
        BoomboxPlayback.stop(helper.getLevel(), player.chunkPosition(), contents(boombox).id());
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void insertingMediumStartsCarriedAndPlacedAndCanPause(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack carried = new ItemStack(ModItems.BOOMBOX.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, carried);
        long carriedId = 0L;
        long placedId = 0L;
        try {
            final BoomboxMenu carriedMenu = new BoomboxMenu(108, player.getInventory(),
                    BoomboxSource.held(InteractionHand.MAIN_HAND));
            carriedMenu.setCarried(disc(10));
            pickup(carriedMenu, 0, player);
            carriedId = contents(carried).id();
            final var carriedState = BoomboxPlayback.stateOf(carried);
            helper.assertTrue(carriedState.cursor().state() == PlaybackCursor.State.PLAYING
                            && BoomboxPlayback.isPlaying(carriedId),
                    "手持ちBoomboxへの媒体投入が再生を開始しない");
            helper.assertTrue(BoomboxPlayback.pauseCarried(player, carried, carriedState.cursor().generation())
                            && BoomboxPlayback.stateOf(carried).cursor().state() == PlaybackCursor.State.PAUSED
                            && !BoomboxPlayback.isPlaying(carriedId),
                    "投入後の手持ちBoomboxで一時停止できない");
            new BoomboxMenu(110, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
            helper.assertTrue(BoomboxPlayback.stateOf(carried).cursor().state() == PlaybackCursor.State.PAUSED
                            && !BoomboxPlayback.isPlaying(carriedId),
                    "同じ盤の手持ちメニュー再開が手動pauseを再生へ戻した");
            carriedMenu.setCarried(disc(12));
            pickup(carriedMenu, 0, player);
            helper.assertTrue("track-12".equals(contents(carried).disc().get(ModDataComponents.CUSTOM_TRACK.get()).title())
                            && BoomboxPlayback.stateOf(carried).cursor().state() == PlaybackCursor.State.PLAYING
                            && BoomboxPlayback.isPlaying(carriedId),
                    "手持ちBoomboxの媒体交換が新しい盤を再生しない");
            carriedMenu.setCarried(ItemStack.EMPTY);
            pickup(carriedMenu, 0, player);
            helper.assertTrue(contents(carried).disc().isEmpty() && !BoomboxPlayback.isPlaying(carriedId)
                            && carriedMenu.getCarried().get(ModDataComponents.CUSTOM_TRACK.get()) != null,
                    "手持ちBoomboxからの媒体取出しが停止または媒体返却をしない");
            pickup(carriedMenu, 0, player);
            helper.assertTrue(BoomboxPlayback.stateOf(carried).cursor().state() == PlaybackCursor.State.PLAYING
                            && BoomboxPlayback.isPlaying(carriedId),
                    "取り出した同じ盤の再投入が手持ちBoomboxを再生しない");

            final BlockPos relativePos = new BlockPos(1, 1, 1);
            helper.setBlock(relativePos, ModBlocks.BOOMBOX.get());
            final BlockPos pos = helper.absolutePos(relativePos);
            player.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            final BoomboxBlockEntity placed = (BoomboxBlockEntity) helper.getLevel().getBlockEntity(pos);
            final ItemStack placedStack = new ItemStack(ModItems.BOOMBOX.get());
            placed.setStored(placedStack);
            final BoomboxMenu placedMenu = new BoomboxMenu(109, player.getInventory(), BoomboxSource.placed(pos));
            placedMenu.setCarried(disc(11));
            pickup(placedMenu, 0, player);
            placedId = contents(placed.getStored()).id();
            final var placedState = BoomboxPlayback.stateOf(placed.getStored());
            helper.assertTrue(placedState.cursor().state() == PlaybackCursor.State.PLAYING
                            && BoomboxPlayback.isPlaying(placedId),
                    "設置Boomboxへの媒体投入が再生を開始しない");
            placedMenu.setCarried(disc(13));
            pickup(placedMenu, 0, player);
            helper.assertTrue("track-13".equals(contents(placed.getStored()).disc()
                            .get(ModDataComponents.CUSTOM_TRACK.get()).title())
                            && BoomboxPlayback.stateOf(placed.getStored()).cursor().state() == PlaybackCursor.State.PLAYING
                            && BoomboxPlayback.isPlaying(placedId),
                    "設置Boomboxの媒体交換が新しい盤を再生しない");
            placedMenu.setCarried(ItemStack.EMPTY);
            pickup(placedMenu, 0, player);
            helper.assertTrue(contents(placed.getStored()).disc().isEmpty() && !BoomboxPlayback.isPlaying(placedId)
                            && placedMenu.getCarried().get(ModDataComponents.CUSTOM_TRACK.get()) != null,
                    "設置Boomboxからの媒体取出しが停止または媒体返却をしない");
            helper.succeed();
        } finally {
            BoomboxPlayback.stop(helper.getLevel(), player.chunkPosition(), carriedId);
            if (placedId != 0L) {
                BoomboxPlayback.stop(helper.getLevel(), player.chunkPosition(), placedId);
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void placedSourceAndStorageRejectionPreserveStacks(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final BlockPos relativePos = new BlockPos(1, 1, 1);
        helper.setBlock(relativePos, ModBlocks.BOOMBOX.get());
        final BlockPos pos = helper.absolutePos(relativePos);
        player.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        final BoomboxBlockEntity be = (BoomboxBlockEntity) helper.getLevel().getBlockEntity(pos);
        final ItemStack original = new ItemStack(ModItems.BOOMBOX.get());
        be.setStored(original);
        final BoomboxMenu placed = new BoomboxMenu(103, player.getInventory(), BoomboxSource.placed(pos));
        final ItemStack stored = disc(6);
        placed.setCarried(stored.copy());
        pickup(placed, 0, player);
        helper.assertTrue(ItemStack.matches(contents(original).disc(), stored), "設置Boomboxへ通常投入できない");
        final ItemStack replacement = new ItemStack(ModItems.BOOMBOX.get());
        replacement.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(disc(7), 80L));
        be.setStored(replacement);
        helper.assertTrue(placed.quickMoveStack(player, 0).isEmpty()
                        && "track-6".equals(contents(original).disc().get(ModDataComponents.CUSTOM_TRACK.get()).title())
                        && "track-7".equals(contents(replacement).disc().get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                "設置sourceの置換後に遅いmenu操作が保存先を書き換えた");
        player.setPos(pos.getX() + 20.0, pos.getY() + 0.5, pos.getZ() + 0.5);
        final BoomboxMenu distant = new BoomboxMenu(207, player.getInventory(), BoomboxSource.placed(pos));
        final ItemStack beforeDistant = replacement.copy();
        distant.setCarried(disc(8));
        pickup(distant, 0, player);
        helper.assertTrue(!distant.stillValid(player) && ItemStack.matches(beforeDistant, replacement),
                "player beyond 8 blocks edited the placed machine");
        player.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        final ItemStack held = new ItemStack(ModItems.BOOMBOX.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        final BoomboxMenu menu = new BoomboxMenu(104, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        final ItemStack oversized = oversizedDisc();
        menu.setCarried(oversized.copy());
        pickup(menu, 0, player);
        helper.assertTrue(ItemStack.matches(menu.getCarried(), oversized) && contents(held).disc().isEmpty(),
                "容量拒否でcursorまたは既存媒体を失った");
        menu.setCarried(ItemStack.EMPTY);
        final ItemStack existingLarge = new ItemStack(ModItems.BOOMBOX.get());
        existingLarge.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(oversized, 81L));
        player.setItemInHand(InteractionHand.MAIN_HAND, existingLarge);
        final BoomboxMenu recovery = new BoomboxMenu(105, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        helper.assertTrue(!recovery.quickMoveStack(player, 0).isEmpty() && contents(existingLarge).disc().isEmpty(),
                "既存の大きい媒体を取り出す時にmenu内とinventoryへ複製したか回収を拒否した");
        BoomboxPlayback.stop(helper.getLevel(), player.chunkPosition(), contents(original).id());
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void staleAndReverseSwapPreserveSource(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack original = new ItemStack(ModItems.BOOMBOX.get());
        original.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(disc(2), 44L));
        player.setItemInHand(InteractionHand.MAIN_HAND, original);
        player.getInventory().setItem(1, disc(3));
        final BoomboxMenu menu = new BoomboxMenu(102, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        swap(menu, 1, 0, player);
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND) == original
                        && "track-2".equals(contents(original).disc().get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                "逆方向hotbar swapが元Boomboxまたは媒体を変えた");
        final ItemStack replacement = new ItemStack(ModItems.BOOMBOX.get());
        replacement.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(disc(9), 45L));
        player.setItemInHand(InteractionHand.MAIN_HAND, replacement);
        helper.assertTrue(menu.quickMoveStack(player, 0).isEmpty()
                        && "track-2".equals(contents(original).disc().get(ModDataComponents.CUSTOM_TRACK.get()).title())
                        && "track-9".equals(contents(replacement).disc().get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                "stale menuが旧または置換Boomboxへ保存した");
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void initialSyncKeepsExistingMachineUntouched(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack safe = new ItemStack(ModItems.BOOMBOX.get());
        safe.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(disc(4), 46L));
        player.setItemInHand(InteractionHand.MAIN_HAND, safe);
        helper.assertTrue(BoomboxMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND))
                        && "track-4".equals(contents(safe).disc().get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                "安全な初回同期を拒否したか既存媒体を変えた");
        helper.succeed();
    }

    private static FakePlayer player(GameTestHelper helper) {
        return FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "boombox-menu-test"));
    }

    private static ItemStack disc(int index) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), new CustomTrackData("https://example.invalid/" + index,
                "track-" + index, "test", 1_000L, "", false));
        return disc;
    }

    private static ItemStack oversizedDisc() {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), new CustomTrackData("https://example.invalid/oversized", "title", "test", 1_000L, "", false));
        // Extra item metadata exercises the synchronization limit without a multi-track disc.
        final net.minecraft.nbt.CompoundTag padding = new net.minecraft.nbt.CompoundTag();
        padding.putByteArray("fixture_padding", new byte[1100 * 1024]);
        disc.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(padding));
        return disc;
    }

    private static BoomboxContents contents(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.BOOMBOX_CONTENTS.get(), BoomboxContents.EMPTY);
    }

    //? if >=26.1 {
    private static void pickup(BoomboxMenu menu, int slot, FakePlayer player) {
        menu.clicked(slot, 0, ContainerInput.PICKUP, player);
    }
    private static void swap(BoomboxMenu menu, int slot, int button, FakePlayer player) {
        menu.clicked(slot, button, ContainerInput.SWAP, player);
    }
    //?} else {
    /*private static void pickup(BoomboxMenu menu, int slot, FakePlayer player) {
        menu.clicked(slot, 0, ClickType.PICKUP, player);
    }
    private static void swap(BoomboxMenu menu, int slot, int button, FakePlayer player) {
        menu.clicked(slot, button, ClickType.SWAP, player);
    }
    *///?}
}
