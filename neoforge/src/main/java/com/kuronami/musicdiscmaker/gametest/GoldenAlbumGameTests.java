package com.kuronami.musicdiscmaker.gametest;

//? if >=1.21 {
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.MediaSequenceResolver;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.menu.AlbumMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.recipe.AlbumDyeRecipe;
import com.kuronami.musicdiscmaker.recipe.GoldenAlbumRecipe;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?}
//? if >=1.21.2 {
import net.minecraft.resources.ResourceKey;
//?}
//? if <1.21.11 {
/*import net.minecraft.resources.ResourceLocation;
*///?}
//? if >=26.1 {
import net.minecraft.world.inventory.ContainerInput;
//?} else {
/*import net.minecraft.world.inventory.ClickType;
*///?}
//? if <1.21.11 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}

/** Golden Album は Album と同じ仕組みで 54 枠を持ち、金で囲むクラフトが中身を引き継ぐ。 */
public final class GoldenAlbumGameTests {
    private static final String TEMPLATE = "empty8x3x8";

    private GoldenAlbumGameTests() {
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void goldenAlbumOpensFiftyFourSlots(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final ItemStack golden = new ItemStack(ModItems.GOLDEN_ALBUM.get());
        AlbumItem.setContents(golden, new AlbumContents(discs(54)));
        player.setItemInHand(InteractionHand.MAIN_HAND, golden);
        final BoomboxSource source = BoomboxSource.held(InteractionHand.MAIN_HAND);

        final AlbumMenu menu = AlbumMenu.forHeld(3, player.getInventory(), source);
        helper.assertTrue(menu.getType() == ModMenus.GOLDEN_ALBUM.get() && menu.rows() == 6
                        && menu.slots.size() == 54 + 36 && menu.stillValid(player),
                "Golden Album が 6 段 54 枠の menu で開かない");
        helper.assertTrue(menu.slots.get(45).hasItem() && "track-53".equals(title(menu.slots.get(53).getItem())),
                "Golden Album の 6 段目に盤が並ばない");
        helper.assertTrue(menu.slots.get(54).container == player.getInventory()
                        && menu.slots.get(54).y == 139 && menu.slots.get(54 + 27).y == 197,
                "Golden Album の下側のインベントリが大きいチェストと同じ位置にない");
        helper.assertTrue(AlbumMenu.title(player, source).getContents() instanceof TranslatableContents key
                        && key.getKey().equals("item.music_disc_maker.golden_album"),
                "Golden Album の画面の題が Golden Album でない");

        final ItemStack taken = menu.quickMoveStack(player, 53);
        helper.assertTrue("track-53".equals(title(taken)) && AlbumItem.contents(golden).size() == 53,
                "54 枚目を取り出せない、または保存数が合わない");
        menu.setCarried(taken);
        pickup(menu, 53, player);
        helper.assertTrue(menu.getCarried().isEmpty() && AlbumItem.contents(golden).size() == 54
                        && "track-53".equals(title(AlbumItem.contents(golden).discAt(53))),
                "通常クリックで 54 枚目の枠へ戻せない");

        player.getInventory().setItem(1, disc(1_000));
        helper.assertTrue(menu.quickMoveStack(player, 54 + 27 + 1).isEmpty()
                        && "track-1000".equals(title(player.getInventory().getItem(1)))
                        && AlbumItem.contents(golden).size() == 54,
                "満杯の Golden Album へ 55 枚目が入った、または投入盤を失った");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void albumKindsKeepTheirOwnMenuAndCannotNest(GameTestHelper helper) {
        final FakePlayer player = createPlayer(helper);
        final BoomboxSource source = BoomboxSource.held(InteractionHand.MAIN_HAND);
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(discs(3)));
        player.setItemInHand(InteractionHand.MAIN_HAND, album);

        final AlbumMenu normal = AlbumMenu.forHeld(4, player.getInventory(), source);
        helper.assertTrue(normal.getType() == ModMenus.ALBUM.get() && normal.slots.size() == 18 + 36,
                "Album が 2 段 18 枠の menu で開かない");
        final AlbumMenu wrongKind = AlbumMenu.golden(5, player.getInventory(), source);
        helper.assertTrue(!wrongKind.stillValid(player) && !wrongKind.slots.get(0).hasItem(),
                "6 段の menu が Album を読み込んだ");
        wrongKind.quickMoveStack(player, 0);
        helper.assertTrue(AlbumItem.contents(album).size() == 3, "種類の違う menu が Album の中身を変えた");

        normal.setCarried(new ItemStack(ModItems.GOLDEN_ALBUM.get()));
        helper.assertTrue(!normal.slots.get(5).mayPlace(normal.getCarried()), "Album に Golden Album が入る");

        final ItemStack golden = new ItemStack(ModItems.GOLDEN_ALBUM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, golden);
        final AlbumMenu goldenMenu = AlbumMenu.forHeld(6, player.getInventory(), source);
        helper.assertTrue(!goldenMenu.slots.get(0).mayPlace(album), "Golden Album に Album が入る");
        helper.assertTrue(!new AlbumMenu(7, player.getInventory(), source).stillValid(player),
                "2 段の menu が Golden Album を開いた");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void goldenAlbumPlaysAllFiftyFourDiscs(GameTestHelper helper) {
        final ItemStack golden = new ItemStack(ModItems.GOLDEN_ALBUM.get());
        AlbumItem.setContents(golden, new AlbumContents(discs(54)));
        final MediaSequenceResolver.ResolvedSequence sequence = MediaSequenceResolver.resolve(golden);
        helper.assertTrue(sequence.kind() == MediaSequenceResolver.Kind.MDM_ALBUM && sequence.tracks().size() == 54
                        && "track-53".equals(sequence.tracks().get(53).track().title()),
                "Golden Album の 54 曲を再生順に解決できない");
        helper.assertTrue(AlbumItem.isAlbum(golden) && AlbumItem.capacity(golden) == 54
                        && AlbumItem.capacity(new ItemStack(ModItems.ALBUM.get())) == 18
                        && AlbumItem.capacity(new ItemStack(Items.STONE)) == 0,
                "Album の種類ごとの枠数が違う");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void goldenAlbumRecipeKeepsDiscsColorAndName(GameTestHelper helper) {
        final Recipe<?> loaded = recipe(helper, "golden_album");
        helper.assertTrue(loaded instanceof GoldenAlbumRecipe && loaded instanceof ShapedRecipe,
                "golden_album が中身を引き継ぐ shaped レシピとして読まれていない");
        final GoldenAlbumRecipe recipe = (GoldenAlbumRecipe) loaded;

        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        album.set(DataComponents.CUSTOM_NAME, Component.literal("旅の曲"));
        AlbumItem.setColor(album, DyeColor.LIME);
        AlbumItem.setContents(album, new AlbumContents(discs(18)));
        final CraftingInput input = surround(album, new ItemStack(Items.GOLD_INGOT));
        helper.assertTrue(recipe.matches(input, helper.getLevel()), "金 8 個で囲んだ Album を受け入れない");
        final ItemStack result = assemble(recipe, input, helper);
        helper.assertTrue(result.is(ModItems.GOLDEN_ALBUM.get()) && result.getCount() == 1,
                "結果が Golden Album 1 冊でない: " + result);
        helper.assertTrue(AlbumItem.contents(result).equals(AlbumItem.contents(album)),
                "盤または順番が Golden Album へ引き継がれない");
        helper.assertTrue(AlbumItem.getColor(result) == DyeColor.LIME && result.getHoverName().equals(album.getHoverName()),
                "色または名前が Golden Album へ引き継がれない");
        helper.assertTrue(AlbumItem.contents(album).size() == 18, "クラフトの結果作りが材料の Album を変えた");

        final ItemStack empty = assemble(recipe, surround(new ItemStack(ModItems.ALBUM.get()), new ItemStack(Items.GOLD_INGOT)), helper);
        helper.assertTrue(empty.is(ModItems.GOLDEN_ALBUM.get()) && AlbumItem.contents(empty).isEmpty(),
                "空の Album から空の Golden Album ができない");
        helper.assertTrue(!recipe.matches(surround(new ItemStack(ModItems.GOLDEN_ALBUM.get()), new ItemStack(Items.GOLD_INGOT)),
                        helper.getLevel())
                        && !recipe.matches(surround(album, new ItemStack(Items.GOLD_NUGGET)), helper.getLevel()),
                "Golden Album の再クラフトか金塊での作成が通る");

        final AlbumDyeRecipe dye = (AlbumDyeRecipe) recipe(helper, "album_dyeing");
        final CraftingInput dyeInput = CraftingInput.of(2, 1, List.of(result, redDye()));
        helper.assertTrue(dye.matches(dyeInput, helper.getLevel()), "Golden Album を染められない");
        final ItemStack dyed = assembleDye(dye, dyeInput, helper);
        helper.assertTrue(dyed.is(ModItems.GOLDEN_ALBUM.get()) && AlbumItem.getColor(dyed) == DyeColor.RED
                        && AlbumItem.contents(dyed).size() == 18,
                "染めた Golden Album の種類・色・中身が合わない");
        helper.succeed();
    }

    private static CraftingInput surround(ItemStack center, ItemStack ring) {
        final List<ItemStack> grid = new ArrayList<>();
        for (int i = 0; i < 9; i++) grid.add(i == 4 ? center : ring.copy());
        return CraftingInput.of(3, 3, grid);
    }

    private static Recipe<?> recipe(GameTestHelper helper, String path) {
        //? if >=1.21.11 {
        final var id = ResourceKey.<Recipe<?>>create(Registries.RECIPE, Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, path));
        final var holder = helper.getLevel().getServer().getRecipeManager().byKey(id).orElse(null);
        //?} elif >=1.21.2 {
        /*final var id = ResourceKey.<Recipe<?>>create(Registries.RECIPE, ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, path));
        final var holder = helper.getLevel().getServer().getRecipeManager().byKey(id).orElse(null);
        *///?} else {
        /*final var id = ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, path);
        final var holder = helper.getLevel().getServer().getRecipeManager().byKey(id).orElse(null);
        *///?}
        helper.assertTrue(holder != null, "RecipeManager に " + path + " が読み込まれていない");
        return holder.value();
    }

    private static ItemStack assemble(GoldenAlbumRecipe recipe, CraftingInput input, GameTestHelper helper) {
        //? if >=26.1 {
        return recipe.assemble(input);
        //?} else {
        /*return recipe.assemble(input, helper.getLevel().registryAccess());
        *///?}
    }

    private static ItemStack assembleDye(AlbumDyeRecipe recipe, CraftingInput input, GameTestHelper helper) {
        //? if >=26.1 {
        return recipe.assemble(input);
        //?} else {
        /*return recipe.assemble(input, helper.getLevel().registryAccess());
        *///?}
    }

    private static ItemStack redDye() {
        //? if >=1.21.11 {
        return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("red_dye")));
        //?} elif >=1.21.2 {
        /*return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(ResourceLocation.withDefaultNamespace("red_dye")));
        *///?} else {
        /*return new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace("red_dye")));
        *///?}
    }

    private static FakePlayer createPlayer(GameTestHelper helper) {
        return FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "golden-album-test"));
    }

    private static void pickup(AlbumMenu menu, int slot, ServerPlayer player) {
        //? if >=26.1 {
        menu.clicked(slot, 0, ContainerInput.PICKUP, player);
        //?} else {
        /*menu.clicked(slot, 0, ClickType.PICKUP, player);
        *///?}
    }

    private static List<ItemStack> discs(int count) {
        final List<ItemStack> discs = new ArrayList<>();
        for (int i = 0; i < count; i++) discs.add(disc(i));
        return discs;
    }

    private static ItemStack disc(int index) {
        final ItemStack stack = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        stack.set(ModDataComponents.CUSTOM_TRACK.get(), new CustomTrackData("https://example.invalid/golden-" + index,
                "track-" + index, "test", 1_000L, "", false));
        return stack;
    }

    private static String title(ItemStack disc) {
        final CustomTrackData track = disc.get(ModDataComponents.CUSTOM_TRACK.get());
        return track == null ? "" : track.title();
    }
}
//?} else {
//?}
