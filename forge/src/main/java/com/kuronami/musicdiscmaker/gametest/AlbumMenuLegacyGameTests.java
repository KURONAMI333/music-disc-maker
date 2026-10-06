package com.kuronami.musicdiscmaker.gametest;

import java.util.ArrayList;
import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.menu.AlbumMenu;
import com.kuronami.musicdiscmaker.menu.AlbumMenuLayout;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/** Forge 1.20.1でAlbum menuとNBT保存を同じ実server経路から保全する。 */
@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
public final class AlbumMenuLegacyGameTests {
    private AlbumMenuLegacyGameTests() { }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void menuShowsLegacyThirteenAcrossBothRowsAndPreservesOrder(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        final List<ItemStack> original = new ArrayList<>();
        for (int i = 0; i < 13; i++) original.add(disc(i));
        AlbumItem.setContents(album, new AlbumContents(original));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final AlbumMenu menu = new AlbumMenu(1, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        helper.assertTrue(AlbumItem.contents(album).size() == 13
                        && menu.slots.get(9).hasItem() && menu.slots.get(12).hasItem()
                        && "track-9".equals(CustomMusicDiscItem.getTrack(
                                AlbumItem.contents(album).discAt(9)).title()),
                "opening Album discarded legacy NBT, the 10th track, or the second row");
        final ItemStack removed = menu.quickMoveStack(player, 0);
        helper.assertTrue(ItemStack.matches(removed, original.get(0))
                        && menu.slots.get(0).getItem().isEmpty()
                        && ItemStack.matches(menu.slots.get(1).getItem(), original.get(1))
                        && ItemStack.matches(menu.slots.get(2).getItem(), original.get(2))
                        && menu.getCarried().isEmpty()
                        && AlbumItem.contents(album).size() == 12 && totalTracks(player, menu) == 13,
                "taking a legacy disc changed the visible slots, cursor, or total count");
        helper.assertTrue("track-12".equals(CustomMusicDiscItem.getTrack(AlbumItem.contents(album).discAt(11)).title()),
                "taking a disc discarded the 13th legacy disc");

        // 13枚は18枠に収まりoverflowがない。保存値は空を除くが、同じ画面の枠0は空のままなので、
        // track-1とtrack-2が実際に残っている枠1/2を交換する。
        menu.clicked(1, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.slots.get(1).getItem().isEmpty()
                        && ItemStack.matches(menu.slots.get(2).getItem(), original.get(2))
                        && ItemStack.matches(menu.getCarried(), original.get(1))
                        && totalTracks(player, menu) == 13,
                "picking the first remaining legacy disc changed the other slot, cursor, or total");
        menu.clicked(2, 0, ClickType.PICKUP, player);
        helper.assertTrue(ItemStack.matches(menu.slots.get(2).getItem(), original.get(1))
                        && menu.slots.get(1).getItem().isEmpty()
                        && ItemStack.matches(menu.getCarried(), original.get(2))
                        && totalTracks(player, menu) == 13,
                "swapping legacy discs changed the visible slot, cursor, or total");
        menu.clicked(1, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.slots.get(0).getItem().isEmpty()
                        && ItemStack.matches(menu.slots.get(1).getItem(), original.get(2))
                        && ItemStack.matches(menu.slots.get(2).getItem(), original.get(1))
                        && menu.getCarried().isEmpty()
                        && AlbumItem.contents(album).size() == 12 && totalTracks(player, menu) == 13,
                "finishing the legacy reorder changed the visible slots, cursor, or total");
        helper.assertTrue("track-2".equals(CustomMusicDiscItem.getTrack(AlbumItem.contents(album).discAt(0)).title()),
                "reordered legacy menu slots were not persisted");
        helper.assertTrue("track-1".equals(CustomMusicDiscItem.getTrack(AlbumItem.contents(album).discAt(1)).title()),
                "legacy menu reorder did not move both discs");
        helper.assertTrue("track-12".equals(CustomMusicDiscItem.getTrack(AlbumItem.contents(album).discAt(11)).title()),
                "legacy menu did not preserve the final disc order");
        for (int i = 2; i < 12; i++) helper.assertTrue(
                ItemStack.matches(AlbumItem.contents(album).discAt(i), original.get(i + 1)),
                "legacy reorder changed an untouched saved disc: " + i);
        final AlbumMenu reopened = new AlbumMenu(2, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        helper.assertTrue(reopened.getCarried().isEmpty() && AlbumItem.contents(album).size() == 12
                        && totalTracks(player, reopened) == 13
                        && ItemStack.matches(reopened.slots.get(0).getItem(), original.get(2))
                        && ItemStack.matches(reopened.slots.get(1).getItem(), original.get(1))
                        && ItemStack.matches(reopened.slots.get(11).getItem(), original.get(12)),
                "reopening legacy Album lost the reordered NBT, last disc, cursor, or total");
        for (int i = 0; i < 12; i++) helper.assertTrue(
                ItemStack.matches(reopened.slots.get(i).getItem(), AlbumItem.contents(album).discAt(i)),
                "reopened legacy menu differs from saved disc order: " + i);
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void eighteenthDiscUsesSecondRowAndRemainsTakeable(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final List<ItemStack> stored = new ArrayList<>();
        for (int i = 0; i < AlbumItem.GUI_CAPACITY; i++) stored.add(disc(i));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(stored));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final AlbumMenu menu = new AlbumMenu(2, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));

        helper.assertTrue(menu.slots.size() == AlbumItem.GUI_CAPACITY + 36
                        && menu.slots.get(9).hasItem()
                        && menu.slots.get(17).hasItem()
                        && "track-17".equals(CustomMusicDiscItem.getTrack(
                                AlbumItem.contents(album).discAt(17)).title())
                        && menu.quickMoveStack(player, -1).isEmpty()
                        && menu.quickMoveStack(player, menu.slots.size()).isEmpty(),
                "legacy 18-slot menu did not construct its second row and inventory");
        final ItemStack removed = menu.quickMoveStack(player, 17);
        helper.assertTrue("track-17".equals(CustomMusicDiscItem.getTrack(removed).title())
                        && AlbumItem.contents(album).size() == 17,
                "legacy could not take the 18th disc from the second row");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void normalClickStoresAndTakesEighteenthDisc(GameTestHelper helper) {
        helper.assertTrue(AlbumMenuLayout.CAPACITY == 18, "Album product capacity is no longer 18");
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final List<ItemStack> stored = new ArrayList<>();
        for (int i = 0; i < 17; i++) stored.add(disc(i));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(stored));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final AlbumMenu menu = new AlbumMenu(5, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));

        menu.setCarried(disc(17));
        menu.clicked(17, 0, ClickType.PICKUP, player);
        helper.assertTrue(menu.getCarried().isEmpty()
                        && menu.slots.get(17).hasItem()
                        && AlbumItem.contents(album).size() == 18
                        && "track-17".equals(CustomMusicDiscItem.getTrack(
                                AlbumItem.contents(album).discAt(17)).title()),
                "legacy normal click could not store the 18th disc in the last second-row slot");

        menu.clicked(17, 0, ClickType.PICKUP, player);
        helper.assertTrue("track-17".equals(CustomMusicDiscItem.getTrack(menu.getCarried()).title())
                        && AlbumItem.contents(album).size() == 17
                        && "track-16".equals(CustomMusicDiscItem.getTrack(
                                AlbumItem.contents(album).discAt(16)).title()),
                "legacy normal click taking the last second-row slot changed order or content");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void shiftClickFillsAndTakesEighteenthDisc(GameTestHelper helper) {
        helper.assertTrue(AlbumMenuLayout.CAPACITY == 18, "Album product capacity is no longer 18");
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final List<ItemStack> stored = new ArrayList<>();
        for (int i = 0; i < 17; i++) stored.add(disc(i));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(stored));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        player.getInventory().setItem(1, disc(17));
        final AlbumMenu menu = new AlbumMenu(6, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));

