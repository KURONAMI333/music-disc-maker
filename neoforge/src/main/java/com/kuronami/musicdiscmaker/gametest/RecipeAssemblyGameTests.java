package com.kuronami.musicdiscmaker.gametest;

//? if >=1.21 {
import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?}
//? if >=1.21.2 {
import net.minecraft.resources.ResourceKey;
//?}
//? if <1.21.11 {
/*import net.minecraft.resources.ResourceLocation;
*///?}
//? if <1.21.11 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}

/** datapackから実際に読まれたAlbum/Speakerレシピの材料配置と出力を検証する。 */
public final class RecipeAssemblyGameTests {
    private static final String TEMPLATE = "empty8x3x8";

    private RecipeAssemblyGameTests() {
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void albumRecipeAssemblesAndRejectsWrongGrid(GameTestHelper helper) {
        final CraftingRecipe recipe = recipe(helper, "album");
        final CraftingInput correct = CraftingInput.of(2, 2, List.of(
                stack(Items.LEATHER), stack(Items.PAPER),
                stack(Items.PAPER), stack(Items.LEATHER)));
        helper.assertTrue(recipe.matches(correct, helper.getLevel()), "Album の LP/PL 2x2 配置を受け入れなかった");
        final ItemStack output = assemble(recipe, correct, helper);
        helper.assertTrue(output.is(ModItems.ALBUM.get()) && output.getCount() == 1,
                "Album レシピの出力が album 1個ではない: " + output);

        final CraftingInput wrong = CraftingInput.of(2, 2, List.of(
                stack(Items.LEATHER), stack(Items.PAPER),
                stack(Items.PAPER), stack(Items.STONE)));
        helper.assertTrue(!recipe.matches(wrong, helper.getLevel()), "Album が不正な2x2材料を受け入れた");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void speakerRecipeAssemblesAndRejectsWrongGrid(GameTestHelper helper) {
        final CraftingRecipe recipe = recipe(helper, "speaker");
        final CraftingInput correct = CraftingInput.of(3, 3, List.of(
                stack(Items.GOLD_INGOT), ItemStack.EMPTY, stack(Items.GOLD_INGOT),
                stack(Items.GOLD_INGOT), stack(Items.NOTE_BLOCK), stack(Items.GOLD_INGOT),
                stack(Items.IRON_INGOT), stack(Items.REDSTONE), stack(Items.IRON_INGOT)));
        helper.assertTrue(recipe.matches(correct, helper.getLevel()), "Speaker の金4鉄2赤石1音符1配置を受け入れなかった");
        final ItemStack output = assemble(recipe, correct, helper);
        helper.assertTrue(output.is(ModItems.SPEAKER.get()) && output.getCount() == 1,
                "Speaker レシピの出力が speaker 1個ではない: " + output);

        final CraftingInput wrong = CraftingInput.of(3, 3, List.of(
                stack(Items.GOLD_INGOT), ItemStack.EMPTY, stack(Items.GOLD_INGOT),
                stack(Items.GOLD_INGOT), stack(Items.NOTE_BLOCK), stack(Items.GOLD_INGOT),
                stack(Items.IRON_INGOT), stack(Items.STONE), stack(Items.IRON_INGOT)));
        helper.assertTrue(!recipe.matches(wrong, helper.getLevel()), "Speaker が不正な3x3材料を受け入れた");
        helper.succeed();
    }

    /** 製品の全クラフトレシピが読み込まれていること。版で書式が違うので、1つでも旧/新書式が混ざると黙って欠ける。 */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void everyCraftingRecipeLoads(GameTestHelper helper) {
        for (final String path : List.of("album", "album_dyeing", "blank_disc", "blank_disc_from_disc", "boombox",
                "disc_dyeing_table", "disc_pedestal", "golden_album", "golden_jukebox", "golden_jukebox_minecart",
                "music_disc_maker", "speaker")) {
            recipe(helper, path);
        }
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void goldenJukeboxMinecartRecipeAssembles(GameTestHelper helper) {
        final CraftingRecipe recipe = recipe(helper, "golden_jukebox_minecart");
        final CraftingInput correct = CraftingInput.of(2, 1, List.of(
                new ItemStack(ModItems.GOLDEN_JUKEBOX.get()), stack(Items.MINECART)));
        helper.assertTrue(recipe.matches(correct, helper.getLevel()), "金ジューク＋トロッコを受け入れなかった");
        final ItemStack output = assemble(recipe, correct, helper);
        helper.assertTrue(output.is(ModItems.GOLDEN_JUKEBOX_MINECART.get()) && output.getCount() == 1,
                "金ジューク付きトロッコのレシピの出力が1台ではない: " + output);
        helper.assertTrue(!recipe.matches(CraftingInput.of(2, 1, List.of(
                        stack(Items.JUKEBOX), stack(Items.MINECART))), helper.getLevel()),
                "通常のジュークボックスで金ジューク付きトロッコが作れた");
        helper.succeed();
    }

    private static CraftingRecipe recipe(GameTestHelper helper, String path) {
        //? if >=1.21.11 {
        final var id = ResourceKey.<net.minecraft.world.item.crafting.Recipe<?>>create(
                Registries.RECIPE, Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, path));
        final var holder = helper.getLevel().getServer().getRecipeManager().byKey(id).orElse(null);
        //?} elif >=1.21.2 {
        /*final var id = ResourceKey.<net.minecraft.world.item.crafting.Recipe<?>>create(
                Registries.RECIPE, ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, path));
        final var holder = helper.getLevel().getServer().getRecipeManager().byKey(id).orElse(null);
        *///?} else {
        /*final var id = ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, path);
        final var holder = helper.getLevel().getServer().getRecipeManager().byKey(id).orElse(null);
        *///?}
        helper.assertTrue(holder != null, "RecipeManager に " + path + " が読み込まれていない");
        helper.assertTrue(holder != null && holder.value() instanceof CraftingRecipe,
                "RecipeManager の " + path + " が crafting recipe ではない");
        return (CraftingRecipe) holder.value();
    }

    private static ItemStack assemble(CraftingRecipe recipe, CraftingInput input, GameTestHelper helper) {
        //? if >=26.1 {
        return recipe.assemble(input);
        //?} else {
        /*return recipe.assemble(input, helper.getLevel().registryAccess());
        *///?}
    }

    private static ItemStack stack(net.minecraft.world.item.Item item) {
        return new ItemStack(item);
    }
}
//?} else {
//?}
