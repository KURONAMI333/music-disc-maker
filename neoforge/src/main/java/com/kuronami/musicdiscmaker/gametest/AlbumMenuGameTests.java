package com.kuronami.musicdiscmaker.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.AlbumStorageBudget;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.menu.AlbumMenu;
import com.kuronami.musicdiscmaker.menu.AlbumMenuLayout;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
//? if >=26.1 {
import net.minecraft.world.inventory.ContainerInput;
//?} else {
/*import net.minecraft.world.inventory.ClickType;
*///?}
//? if <1.21.11 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}

/** AlbumMenu のserver実経路で、画面外overflowを黙って失わないことを確認する。 */
public final class AlbumMenuGameTests {
    private AlbumMenuGameTests() { }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void openingAndTakingKeepsLegacyThirteen(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        final List<ItemStack> original = new ArrayList<>();
        for (int i = 0; i < 13; i++) original.add(disc(i));
        AlbumItem.setContents(album, new AlbumContents(original));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);

        final AlbumMenu menu = new AlbumMenu(1, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        helper.assertTrue(AlbumItem.contents(album).size() == 13
                        && menu.slots.get(9).hasItem() && menu.slots.get(12).hasItem()
                        && "track-9".equals(AlbumItem.contents(album).discAt(9)
                                .get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                "13枚の旧Albumが保存、10枚目の参照、または2段表示で失われた");
        menu.quickMoveStack(player, 0);
        final AlbumContents after = AlbumItem.contents(album);
        helper.assertTrue(after.size() == 12, "先頭を取り出した後の盤数が12でない");
        helper.assertTrue("track-12".equals(after.discAt(11).get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                "13枚の旧Albumの最後の盤が先頭取出後に失われた");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void eighteenthDiscUsesSecondRowAndRemainsTakeable(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final List<ItemStack> stored = new ArrayList<>();
        for (int i = 0; i < AlbumItem.GUI_CAPACITY; i++) stored.add(disc(i));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(stored));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final AlbumMenu menu = new AlbumMenu(2, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));

        helper.assertTrue(menu.slots.size() == AlbumItem.GUI_CAPACITY + 36
                        && menu.slots.get(9).hasItem()
                        && menu.slots.get(17).hasItem()
                        && "track-17".equals(AlbumItem.contents(album).discAt(17)
                                .get(ModDataComponents.CUSTOM_TRACK.get()).title())
                        && menu.quickMoveStack(player, -1).isEmpty()
                        && menu.quickMoveStack(player, menu.slots.size()).isEmpty(),
                "18枠menuが第二段またはplayer inventoryを正しく構成しない");
        final ItemStack removed = menu.quickMoveStack(player, 17);
        helper.assertTrue("track-17".equals(removed.get(ModDataComponents.CUSTOM_TRACK.get()).title())
                        && AlbumItem.contents(album).size() == 17,
                "18枚目をmenuの第二段から回収できない");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void normalClickStoresAndTakesEighteenthDisc(GameTestHelper helper) {
        helper.assertTrue(AlbumMenuLayout.CAPACITY == 18, "Album product capacity is no longer 18");
        final FakePlayer player = createPlayer(helper);
        final List<ItemStack> stored = new ArrayList<>();
        for (int i = 0; i < 17; i++) stored.add(disc(i));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(stored));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final AlbumMenu menu = new AlbumMenu(2, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));

        menu.setCarried(disc(17));
        pickup(menu, 17, player);
        helper.assertTrue(menu.getCarried().isEmpty()
                        && menu.slots.get(17).hasItem()
                        && AlbumItem.contents(album).size() == 18
                        && "track-17".equals(AlbumItem.contents(album).discAt(17)
                                .get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                "通常クリックで18枚目を2段目の最後の枠へ保存できない");

        pickup(menu, 17, player);
        helper.assertTrue("track-17".equals(menu.getCarried().get(ModDataComponents.CUSTOM_TRACK.get()).title())
                        && AlbumItem.contents(album).size() == 17
                        && AlbumItem.contents(album).discAt(16).get(ModDataComponents.CUSTOM_TRACK.get()).title()
                                .equals("track-16"),
                "2段目の最後の枠を通常クリックで取り出すと順序または内容が壊れる");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void shiftClickFillsAndTakesEighteenthDisc(GameTestHelper helper) {
        helper.assertTrue(AlbumMenuLayout.CAPACITY == 18, "Album product capacity is no longer 18");
        final FakePlayer player = createPlayer(helper);
        final List<ItemStack> stored = new ArrayList<>();
        for (int i = 0; i < 17; i++) stored.add(disc(i));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(stored));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        player.getInventory().setItem(1, disc(17));
        final AlbumMenu menu = new AlbumMenu(3, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));

        helper.assertTrue(!menu.quickMoveStack(player, inventoryMenuSlot(1)).isEmpty()
                        && player.getInventory().getItem(1).isEmpty()
                        && menu.slots.get(17).hasItem()
                        && AlbumItem.contents(album).size() == 18
                        && "track-17".equals(AlbumItem.contents(album).discAt(17)
                                .get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                "shift-clickで18枚目を最後の2段目枠へ保存できない");
        final ItemStack removed = menu.quickMoveStack(player, 17);
        helper.assertTrue("track-17".equals(removed.get(ModDataComponents.CUSTOM_TRACK.get()).title())
                        && AlbumItem.contents(album).size() == 17
                        && "track-16".equals(AlbumItem.contents(album).discAt(16)
                                .get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                "最後の2段目枠をshift-clickで取り出すと順序または内容が壊れる");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void movedSourceCannotSaveIntoReplacement(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final ItemStack original = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(original, new AlbumContents(List.of(disc(0), disc(1))));
        player.setItemInHand(InteractionHand.MAIN_HAND, original);
        final AlbumMenu menu = new AlbumMenu(2, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        pickup(menu, sourceMenuSlot(menu, original), player);
        final ItemStack replacement = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(replacement, new AlbumContents(List.of(disc(9))));
        player.setItemInHand(InteractionHand.MAIN_HAND, replacement);
        menu.quickMoveStack(player, 0);
        helper.assertTrue(AlbumItem.contents(replacement).size() == 1,
                "元Albumを移動後、遅いmenu操作が置換Albumへ保存した");
        helper.assertTrue(AlbumItem.contents(original).size() == 2,
                "元Albumを移動後、遅いmenu操作が元データを壊した");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void storesTakesAndReordersThroughMenu(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final AlbumMenu menu = new AlbumMenu(3, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        player.getInventory().setItem(1, disc(0));
        pickup(menu, inventoryMenuSlot(1), player);
        pickup(menu, 0, player);
        helper.assertTrue(AlbumItem.contents(album).size() == 1, "通常クリックで盤をAlbumへ投入できない");
        helper.assertTrue(!menu.quickMoveStack(player, 0).isEmpty(), "通常投入した盤をAlbumから取り出せない");
        player.getInventory().setItem(1, disc(0));
        helper.assertTrue(!menu.quickMoveStack(player, inventoryMenuSlot(1)).isEmpty(), "shift-clickで盤をAlbumへ投入できない");
        helper.assertTrue(AlbumItem.contents(album).size() == 1, "投入した盤が保存されない");
        player.getInventory().setItem(2, new ItemStack(ModItems.BOOMBOX.get()));
        helper.assertTrue(menu.quickMoveStack(player, inventoryMenuSlot(2)).isEmpty(), "BoomboxをAlbumへ投入できてしまった");
        helper.assertTrue(AlbumItem.contents(album).size() == 1, "拒否入力がAlbumを変えた");

        player.getInventory().setItem(3, disc(1));
        menu.quickMoveStack(player, inventoryMenuSlot(3));
        pickup(menu, 0, player);
        pickup(menu, 2, player);
        pickup(menu, 1, player);
        pickup(menu, 0, player);
        pickup(menu, 2, player);
        pickup(menu, 1, player);
        helper.assertTrue("track-1".equals(AlbumItem.contents(album).discAt(0)
                        .get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                "menu内の並べ替え順が保存されない");
        helper.assertTrue("track-0".equals(AlbumItem.contents(album).discAt(1)
                        .get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                "menu内の並べ替えで2枚目が入れ替わらない");
        helper.assertTrue(!menu.quickMoveStack(player, 0).isEmpty(), "Albumからshift-clickで取り出せない");
        helper.assertTrue(AlbumItem.contents(album).size() == 1, "取り出し後の保存盤数が不正");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void sourceSlotRejectsAllMoveClicks(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        player.getInventory().setItem(1, new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get()));
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get()));
        final AlbumMenu menu = new AlbumMenu(4, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        final int sourceSlot = sourceMenuSlot(menu, album);
        pickup(menu, sourceSlot, player);
        swap(menu, sourceSlot, 1, player);
        swap(menu, sourceSlot, 40, player);
        drop(menu, sourceSlot, player);
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND) == album,
                "通常クリック・数字キー・offhand・dropのいずれかで元Albumを移動できた");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void reverseHotbarSwapPreservesSourceAndMetadata(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(List.of(disc(0))));
        final ItemStack inventoryDisc = disc(1);
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        player.getInventory().setItem(1, inventoryDisc);
        final AlbumMenu menu = new AlbumMenu(5, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        swap(menu, inventoryMenuSlot(1), 0, player);
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND) == album,
                "逆方向の数字キー交換で元Albumがhotbarから移動した");
        helper.assertTrue(ItemStack.matches(player.getInventory().getItem(1), inventoryDisc),
                "逆方向の数字キー交換で所持品の盤metadataが変わった");
        helper.assertTrue(menu.getCarried().isEmpty(), "逆方向の数字キー交換でcursorにstackが残った");
        helper.assertTrue("track-0".equals(AlbumItem.contents(album).discAt(0)
                        .get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                "逆方向の数字キー交換でAlbum内の盤metadataが変わった");
        helper.assertTrue(totalTracks(player, menu) == 2, "逆方向の数字キー交換で盤の総数が変わった");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void reverseOffhandSwapAndInvalidClicksPreserveAll(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final ItemStack offhandAlbum = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(offhandAlbum, new AlbumContents(List.of(disc(0))));
        final ItemStack inventoryDisc = disc(1);
        player.setItemInHand(InteractionHand.OFF_HAND, offhandAlbum);
        player.getInventory().setItem(1, inventoryDisc);
        final AlbumMenu offhandMenu = new AlbumMenu(6, player.getInventory(), BoomboxSource.held(InteractionHand.OFF_HAND));
        swap(offhandMenu, inventoryMenuSlot(1), 40, player);
        helper.assertTrue(player.getItemInHand(InteractionHand.OFF_HAND) == offhandAlbum,
                "逆方向のoffhand交換で元Albumが移動した");
        helper.assertTrue(ItemStack.matches(player.getInventory().getItem(1), inventoryDisc)
                        && offhandMenu.getCarried().isEmpty() && totalTracks(player, offhandMenu) == 2,
                "逆方向のoffhand交換で盤・cursor・inventory総数が保全されない");

        final ItemStack replacement = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(replacement, new AlbumContents(List.of(disc(9))));
        player.setItemInHand(InteractionHand.OFF_HAND, replacement);
        pickup(offhandMenu, inventoryMenuSlot(1), player);
        pickup(offhandMenu, 0, player);
        helper.assertTrue(player.getItemInHand(InteractionHand.OFF_HAND) == replacement
                        && ItemStack.matches(player.getInventory().getItem(1), inventoryDisc)
                        && offhandMenu.getCarried().isEmpty(),
                "無効menuの通常クリックがAlbum・所持品・cursorを変えた");
        helper.assertTrue(AlbumItem.contents(offhandAlbum).size() == 1
                        && AlbumItem.contents(replacement).size() == 1,
                "無効menuの通常クリックがAlbum保存値を変えた");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void storageBudgetRejectsEveryInsertPathWithoutLoss(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        final List<ItemStack> stored = new ArrayList<>();
        for (int i = 0; i < 8; i++) stored.add(longDisc(i, 1));
        AlbumItem.setContents(album, new AlbumContents(stored));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final AlbumMenu menu = new AlbumMenu(7, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        final ItemStack oversized = longDisc(10_000, 25);

        menu.setCarried(oversized.copy());
        pickup(menu, 8, player);
        helper.assertTrue(ItemStack.matches(menu.getCarried(), oversized)
                        && AlbumItem.contents(album).size() == 8,
                "通常クリックの予算拒否でcursorまたはAlbumの盤を失った");

        menu.setCarried(ItemStack.EMPTY);
        player.getInventory().setItem(1, oversized.copy());
        final ItemStack retainedCarried = longDisc(20_000, 1);
        menu.setCarried(retainedCarried.copy());
        helper.assertTrue(menu.quickMoveStack(player, inventoryMenuSlot(1)).isEmpty()
                        && ItemStack.matches(player.getInventory().getItem(1), oversized)
                        && ItemStack.matches(menu.getCarried(), retainedCarried)
                        && AlbumItem.contents(album).size() == 8,
                "shift-clickの予算拒否で所持品・cursorまたはAlbumの盤を失った");

        menu.setCarried(ItemStack.EMPTY);
        player.getInventory().setItem(2, oversized.copy());
        swap(menu, 0, 2, player);
        helper.assertTrue(ItemStack.matches(player.getInventory().getItem(2), oversized)
                        && AlbumItem.contents(album).size() == 8,
                "数字キーswapの予算拒否で所持品またはAlbumの盤を失った");

        final ItemStack smallAlbum = new ItemStack(ModItems.ALBUM.get());
        player.getInventory().setItem(1, ItemStack.EMPTY);
        player.getInventory().setItem(2, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.MAIN_HAND, smallAlbum);
        final AlbumMenu smallMenu = new AlbumMenu(8, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        final ItemStack small = disc(42);
        smallMenu.setCarried(small.copy());
        pickup(smallMenu, 0, player);
        helper.assertTrue(smallMenu.getCarried().isEmpty() && AlbumItem.contents(smallAlbum).size() == 1,
                "通常クリックの小さい盤を予算検証が拒否した");
        helper.assertTrue(!smallMenu.quickMoveStack(player, 0).isEmpty(), "小さい盤を取り出せない");
        player.getInventory().setItem(1, small.copy());
        helper.assertTrue(!smallMenu.quickMoveStack(player, inventoryMenuSlot(1)).isEmpty(), "小さい盤のshift-clickを予算検証が拒否した");
        helper.assertTrue(!smallMenu.quickMoveStack(player, 0).isEmpty(), "shift-click投入後の小さい盤を取り出せない");
        player.getInventory().setItem(2, small.copy());
        swap(smallMenu, 0, 2, player);
        helper.assertTrue(AlbumItem.contents(smallAlbum).size() == 1
                        && player.getInventory().getItem(2).isEmpty(),
                "小さい盤の数字キーswapを予算検証が拒否した");
        assertUnrelatedInventoryAllowsSmallInsertion(helper);
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void menuRecoversOverflowInSameSession(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final List<ItemStack> stored = new ArrayList<>();
        for (int i = 0; i < 21; i++) stored.add(disc(i));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(stored));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final AlbumMenu menu = new AlbumMenu(9, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));

        for (int i = 0; i < 21; i++) {
            final ItemStack removed = menu.quickMoveStack(player, firstStoredSlot(menu));
            helper.assertTrue(!removed.isEmpty()
                            && ("track-" + i).equals(removed.get(ModDataComponents.CUSTOM_TRACK.get()).title())
                            && AlbumItem.contents(album).size() == 20 - i,
                    "同一menuでoverflow盤を保存順に回収できない: " + i);
            if (i < 20) {
                helper.assertTrue(("track-" + (i + 1)).equals(AlbumItem.contents(album).discAt(0)
                                .get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                        "補充後の先頭盤が保存順でない: " + i);
            }
        }
        helper.succeed();
    }

    /** 分割syncの合法な大きい所持品と、実クリックで変わる値を区別する回帰。 */
    private static void assertUnrelatedInventoryAllowsSmallInsertion(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final List<ItemStack> unrelated = List.of(paddedDisc(30_000, 400 * 1024),
                paddedDisc(30_001, 400 * 1024), paddedDisc(30_002, 400 * 1024));
        for (int i = 0; i < unrelated.size(); i++) {
            helper.assertTrue(AlbumStorageBudget.fits(unrelated.get(i), player.level().registryAccess()),
                    "回帰用の所持品が単独packet予算を超えた");
            player.getInventory().setItem(9 + i, unrelated.get(i).copy());
        }
        helper.assertTrue(!AlbumStorageBudget.fitsMenuSync(unrelated, ItemStack.EMPTY,
                        player.level().registryAccess()), "回帰用の所持品合計が1MiB以下だった");
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final AlbumMenu menu = new AlbumMenu(70, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        final ItemStack small = disc(30_010);
        menu.setCarried(small.copy());
        List<ItemStack> before = menu.slots.stream().map(slot -> slot.getItem().copy()).toList();
        helper.assertTrue(menu.slots.get(0).mayPlace(menu.getCarried()),
                "無関係な大所持品の合計で小さい盤のmayPlaceを拒否した");
        pickup(menu, 0, player);
        assertChangedSlotBudget(helper, menu, before, player);
        helper.assertTrue(menu.getCarried().isEmpty() && ItemStack.matches(AlbumItem.contents(album).discAt(0), small),
                "大所持品があると通常投入の盤またはcursorを保存できない");
        final AlbumMenu reopened = new AlbumMenu(71, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        helper.assertTrue(ItemStack.matches(reopened.slots.get(0).getItem(), small), "小さい盤が再openで失われた");
        pickup(reopened, 0, player);
        helper.assertTrue(ItemStack.matches(reopened.getCarried(), small) && AlbumItem.contents(album).isEmpty(),
                "大所持品があると小さい盤をcursorへ回収できない");
        reopened.setCarried(ItemStack.EMPTY);
        player.getInventory().setItem(1, small.copy());
        before = reopened.slots.stream().map(slot -> slot.getItem().copy()).toList();
        helper.assertTrue(!reopened.quickMoveStack(player, inventoryMenuSlot(1)).isEmpty()
                        && player.getInventory().getItem(1).isEmpty(), "大所持品があるとshift投入を拒否する");
        assertChangedSlotBudget(helper, reopened, before, player);
        helper.assertTrue(!reopened.quickMoveStack(player, 0).isEmpty(), "shift投入後の小さい盤を回収できない");
        player.getInventory().setItem(2, disc(30_011));
        before = reopened.slots.stream().map(slot -> slot.getItem().copy()).toList();
        swap(reopened, 0, 2, player);
        assertChangedSlotBudget(helper, reopened, before, player);
        helper.assertTrue(player.getInventory().getItem(2).isEmpty()
                        && "track-30011".equals(AlbumItem.contents(album).discAt(0)
                                .get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                "大所持品があると数字キー投入を拒否する");
        for (int i = 0; i < unrelated.size(); i++) helper.assertTrue(
                ItemStack.matches(player.getInventory().getItem(9 + i), unrelated.get(i)),
                "小さい盤の操作が無関係な大所持品を変えた");

        final ItemStack empty = new ItemStack(ModItems.ALBUM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, empty);
        final AlbumMenu reject = new AlbumMenu(72, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        final ItemStack huge = paddedDisc(30_020, 1100 * 1024);
        helper.assertTrue(!AlbumStorageBudget.fits(huge, player.level().registryAccess()),
                "巨大媒体の単独codec拒否がなくなった");
        reject.setCarried(huge.copy());
        pickup(reject, 0, player);
        helper.assertTrue(ItemStack.matches(reject.getCarried(), huge) && AlbumItem.contents(empty).isEmpty(),
                "巨大媒体の拒否でcursorまたはAlbumを変えた");
        // 個別には合法でも、挿入値・更新後source・cursorの同一packet合算が過大なら拒否する。
        final ItemStack packetLarge = paddedDisc(30_021, 600 * 1024);
        helper.assertTrue(AlbumStorageBudget.fits(packetLarge, player.level().registryAccess()),
                "クリック合算用媒体が単独codec予算を超えた");
        reject.setCarried(packetLarge.copy());
        helper.assertTrue(!reject.slots.get(0).mayPlace(reject.getCarried()),
                "クリックのslotとcursorの合算上限を外した");
        pickup(reject, 0, player);
        helper.assertTrue(ItemStack.matches(reject.getCarried(), packetLarge) && AlbumItem.contents(empty).isEmpty(),
                "クリック合算拒否でcursorまたは保存内容を失った");
    }

    private static void assertChangedSlotBudget(GameTestHelper helper, AlbumMenu menu, List<ItemStack> before, FakePlayer player) {
        final List<ItemStack> changed = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            final ItemStack after = menu.slots.get(i).getItem();
            if (!ItemStack.matches(before.get(i), after)) changed.add(after.copy());
        }
        helper.assertTrue(AlbumStorageBudget.fitsMenuSync(changed, menu.getCarried(), player.level().registryAccess()),
                "受理したクリックのchangedSlotsとcursorが同期予算を超えた");
    }

    private static int inventoryMenuSlot(int inventorySlot) {
        return AlbumItem.GUI_CAPACITY + 27 + inventorySlot;
    }

    private static int firstStoredSlot(AlbumMenu menu) {
        for (int i = 0; i < AlbumItem.GUI_CAPACITY; i++) {
            if (menu.slots.get(i).hasItem()) return i;
        }
        throw new AssertionError("Album内に取り出せる盤がない");
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void storageDecodeLimitRejectsInsertionButKeepsExistingContents(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
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
                "256枚保存済みAlbumへの投入拒否で既存データまたは投入盤を失った");
        helper.assertTrue(!menu.quickMoveStack(player, 0).isEmpty()
                        && AlbumItem.contents(album).size() == AlbumContents.STORAGE_DECODE_LIMIT - 1,
                "256枚保存済みAlbumから既存の盤を取り出せない");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void initialSyncIgnoresPlayerInventoryButRejectsOversizedContents(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final List<ItemStack> safeDiscs = new ArrayList<>();
        for (int i = 0; i < AlbumItem.GUI_CAPACITY; i++) safeDiscs.add(longDisc(i, 1));
        final ItemStack safeAlbum = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(safeAlbum, new AlbumContents(safeDiscs));
        player.setItemInHand(InteractionHand.MAIN_HAND, safeAlbum);
        helper.assertTrue(AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND)),
                "安全な既存Albumの初回menu同期を拒否した");

        for (int i = 0; i < 3; i++) player.getInventory().setItem(9 + i, longDisc(5_000 + i, 6));
        helper.assertTrue(AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND)),
                "所持品の大きい盤だけで安全なAlbumの初回同期を拒否した");
        final AlbumMenu splitMenu = new AlbumMenu(10, player.getInventory(),
                BoomboxSource.held(InteractionHand.MAIN_HAND));
        final InitialMenuSyncCapture capture = new InitialMenuSyncCapture();
        splitMenu.setSynchronizer(capture);
        helper.assertTrue(capture.initial.size() == splitMenu.slots.size()
                        && capture.initial.subList(AlbumItem.GUI_CAPACITY, capture.initial.size()).stream()
                                .allMatch(ItemStack::isEmpty),
                "初回一括同期へplayer inventoryを残した");
        for (int i = 0; i < 3; i++) {
            final ItemStack expected = player.getInventory().getItem(9 + i);
            helper.assertTrue(capture.slotChanges.stream().anyMatch(change -> ItemStack.matches(change.stack(), expected)),
                    "分割したslot同期で大きい所持品を復元しなかった: " + i);
        }
        helper.assertTrue(ItemStack.matches(player.getItemInHand(InteractionHand.MAIN_HAND), safeAlbum),
                "初回同期の分割がserver inventoryを変更した");

        final ItemStack carried = longDisc(6_000, 6);
        splitMenu.setCarried(carried);
        capture.clearDeltas();
        splitMenu.sendAllDataToRemote();
        helper.assertTrue(capture.initialCalls == 2 && capture.initialCarried.isEmpty()
                        && capture.initial.subList(AlbumItem.GUI_CAPACITY, capture.initial.size()).stream()
                                .allMatch(ItemStack::isEmpty),
                "強制再同期でplayer inventoryまたはcursorを一括packetへ戻した");
        for (int i = 0; i < 3; i++) {
            final ItemStack expected = player.getInventory().getItem(9 + i);
            helper.assertTrue(capture.slotChanges.stream().anyMatch(change -> ItemStack.matches(change.stack(), expected)),
                    "強制再同期後のslot分割で大きい所持品を復元しなかった: " + i);
        }
        helper.assertTrue(capture.carriedChanges.stream().anyMatch(stack -> ItemStack.matches(stack, carried))
                        && ItemStack.matches(splitMenu.getCarried(), carried),
                "強制再同期後のcursor分割が復元されないかserver値を変更した");

        capture.clearDeltas();
        splitMenu.broadcastChanges();
        helper.assertTrue(capture.slotChanges.isEmpty() && capture.carriedChanges.isEmpty(),
                "強制再同期後のremote snapshotが更新されず同じ差分を再送した");
        final ItemStack changedSlot = disc(7_000);
        final ItemStack changedCarried = disc(7_001);
        player.getInventory().setItem(9, changedSlot);
        splitMenu.setCarried(changedCarried);
        splitMenu.broadcastChanges();
        helper.assertTrue(capture.slotChanges.stream().anyMatch(change -> ItemStack.matches(change.stack(), changedSlot))
                        && capture.carriedChanges.stream().anyMatch(stack -> ItemStack.matches(stack, changedCarried)),
                "強制再同期後の次のslot/cursor差分を送信しなかった");

        final List<ItemStack> unsafeDiscs = new ArrayList<>();
        for (int i = 0; i < AlbumItem.GUI_CAPACITY; i++) unsafeDiscs.add(longDisc(10_000 + i * 10, 6));
        final ItemStack unsafeAlbum = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(unsafeAlbum, new AlbumContents(unsafeDiscs));
        player.setItemInHand(InteractionHand.MAIN_HAND, unsafeAlbum);
        helper.assertTrue(!AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND))
                        && AlbumItem.contents(unsafeAlbum).size() == AlbumItem.GUI_CAPACITY,
                "危険な既存Albumの初回menu同期を許可したか保存値を変えた");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void nonHeldSourcesDisableMenuInsteadOfThrowing(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
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
            swap(menu, inventoryMenuSlot(1), 40, player);
            helper.assertTrue(!AlbumMenu.fitsInitialMenuSync(player.getInventory(), source),
                    "非held sourceが初回menu同期の検証を通過した");
        }
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND) == album
                        && AlbumItem.contents(album).size() == 1
                        && player.getInventory().getItem(1).is(ModItems.CUSTOM_MUSIC_DISC.get()),
                "非held sourceのmenuがAlbumまたは所持品を変更した");
        helper.succeed();
    }

    private static FakePlayer createPlayer(GameTestHelper helper) {
        return FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "album-test"));
    }

    private static int sourceMenuSlot(AlbumMenu menu, ItemStack source) {
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.slots.get(i).getItem() == source) return i;
        }
        throw new AssertionError("opened Album source slot not found");
    }

    private static void pickup(AlbumMenu menu, int slot, ServerPlayer player) {
        //? if >=26.1 {
        menu.clicked(slot, 0, ContainerInput.PICKUP, player);
        //?} else {
        /*menu.clicked(slot, 0, ClickType.PICKUP, player);
        *///?}
    }

    private static void swap(AlbumMenu menu, int slot, int hotbarOrOffhand, ServerPlayer player) {
        //? if >=26.1 {
        menu.clicked(slot, hotbarOrOffhand, ContainerInput.SWAP, player);
        //?} else {
        /*menu.clicked(slot, hotbarOrOffhand, ClickType.SWAP, player);
        *///?}
    }

    private static void drop(AlbumMenu menu, int slot, ServerPlayer player) {
        //? if >=26.1 {
        menu.clicked(slot, 1, ContainerInput.THROW, player);
        //?} else {
        /*menu.clicked(slot, 1, ClickType.THROW, player);
        *///?}
    }

    private static int totalTracks(ServerPlayer player, AlbumMenu menu) {
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
        final ItemStack stack = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        stack.set(ModDataComponents.CUSTOM_TRACK.get(), new CustomTrackData("https://example.invalid/" + index,
                "track-" + index, "test", 1_000L, "", false));
        return stack;
    }

    private static ItemStack longDisc(int start, int sizeClass) {
        return paddedDisc(start, sizeClass == 1 ? 16 * 1024 : 600 * 1024);
    }

    private static ItemStack paddedDisc(int start, int bytes) {
        final ItemStack stack = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        stack.set(ModDataComponents.CUSTOM_TRACK.get(), new CustomTrackData("https://example.invalid/" + start, "track-" + start, "test", 180_000L, "", false));
        // Extra item metadata exercises the synchronization limit without a multi-track disc.
        final net.minecraft.nbt.CompoundTag padding = new net.minecraft.nbt.CompoundTag();
        padding.putByteArray("fixture_padding", new byte[bytes]);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(padding));
        return stack;
    }
}
