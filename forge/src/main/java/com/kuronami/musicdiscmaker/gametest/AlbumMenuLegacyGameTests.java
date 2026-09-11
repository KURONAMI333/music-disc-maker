package com.kuronami.musicdiscmaker.gametest;

import java.util.ArrayList;
import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.menu.AlbumMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.register.ModItems;

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
    public static void menuPreservesOverflowAndReorderedNbt(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "menu-fixture"));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        final List<ItemStack> original = new ArrayList<>();
        for (int i = 0; i < 10; i++) original.add(disc(i));
        AlbumItem.setContents(album, new AlbumContents(original));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final AlbumMenu menu = new AlbumMenu(1, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        helper.assertTrue(AlbumItem.contents(album).size() == 10, "opening Album discarded overflow in legacy NBT");
        menu.quickMoveStack(player, 0);
        helper.assertTrue("track-9".equals(CustomMusicDiscItem.getTrack(AlbumItem.contents(album).discAt(8)).title()),
                "taking a disc discarded legacy overflow");

        menu.clicked(0, 0, ClickType.PICKUP, player);
        menu.clicked(1, 0, ClickType.PICKUP, player);
        menu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue("track-2".equals(CustomMusicDiscItem.getTrack(AlbumItem.contents(album).discAt(0)).title()),
                "reordered legacy menu slots were not persisted");
        helper.assertTrue("track-1".equals(CustomMusicDiscItem.getTrack(AlbumItem.contents(album).discAt(1)).title()),
                "legacy menu reorder did not move both discs");
        helper.assertTrue("track-9".equals(CustomMusicDiscItem.getTrack(AlbumItem.contents(album).discAt(8)).title()),
                "legacy overflow refill did not preserve the remaining order");
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
        menu.clicked(37, 0, ClickType.PICKUP, player);
        menu.clicked(0, 0, ClickType.PICKUP, player);
        helper.assertTrue(AlbumItem.contents(album).size() == 1, "legacy normal click did not insert a disc");
        helper.assertTrue(!menu.quickMoveStack(player, 0).isEmpty(), "legacy normal-click disc could not be taken back");
        player.getInventory().setItem(1, disc(0));
        helper.assertTrue(!menu.quickMoveStack(player, 37).isEmpty(), "legacy shift-click did not insert a disc");
        helper.assertTrue(menu.quickMoveStack(player, 38).isEmpty(), "legacy Album accepted Boombox input");
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
        menu.clicked(37, 0, ClickType.SWAP, player);
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
        menu.clicked(37, 40, ClickType.SWAP, player);
        helper.assertTrue(player.getItemInHand(InteractionHand.OFF_HAND) == album,
                "legacy reverse offhand swap moved source Album");
        helper.assertTrue(ItemStack.matches(player.getInventory().getItem(1), inventoryDisc)
                        && menu.getCarried().isEmpty() && totalTracks(player, menu) == tracksBefore,
                "legacy reverse offhand swap changed disc metadata, cursor, or total");

        final ItemStack replacement = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(replacement, new AlbumContents(List.of(disc(9))));
        player.setItemInHand(InteractionHand.OFF_HAND, replacement);
        menu.clicked(37, 0, ClickType.PICKUP, player);
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
        helper.assertTrue(menu.quickMoveStack(player, 37).isEmpty()
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
        helper.assertTrue(!smallMenu.quickMoveStack(player, 37).isEmpty(), "legacy shift-click rejected a small disc");
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
        for (int i = 0; i < 12; i++) stored.add(disc(i));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(stored));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final AlbumMenu menu = new AlbumMenu(9, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));

        for (int i = 0; i < 12; i++) {
            final ItemStack removed = menu.quickMoveStack(player, firstStoredSlot(menu));
            helper.assertTrue(!removed.isEmpty()
                            && ("track-" + i).equals(CustomMusicDiscItem.getTrack(removed).title())
                            && AlbumItem.contents(album).size() == 11 - i,
                    "legacy同一menuでoverflow盤を保存順に回収できない: " + i);
            if (i < 11) {
                helper.assertTrue(("track-" + (i + 1)).equals(CustomMusicDiscItem.getTrack(
                                AlbumItem.contents(album).discAt(0)).title()),
                        "legacy補充後の先頭盤が保存順でない: " + i);
            }
        }
        helper.succeed();
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

        helper.assertTrue(menu.quickMoveStack(player, 37).isEmpty()
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
        for (int i = 0; i < 9; i++) safeDiscs.add(longDisc(i, 1));
        final ItemStack safeAlbum = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(safeAlbum, new AlbumContents(safeDiscs));
        player.setItemInHand(InteractionHand.MAIN_HAND, safeAlbum);
        helper.assertTrue(AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND)),
                "legacy安全な既存Albumの初回menu同期を拒否した");

        final List<ItemStack> unsafeDiscs = new ArrayList<>();
        for (int i = 0; i < 9; i++) unsafeDiscs.add(longDisc(10_000 + i * 10, 6));
        final ItemStack unsafeAlbum = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(unsafeAlbum, new AlbumContents(unsafeDiscs));
        player.setItemInHand(InteractionHand.MAIN_HAND, unsafeAlbum);
        helper.assertTrue(!AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND))
                        && AlbumItem.contents(unsafeAlbum).size() == 9,
                "legacy危険な既存Albumの初回menu同期を許可したか保存値を変えた");
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
