package com.kuronami.musicdiscmaker.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.menu.AlbumMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

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
//? if <1.21.2 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}

/** AlbumMenu のserver実経路で、画面外overflowを黙って失わないことを確認する。 */
public final class AlbumMenuGameTests {
    private AlbumMenuGameTests() { }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void openingAndTakingKeepsOverflow(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        final List<ItemStack> original = new ArrayList<>();
        for (int i = 0; i < 10; i++) original.add(disc(i));
        AlbumItem.setContents(album, new AlbumContents(original));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);

        final AlbumMenu menu = new AlbumMenu(1, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        helper.assertTrue(AlbumItem.contents(album).size() == 10, "10枚Albumを開いただけで後半が失われた");
        menu.quickMoveStack(player, 0);
        final AlbumContents after = AlbumItem.contents(album);
        helper.assertTrue(after.size() == 9, "先頭を取り出した後の盤数が9でない");
        helper.assertTrue("track-9".equals(after.discAt(8).get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                "画面外10枚目が先頭取出後に失われた");
        helper.succeed();
    }

    //? if <1.21.2 {
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

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void storesTakesAndReordersThroughMenu(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final AlbumMenu menu = new AlbumMenu(3, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        player.getInventory().setItem(1, disc(0));
        pickup(menu, 37, player);
        pickup(menu, 0, player);
        helper.assertTrue(AlbumItem.contents(album).size() == 1, "通常クリックで盤をAlbumへ投入できない");
        helper.assertTrue(!menu.quickMoveStack(player, 0).isEmpty(), "通常投入した盤をAlbumから取り出せない");
        player.getInventory().setItem(1, disc(0));
        helper.assertTrue(!menu.quickMoveStack(player, 37).isEmpty(), "shift-clickで盤をAlbumへ投入できない");
        helper.assertTrue(AlbumItem.contents(album).size() == 1, "投入した盤が保存されない");
        player.getInventory().setItem(2, new ItemStack(ModItems.BOOMBOX.get()));
        helper.assertTrue(menu.quickMoveStack(player, 38).isEmpty(), "BoomboxをAlbumへ投入できてしまった");
        helper.assertTrue(AlbumItem.contents(album).size() == 1, "拒否入力がAlbumを変えた");

        player.getInventory().setItem(3, disc(1));
        menu.quickMoveStack(player, 39);
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

    //? if <1.21.2 {
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

    //? if <1.21.2 {
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
        swap(menu, 37, 0, player);
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

    //? if <1.21.2 {
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
        swap(offhandMenu, 37, 40, player);
        helper.assertTrue(player.getItemInHand(InteractionHand.OFF_HAND) == offhandAlbum,
                "逆方向のoffhand交換で元Albumが移動した");
        helper.assertTrue(ItemStack.matches(player.getInventory().getItem(1), inventoryDisc)
                        && offhandMenu.getCarried().isEmpty() && totalTracks(player, offhandMenu) == 2,
                "逆方向のoffhand交換で盤・cursor・inventory総数が保全されない");

        final ItemStack replacement = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(replacement, new AlbumContents(List.of(disc(9))));
        player.setItemInHand(InteractionHand.OFF_HAND, replacement);
        pickup(offhandMenu, 37, player);
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

    //? if <1.21.2 {
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
        helper.assertTrue(menu.quickMoveStack(player, 37).isEmpty()
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
        helper.assertTrue(!smallMenu.quickMoveStack(player, 37).isEmpty(), "小さい盤のshift-clickを予算検証が拒否した");
        helper.assertTrue(!smallMenu.quickMoveStack(player, 0).isEmpty(), "shift-click投入後の小さい盤を取り出せない");
        player.getInventory().setItem(2, small.copy());
        swap(smallMenu, 0, 2, player);
        helper.assertTrue(AlbumItem.contents(smallAlbum).size() == 1
                        && player.getInventory().getItem(2).isEmpty(),
                "小さい盤の数字キーswapを予算検証が拒否した");
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void menuRecoversOverflowInSameSession(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final List<ItemStack> stored = new ArrayList<>();
        for (int i = 0; i < 12; i++) stored.add(disc(i));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(stored));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final AlbumMenu menu = new AlbumMenu(9, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));

        for (int i = 0; i < 12; i++) {
            final ItemStack removed = menu.quickMoveStack(player, firstStoredSlot(menu));
            helper.assertTrue(!removed.isEmpty()
                            && ("track-" + i).equals(removed.get(ModDataComponents.CUSTOM_TRACK.get()).title())
                            && AlbumItem.contents(album).size() == 11 - i,
                    "同一menuでoverflow盤を保存順に回収できない: " + i);
            if (i < 11) {
                helper.assertTrue(("track-" + (i + 1)).equals(AlbumItem.contents(album).discAt(0)
                                .get(ModDataComponents.CUSTOM_TRACK.get()).title()),
                        "補充後の先頭盤が保存順でない: " + i);
            }
        }
        helper.succeed();
    }

    private static int firstStoredSlot(AlbumMenu menu) {
        for (int i = 0; i < AlbumItem.GUI_CAPACITY; i++) {
            if (menu.slots.get(i).hasItem()) return i;
        }
        throw new AssertionError("Album内に取り出せる盤がない");
    }

    //? if <1.21.2 {
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

        helper.assertTrue(menu.quickMoveStack(player, 37).isEmpty()
                        && !player.getInventory().getItem(1).isEmpty()
                        && AlbumItem.contents(album).size() == AlbumContents.STORAGE_DECODE_LIMIT,
                "256枚保存済みAlbumへの投入拒否で既存データまたは投入盤を失った");
        helper.assertTrue(!menu.quickMoveStack(player, 0).isEmpty()
                        && AlbumItem.contents(album).size() == AlbumContents.STORAGE_DECODE_LIMIT - 1,
                "256枚保存済みAlbumから既存の盤を取り出せない");
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void initialMenuSyncRejectsUnsafeExistingAlbum(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final List<ItemStack> safeDiscs = new ArrayList<>();
        for (int i = 0; i < 9; i++) safeDiscs.add(longDisc(i, 1));
        final ItemStack safeAlbum = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(safeAlbum, new AlbumContents(safeDiscs));
        player.setItemInHand(InteractionHand.MAIN_HAND, safeAlbum);
        helper.assertTrue(AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND)),
                "安全な既存Albumの初回menu同期を拒否した");

        final List<ItemStack> unsafeDiscs = new ArrayList<>();
        for (int i = 0; i < 9; i++) unsafeDiscs.add(longDisc(10_000 + i * 10, 6));
        final ItemStack unsafeAlbum = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(unsafeAlbum, new AlbumContents(unsafeDiscs));
        player.setItemInHand(InteractionHand.MAIN_HAND, unsafeAlbum);
        helper.assertTrue(!AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND))
                        && AlbumItem.contents(unsafeAlbum).size() == 9,
                "危険な既存Albumの初回menu同期を許可したか保存値を変えた");
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
        final ItemStack stack = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        stack.set(ModDataComponents.CUSTOM_TRACK.get(), new CustomTrackData("https://example.invalid/" + start, "track-" + start, "test", 180_000L, "", false));
        // Extra item metadata exercises the synchronization limit without a multi-track disc.
        final net.minecraft.nbt.CompoundTag padding = new net.minecraft.nbt.CompoundTag();
        padding.putByteArray("fixture_padding", new byte[sizeClass == 1 ? 16 * 1024 : 600 * 1024]);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(padding));
        return stack;
    }
}
