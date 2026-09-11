package com.kuronami.musicdiscmaker.gametest;

import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.block.BoomboxBlockEntity;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/** Forge 1.20.1 のNBT保存経路で、媒体の出し入れとsource保護を確認する。 */
@net.minecraftforge.gametest.GameTestHolder("music_disc_maker")
@net.minecraftforge.gametest.PrefixGameTestTemplate(false)
public final class BoomboxMenuLegacyGameTests {
    private BoomboxMenuLegacyGameTests() { }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void storesAndProtectsSource(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final ItemStack boombox = new ItemStack(ModItems.BOOMBOX.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, boombox);
        final BoomboxMenu menu = new BoomboxMenu(201, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        final ItemStack disc = disc(1);
        menu.setCarried(disc.copy());
        menu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty() && ItemStack.matches(BoomboxContents.of(boombox).disc(), disc),
                "legacy通常投入で媒体が保存されない");
        helper.assertTrue(!menu.quickMoveStack(player, 0).isEmpty() && BoomboxContents.of(boombox).disc().isEmpty(),
                "legacy shift-clickで媒体を取り出せない");
        player.getInventory().setItem(9, disc.copy());
        helper.assertTrue(!menu.quickMoveStack(player, 1).isEmpty() && ItemStack.matches(BoomboxContents.of(boombox).disc(), disc),
                "legacy shift-clickで媒体を投入できない");
        player.getInventory().setItem(2, disc(2));
        menu.clicked(0, 2, ClickType.SWAP, player);
        helper.assertTrue("track-2".equals(CustomMusicDiscItem.getTrack(BoomboxContents.of(boombox).disc()).title())
                        && "track-1".equals(CustomMusicDiscItem.getTrack(player.getInventory().getItem(2)).title()),
                "legacy数字キーswapが媒体metadataを保全しない");
        menu.clicked(1, 0, ClickType.SWAP, player);
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND) == boombox
                        && "track-2".equals(CustomMusicDiscItem.getTrack(BoomboxContents.of(boombox).disc()).title()),
                "legacy逆方向swapが元Boomboxまたは媒体を変えた");
        final ItemStack offhand = new ItemStack(ModItems.BOOMBOX.get());
        player.setItemInHand(InteractionHand.OFF_HAND, offhand);
        final BoomboxMenu offhandMenu = new BoomboxMenu(203, player.getInventory(), BoomboxSource.held(InteractionHand.OFF_HAND));
        offhandMenu.clicked(1, 40, ClickType.SWAP, player);
        helper.assertTrue(player.getItemInHand(InteractionHand.OFF_HAND) == offhand,
                "legacy逆方向offhand swapが元Boomboxを移動した");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 200)
    public static void placedSourceAndCapacityRejectionPreserveStacks(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final BlockPos relativePos = new BlockPos(1, 1, 1);
        helper.setBlock(relativePos, ModBlocks.BOOMBOX.get());
        final BlockPos pos = helper.absolutePos(relativePos);
        player.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        final BoomboxBlockEntity be = (BoomboxBlockEntity) helper.getLevel().getBlockEntity(pos);
        final ItemStack original = new ItemStack(ModItems.BOOMBOX.get());
        be.setStored(original);
        final BoomboxMenu placed = new BoomboxMenu(204, player.getInventory(), BoomboxSource.placed(pos));
        final ItemStack stored = disc(5);
        placed.setCarried(stored.copy());
        placed.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(ItemStack.matches(BoomboxContents.of(original).disc(), stored),
                "legacy設置Boomboxへ媒体を保存できない");
        final ItemStack replacement = new ItemStack(ModItems.BOOMBOX.get());
        BoomboxContents.store(replacement, new BoomboxContents(disc(6), 73L));
        be.setStored(replacement);
        helper.assertTrue(placed.quickMoveStack(player, 0).isEmpty()
                        && "track-5".equals(CustomMusicDiscItem.getTrack(BoomboxContents.of(original).disc()).title())
                        && "track-6".equals(CustomMusicDiscItem.getTrack(BoomboxContents.of(replacement).disc()).title()),
                "legacy設置source置換後にmenuが別機体を変更した");
        player.setPos(pos.getX() + 20.0, pos.getY() + 0.5, pos.getZ() + 0.5);
        final BoomboxMenu distant = new BoomboxMenu(207, player.getInventory(), BoomboxSource.placed(pos));
        final ItemStack beforeDistant = replacement.copy();
        distant.setCarried(disc(8));
        distant.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(!distant.stillValid(player) && ItemStack.matches(beforeDistant, replacement),
                "player beyond 8 blocks edited the placed machine");
        player.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        final ItemStack held = new ItemStack(ModItems.BOOMBOX.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
        final BoomboxMenu menu = new BoomboxMenu(205, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        final ItemStack oversized = oversizedDisc();
        menu.setCarried(oversized.copy());
        menu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(ItemStack.matches(menu.getCarried(), oversized) && BoomboxContents.of(held).disc().isEmpty(),
                "legacy容量拒否でcursorまたは既存媒体を失った");
        menu.setCarried(ItemStack.EMPTY);
        final ItemStack existingLarge = new ItemStack(ModItems.BOOMBOX.get());
        BoomboxContents.store(existingLarge, new BoomboxContents(oversized, 74L));
        player.setItemInHand(InteractionHand.MAIN_HAND, existingLarge);
        final BoomboxMenu recovery = new BoomboxMenu(206, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        helper.assertTrue(!recovery.quickMoveStack(player, 0).isEmpty() && BoomboxContents.of(existingLarge).disc().isEmpty(),
                "legacy既存の大きい媒体を取り出す時にmenu内とinventoryへ複製したか回収を拒否した");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void staleMenuCannotSaveIntoReplacement(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final ItemStack original = new ItemStack(ModItems.BOOMBOX.get());
        BoomboxContents.store(original, new BoomboxContents(disc(3), 71L));
        player.setItemInHand(InteractionHand.MAIN_HAND, original);
        final BoomboxMenu menu = new BoomboxMenu(202, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        final ItemStack replacement = new ItemStack(ModItems.BOOMBOX.get());
        BoomboxContents.store(replacement, new BoomboxContents(disc(4), 72L));
        player.setItemInHand(InteractionHand.MAIN_HAND, replacement);
        helper.assertTrue(menu.quickMoveStack(player, 0).isEmpty()
                        && "track-3".equals(CustomMusicDiscItem.getTrack(BoomboxContents.of(original).disc()).title())
                        && "track-4".equals(CustomMusicDiscItem.getTrack(BoomboxContents.of(replacement).disc()).title()),
                "stale legacy menuが別のBoomboxへ保存した");
        helper.succeed();
    }

    private static ItemStack disc(int index) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        CustomMusicDiscItem.setTrack(disc, new CustomTrackData("https://example.invalid/" + index,
                "track-" + index, "test", 1_000L, "", false));
        return disc;
    }

    private static ItemStack oversizedDisc() {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        CustomMusicDiscItem.setTrack(disc, new CustomTrackData("https://example.invalid/oversized", "title", "test", 1_000L, "", false));
        // Extra item metadata exercises the synchronization limit without a multi-track disc.
        disc.getOrCreateTag().putByteArray("fixture_padding", new byte[1100 * 1024]);
        return disc;
    }
}
