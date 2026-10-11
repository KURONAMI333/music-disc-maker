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
import com.kuronami.musicdiscmaker.recipe.GoldenAlbumRecipe;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Forge 1.20.1 でも Golden Album が 54 枠で開き、金で囲むクラフトが NBT を引き継ぐ。 */
@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
public final class GoldenAlbumLegacyGameTests {
    private GoldenAlbumLegacyGameTests() { }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void goldenAlbumOpensFiftyFourSlots(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "golden-album-fixture"));
        final ItemStack golden = new ItemStack(ModItems.GOLDEN_ALBUM.get());
        AlbumItem.setContents(golden, new AlbumContents(discs(54)));
        player.setItemInHand(InteractionHand.MAIN_HAND, golden);
        final AlbumMenu menu = AlbumMenu.forHeld(1, player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND));
        helper.assertTrue(menu.getType() == ModMenus.GOLDEN_ALBUM.get() && menu.slots.size() == 54 + 36
                        && "track-53".equals(CustomMusicDiscItem.getTrack(menu.slots.get(53).getItem()).title()),
                "Golden Album did not open as a 54-slot menu with its 54th disc");
        final ItemStack taken = menu.quickMoveStack(player, 53);
        helper.assertTrue("track-53".equals(CustomMusicDiscItem.getTrack(taken).title())
                        && AlbumItem.contents(golden).size() == 53,
                "the 54th disc could not be taken from the Golden Album");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void goldenAlbumRecipeKeepsDiscsColorAndName(GameTestHelper helper) {
        final Recipe<?> loaded = helper.getLevel().getRecipeManager()
                .byKey(new ResourceLocation(MusicDiscMaker.MODID, "golden_album")).orElse(null);
        helper.assertTrue(loaded instanceof GoldenAlbumRecipe && loaded instanceof ShapedRecipe,
                "golden_album is not loaded as the content-keeping shaped recipe");
        final GoldenAlbumRecipe recipe = (GoldenAlbumRecipe) loaded;
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        album.setHoverName(Component.literal("road songs"));
        AlbumItem.setColor(album, DyeColor.LIME);
        AlbumItem.setContents(album, new AlbumContents(discs(18)));

        final TransientCraftingContainer grid = grid(album, new ItemStack(Items.GOLD_INGOT));
        helper.assertTrue(recipe.matches(grid, helper.getLevel()), "an Album ringed by gold was rejected");
        final ItemStack result = recipe.assemble(grid, helper.getLevel().registryAccess());
        helper.assertTrue(result.is(ModItems.GOLDEN_ALBUM.get()) && result.getCount() == 1
                        && AlbumItem.contents(result).equals(AlbumItem.contents(album))
                        && AlbumItem.getColor(result) == DyeColor.LIME
                        && result.getHoverName().getString().equals("road songs"),
                "the Golden Album lost discs, order, color, or name: " + result.getTag());
        helper.assertTrue(!recipe.matches(grid(new ItemStack(ModItems.GOLDEN_ALBUM.get()), new ItemStack(Items.GOLD_INGOT)),
                        helper.getLevel()),
                "a Golden Album was accepted as the center");
        helper.succeed();
    }

    private static TransientCraftingContainer grid(ItemStack center, ItemStack ring) {
        final AbstractContainerMenu dummy = new AbstractContainerMenu(null, -1) {
            @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(Player player) { return true; }
        };
        final TransientCraftingContainer grid = new TransientCraftingContainer(dummy, 3, 3);
        for (int i = 0; i < 9; i++) grid.setItem(i, i == 4 ? center.copy() : ring.copy());
        return grid;
    }

    private static List<ItemStack> discs(int count) {
        final List<ItemStack> discs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
            CustomMusicDiscItem.setTrack(disc, new CustomTrackData("https://example.invalid/golden-" + i,
                    "track-" + i, "test", 1_000L, "", false));
            discs.add(disc);
        }
        return discs;
    }
}