        helper.assertTrue(!menu.quickMoveStack(player, inventoryMenuSlot(1)).isEmpty()
                        && player.getInventory().getItem(1).isEmpty()
                        && menu.slots.get(17).hasItem()
                        && AlbumItem.contents(album).size() == 18
                        && "track-17".equals(CustomMusicDiscItem.getTrack(
                                AlbumItem.contents(album).discAt(17)).title()),
                "legacy shift-click could not store the 18th disc in the last second-row slot");
        final ItemStack removed = menu.quickMoveStack(player, 17);
        helper.assertTrue("track-17".equals(CustomMusicDiscItem.getTrack(removed).title())
                        && AlbumItem.contents(album).size() == 17
                        && "track-16".equals(CustomMusicDiscItem.getTrack(
                                AlbumItem.contents(album).discAt(16)).title()),
                "legacy shift-click taking the last second-row slot changed order or content");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void menuRejectsBadInputAndProtectsSourceClicks(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        player.getInventory().setItem(1, disc(0));
        player.getInventory().setItem(2, new ItemStack(ModItems.BOOMBOX.get()));
        player.setItemInHand(InteractionHand.OFF_HAND, disc(1));
        final AlbumMenu menu = new AlbumMenu(2, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        menu.clicked(inventoryMenuSlot(1), 0, ClickType.PICKUP, player);
        menu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(AlbumItem.contents(album).size() == 1, "legacy normal click did not insert a disc");
        helper.assertTrue(!menu.quickMoveStack(player, 0).isEmpty(), "legacy normal-click disc could not be taken back");
        player.getInventory().setItem(1, disc(0));
        helper.assertTrue(!menu.quickMoveStack(player, inventoryMenuSlot(1)).isEmpty(), "legacy shift-click did not insert a disc");
        helper.assertTrue(menu.quickMoveStack(player, inventoryMenuSlot(2)).isEmpty(), "legacy Album accepted Boombox input");
        final int sourceSlot = sourceMenuSlot(menu, album);
        menu.clicked(sourceSlot, 0, ClickType.PICKUP, player);
        menu.clicked(sourceSlot, 1, ClickType.SWAP, player);
        menu.clicked(sourceSlot, 40, ClickType.SWAP, player);
        menu.clicked(sourceSlot, 1, ClickType.THROW, player);
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND) == album,
                "legacy source Album moved through a protected click route");
        helper.assertTrue(AlbumItem.contents(album).size() == 1, "protected source clicks changed stored content");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void staleMenuCannotSaveIntoReplacement(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final ItemStack original = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(original, new AlbumContents(List.of(disc(0), disc(1))));
        player.setItemInHand(InteractionHand.MAIN_HAND, original);
        final AlbumMenu menu = new AlbumMenu(3, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        final ItemStack replacement = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(replacement, new AlbumContents(List.of(disc(9))));
        player.setItemInHand(InteractionHand.MAIN_HAND, replacement);
        helper.assertTrue(menu.quickMoveStack(player, 0).isEmpty(), "stale legacy menu accepted delayed shift-click");
        helper.assertTrue(AlbumItem.contents(replacement).size() == 1, "stale legacy menu wrote into replacement Album");
        helper.assertTrue(AlbumItem.contents(original).size() == 2, "stale legacy menu changed original Album");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void reverseHotbarSwapPreservesSourceAndMetadata(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(disc(0))));
        final ItemStack inventoryDisc = disc(1);
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        player.getInventory().setItem(1, inventoryDisc);
        final AlbumMenu menu = new AlbumMenu(4, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        final int tracksBefore = totalTracks(player, menu);
        menu.clicked(inventoryMenuSlot(1), 0, ClickType.SWAP, player);
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND) == album,
                "legacy reverse hotbar swap moved source Album");
        helper.assertTrue(ItemStack.matches(player.getInventory().getItem(1), inventoryDisc)
                        && menu.getCarried().isEmpty() && totalTracks(player, menu) == tracksBefore,
                "legacy reverse hotbar swap changed disc metadata, cursor, or total");
        helper.assertTrue("track-0".equals(CustomMusicDiscItem.getTrack(AlbumItem.contents(album).discAt(0)).title()),
                "legacy reverse hotbar swap changed Album metadata");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void reverseOffhandSwapAndInvalidClicksPreserveAll(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(disc(0))));
        final ItemStack inventoryDisc = disc(1);
        player.setItemInHand(InteractionHand.OFF_HAND, album);
        player.getInventory().setItem(1, inventoryDisc);
        final AlbumMenu menu = new AlbumMenu(5, player.getInventory(), BoomboxSource.held(InteractionHand.OFF_HAND));
        final int tracksBefore = totalTracks(player, menu);
        menu.clicked(inventoryMenuSlot(1), 40, ClickType.SWAP, player);
        helper.assertTrue(player.getItemInHand(InteractionHand.OFF_HAND) == album,
                "legacy reverse offhand swap moved source Album");
        helper.assertTrue(ItemStack.matches(player.getInventory().getItem(1), inventoryDisc)
                        && menu.getCarried().isEmpty() && totalTracks(player, menu) == tracksBefore,
                "legacy reverse offhand swap changed disc metadata, cursor, or total");

        final ItemStack replacement = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(replacement, new AlbumContents(List.of(disc(9))));
        player.setItemInHand(InteractionHand.OFF_HAND, replacement);
        menu.clicked(inventoryMenuSlot(1), 0, ClickType.PICKUP, player);
        menu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(player.getItemInHand(InteractionHand.OFF_HAND) == replacement
                        && ItemStack.matches(player.getInventory().getItem(1), inventoryDisc)
                        && menu.getCarried().isEmpty(),
                "legacy invalid menu click changed Album, inventory, or cursor");
        helper.assertTrue(AlbumItem.contents(album).size() == 1 && AlbumItem.contents(replacement).size() == 1,
                "legacy invalid menu click changed persisted Albums");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 200)
    public static void storageBudgetRejectsEveryInsertPathWithoutLoss(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        final List<ItemStack> stored = new ArrayList<>();
        for (int i = 0; i < 8; i++) stored.add(longDisc(i, 1));
        AlbumItem.setContents(album, new AlbumContents(stored));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final AlbumMenu menu = new AlbumMenu(7, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        final ItemStack oversized = longDisc(10_000, 25);

        menu.setCarried(oversized.copy());
        menu.clicked(8, 0, ClickType.PICKUP, player);
        helper.assertTrue(ItemStack.matches(menu.getCarried(), oversized)
                        && AlbumItem.contents(album).size() == 8,
                "legacy normal-click rejection lost the cursor or Album contents");

        menu.setCarried(ItemStack.EMPTY);
        player.getInventory().setItem(1, oversized.copy());
        final ItemStack retainedCarried = longDisc(20_000, 1);
        menu.setCarried(retainedCarried.copy());
        helper.assertTrue(menu.quickMoveStack(player, inventoryMenuSlot(1)).isEmpty()
                        && ItemStack.matches(player.getInventory().getItem(1), oversized)
                        && ItemStack.matches(menu.getCarried(), retainedCarried)
                        && AlbumItem.contents(album).size() == 8,
                "legacy shift-click rejection lost the inventory, cursor, or Album contents");

        menu.setCarried(ItemStack.EMPTY);
        player.getInventory().setItem(2, oversized.copy());
        menu.clicked(0, 2, ClickType.SWAP, player);
        helper.assertTrue(ItemStack.matches(player.getInventory().getItem(2), oversized)
                        && AlbumItem.contents(album).size() == 8,
                "legacy number-key rejection lost the inventory or Album contents");

        final ItemStack smallAlbum = new ItemStack(ModItems.ALBUM.get());
        player.getInventory().setItem(1, ItemStack.EMPTY);
        player.getInventory().setItem(2, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.MAIN_HAND, smallAlbum);
        final AlbumMenu smallMenu = new AlbumMenu(8, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        final ItemStack small = disc(42);
        smallMenu.setCarried(small.copy());
        smallMenu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(smallMenu.getCarried().isEmpty() && AlbumItem.contents(smallAlbum).size() == 1,
                "legacy normal-click rejected a small disc");
        helper.assertTrue(!smallMenu.quickMoveStack(player, 0).isEmpty(), "legacy could not take a small disc");
        player.getInventory().setItem(1, small.copy());
        helper.assertTrue(!smallMenu.quickMoveStack(player, inventoryMenuSlot(1)).isEmpty(), "legacy shift-click rejected a small disc");
        helper.assertTrue(!smallMenu.quickMoveStack(player, 0).isEmpty(), "legacy could not take a shift-clicked disc");
        player.getInventory().setItem(2, small.copy());
        smallMenu.clicked(0, 2, ClickType.SWAP, player);
        helper.assertTrue(AlbumItem.contents(smallAlbum).size() == 1
                        && player.getInventory().getItem(2).isEmpty(),
                "legacy number-key swap rejected a small disc");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 200)
    public static void menuRecoversOverflowInSameSession(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final List<ItemStack> stored = new ArrayList<>();
        for (int i = 0; i < 21; i++) stored.add(disc(i));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(stored));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final AlbumMenu menu = new AlbumMenu(9, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));

        for (int i = 0; i < 21; i++) {
            final ItemStack removed = menu.quickMoveStack(player, firstStoredSlot(menu));
            helper.assertTrue(!removed.isEmpty()
                            && ("track-" + i).equals(CustomMusicDiscItem.getTrack(removed).title())
                            && AlbumItem.contents(album).size() == 20 - i,
                    "legacy同一menuでoverflow盤を保存順に回収できない: " + i);
            if (i < 20) {
                helper.assertTrue(("track-" + (i + 1)).equals(CustomMusicDiscItem.getTrack(
                                AlbumItem.contents(album).discAt(0)).title()),
                        "legacy補充後の先頭盤が保存順でない: " + i);
            }
        }
        helper.succeed();
    }

    private static int inventoryMenuSlot(int inventorySlot) {
        return AlbumItem.GUI_CAPACITY + 27 + inventorySlot;
    }

    private static int firstStoredSlot(AlbumMenu menu) {
        for (int i = 0; i < AlbumItem.GUI_CAPACITY; i++) {
            if (menu.slots.get(i).hasItem()) return i;
        }
        throw new AssertionError("legacy Album内に取り出せる盤がない");
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 200)
    public static void storageDecodeLimitRejectsInsertionButKeepsExistingContents(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final List<ItemStack> stored = new ArrayList<>();
        for (int i = 0; i < AlbumContents.STORAGE_DECODE_LIMIT; i++) stored.add(disc(i));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(stored));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        player.getInventory().setItem(1, disc(1_000));
        final AlbumMenu menu = new AlbumMenu(9, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));

        helper.assertTrue(menu.quickMoveStack(player, inventoryMenuSlot(1)).isEmpty()
                        && !player.getInventory().getItem(1).isEmpty()
                        && AlbumItem.contents(album).size() == AlbumContents.STORAGE_DECODE_LIMIT,
                "legacy 256枚Albumへの投入拒否で既存データまたは投入盤を失った");
        helper.assertTrue(!menu.quickMoveStack(player, 0).isEmpty()
                        && AlbumItem.contents(album).size() == AlbumContents.STORAGE_DECODE_LIMIT - 1,
                "legacy 256枚Albumから既存の盤を取り出せない");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 200)
    public static void initialMenuSyncRejectsUnsafeExistingAlbum(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final List<ItemStack> safeDiscs = new ArrayList<>();
        for (int i = 0; i < AlbumItem.GUI_CAPACITY; i++) safeDiscs.add(longDisc(i, 1));
        final ItemStack safeAlbum = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(safeAlbum, new AlbumContents(safeDiscs));
        player.setItemInHand(InteractionHand.MAIN_HAND, safeAlbum);
        helper.assertTrue(AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND)),
                "legacy安全な既存Albumの初回menu同期を拒否した");

        for (int i = 0; i < 3; i++) player.getInventory().setItem(9 + i, longDisc(5_000 + i, 6));
        helper.assertTrue(AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND)),
                "legacy所持品の大きい盤だけで安全なAlbumの初回同期を拒否した");
        final AlbumMenu splitMenu = new AlbumMenu(10, player.getInventory(),
                BoomboxSource.held(InteractionHand.MAIN_HAND));
        final InitialMenuSyncCapture capture = new InitialMenuSyncCapture();
        splitMenu.setSynchronizer(capture);
        helper.assertTrue(capture.initial.size() == splitMenu.slots.size()
                        && capture.initial.subList(AlbumItem.GUI_CAPACITY, capture.initial.size()).stream()
                                .allMatch(ItemStack::isEmpty),
                "legacy初回一括同期へplayer inventoryを残した");
        for (int i = 0; i < 3; i++) {
            final ItemStack expected = player.getInventory().getItem(9 + i);
            helper.assertTrue(capture.slotChanges.stream().anyMatch(change -> ItemStack.matches(change.stack(), expected)),
                    "legacy分割slot同期で大きい所持品を復元しなかった: " + i);
        }
        helper.assertTrue(ItemStack.matches(player.getItemInHand(InteractionHand.MAIN_HAND), safeAlbum),
                "legacy初回同期の分割がserver inventoryを変更した");

        final ItemStack carried = longDisc(6_000, 6);
        splitMenu.setCarried(carried);
        capture.clearDeltas();
        splitMenu.sendAllDataToRemote();
        helper.assertTrue(capture.initialCalls == 2 && capture.initialCarried.isEmpty()
                        && capture.initial.subList(AlbumItem.GUI_CAPACITY, capture.initial.size()).stream()
                                .allMatch(ItemStack::isEmpty),
                "legacy強制再同期でplayer inventoryまたはcursorを一括packetへ戻した");
        for (int i = 0; i < 3; i++) {
            final ItemStack expected = player.getInventory().getItem(9 + i);
            helper.assertTrue(capture.slotChanges.stream().anyMatch(change -> ItemStack.matches(change.stack(), expected)),
                    "legacy強制再同期後のslot分割で大きい所持品を復元しなかった: " + i);
        }
        helper.assertTrue(capture.carriedChanges.stream().anyMatch(stack -> ItemStack.matches(stack, carried))
                        && ItemStack.matches(splitMenu.getCarried(), carried),
                "legacy強制再同期後のcursor分割が復元されないかserver値を変更した");

        capture.clearDeltas();
        splitMenu.broadcastChanges();
        helper.assertTrue(capture.slotChanges.isEmpty() && capture.carriedChanges.isEmpty(),
                "legacy強制再同期後のremote snapshotが更新されず同じ差分を再送した");
        final ItemStack changedSlot = disc(7_000);
        final ItemStack changedCarried = disc(7_001);
        player.getInventory().setItem(9, changedSlot);
        splitMenu.setCarried(changedCarried);
        splitMenu.broadcastChanges();
        helper.assertTrue(capture.slotChanges.stream().anyMatch(change -> ItemStack.matches(change.stack(), changedSlot))
                        && capture.carriedChanges.stream().anyMatch(stack -> ItemStack.matches(stack, changedCarried)),
                "legacy強制再同期後の次のslot/cursor差分を送信しなかった");

        final List<ItemStack> unsafeDiscs = new ArrayList<>();
        for (int i = 0; i < AlbumItem.GUI_CAPACITY; i++) unsafeDiscs.add(longDisc(10_000 + i * 10, 6));
        final ItemStack unsafeAlbum = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(unsafeAlbum, new AlbumContents(unsafeDiscs));
        player.setItemInHand(InteractionHand.MAIN_HAND, unsafeAlbum);
        helper.assertTrue(!AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND))
                        && AlbumItem.contents(unsafeAlbum).size() == AlbumItem.GUI_CAPACITY,
                "legacy危険な既存Albumの初回menu同期を許可したか保存値を変えた");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void nonHeldSourcesDisableMenuInsteadOfThrowing(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(disc(0))));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        player.getInventory().setItem(1, disc(1));

        // AlbumMenuを開けるsourceはheldだけ。設置・装備・wire由来の任意intが来ても
        // heldHand()の例外でclientを切断せず、stillValid=falseで全操作を拒否することが回帰条件。
        int id = 100;
        for (final BoomboxSource source : List.of(
                BoomboxSource.placed(BlockPos.ZERO),
                BoomboxSource.equipped("back", 0, 1L),
                new BoomboxSource(BlockPos.ZERO, 7, "", -1, 0L))) {
            final AlbumMenu menu = new AlbumMenu(id++, player.getInventory(), source);
            helper.assertTrue(!menu.stillValid(player), "非held sourceのmenuが有効扱いになった");
            helper.assertTrue(menu.quickMoveStack(player, 0).isEmpty(),
                    "非held sourceのmenuでshift-clickが盤を動かした");
            menu.clicked(inventoryMenuSlot(1), 40, ClickType.SWAP, player);
            helper.assertTrue(!AlbumMenu.fitsInitialMenuSync(player.getInventory(), source),
                    "非held sourceが初回menu同期の検証を通過した");
        }
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND) == album
                        && AlbumItem.contents(album).size() == 1
                        && player.getInventory().getItem(1).is(ModItems.CUSTOM_MUSIC_DISC.get()),
                "非held sourceのmenuがAlbumまたは所持品を変更した");
        helper.succeed();
    }

    private static int sourceMenuSlot(AlbumMenu menu, ItemStack source) {
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.slots.get(i).getItem() == source) return i;
        }
        throw new AssertionError("opened Album source slot not found");
    }

    private static int totalTracks(FakePlayer player, AlbumMenu menu) {
        int total = menu.getCarried().is(ModItems.CUSTOM_MUSIC_DISC.get()) ? menu.getCarried().getCount() : 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            final ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(ModItems.CUSTOM_MUSIC_DISC.get())) total += stack.getCount();
        }
        for (ItemStack stack : AlbumItem.contents(player.getItemInHand(InteractionHand.MAIN_HAND)).discs()) {
            if (stack.is(ModItems.CUSTOM_MUSIC_DISC.get())) total += stack.getCount();
        }
        for (ItemStack stack : AlbumItem.contents(player.getItemInHand(InteractionHand.OFF_HAND)).discs()) {
            if (stack.is(ModItems.CUSTOM_MUSIC_DISC.get())) total += stack.getCount();
        }
        return total;
    }

    private static ItemStack disc(int index) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        CustomMusicDiscItem.setTrack(disc, new CustomTrackData("https://example.invalid/" + index,
                "track-" + index, "test", 1_000L, "", false));
        return disc;
    }

    private static ItemStack longDisc(int start, int sizeClass) {
        final ItemStack stack = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        CustomMusicDiscItem.setTrack(stack, new CustomTrackData("https://example.invalid/" + start, "track-" + start, "test", 180_000L, "", false));
        // Extra item metadata exercises the synchronization limit without a multi-track disc.
        stack.getOrCreateTag().putByteArray("fixture_padding", new byte[sizeClass == 1 ? 16 * 1024 : 600 * 1024]);
        return stack;
    }
}
