package com.kuronami.musicdiscmaker.gametest;

//? if >=1.21 {
import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.recipe.AlbumDyeRecipe;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?}
//? if >=1.21.2 {
import net.minecraft.resources.ResourceKey;
//?}
//? if <1.21.11 {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.ShapelessRecipe;
//? if <1.21.11 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}

/** Album の染色は、外装だけを変え、収納物と名前を完全に残す。 */
public final class AlbumDyeingGameTests {
    private static final String TEMPLATE = "empty8x3x8";

    private AlbumDyeingGameTests() {
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void dyeingKeepsAlbumMetadataAndContents(GameTestHelper helper) {
        final ItemStack album = populatedAlbum();
        helper.assertTrue(AlbumItem.getColor(album) == null, "旧無色 Album が既定色componentを持っている");

        final AlbumDyeRecipe recipe = recipe(helper, "album_dyeing_blue");
        final CraftingInput input = CraftingInput.of(2, 1, List.of(album, dye(DyeColor.BLUE)));
        helper.assertTrue(recipe.matches(input, helper.getLevel()), "Album 1冊と染料1個を受け入れなかった");
        final ItemStack dyed = assemble(recipe, input, helper);

        helper.assertTrue(AlbumItem.getColor(dyed) == DyeColor.BLUE, "染料の色が Album に保存されなかった");
        helper.assertTrue(album.getHoverName().equals(dyed.getHoverName()), "Album のカスタム名が染色で失われた");
        helper.assertTrue(AlbumItem.contents(album).equals(AlbumItem.contents(dyed)),
                "Album の盤・順序・盤のmetadataが染色で変わった");
        helper.assertTrue(AlbumItem.contents(dyed).discAt(0).get(ModDataComponents.CUSTOM_TRACK.get())
                        .equals(track("first")),
                "収納済み盤の曲metadataが染色で失われた");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void dyeingRejectsExtraWrongAndSameColorInputs(GameTestHelper helper) {
        final AlbumDyeRecipe blue = recipe(helper, "album_dyeing_blue");
        final AlbumDyeRecipe red = recipe(helper, "album_dyeing_red");
        final ItemStack album = populatedAlbum();
        helper.assertTrue(!blue.matches(CraftingInput.of(3, 1,
                        List.of(album, dye(DyeColor.RED), dye(DyeColor.BLUE))), helper.getLevel()),
                "染料2個を受け入れた");
        helper.assertTrue(!blue.matches(CraftingInput.of(2, 1,
                        List.of(album, new ItemStack(Items.STONE))), helper.getLevel()),
                "Album と無関係な材料を受け入れた");
        helper.assertTrue(!blue.matches(CraftingInput.of(2, 1,
                        List.of(album, dye(DyeColor.RED))), helper.getLevel()),
                "レシピと違う色の染料を受け入れた");

        final ItemStack redAlbum = populatedAlbum();
        AlbumItem.setColor(redAlbum, DyeColor.RED);
        helper.assertTrue(!red.matches(CraftingInput.of(2, 1,
                        List.of(redAlbum, dye(DyeColor.RED))), helper.getLevel()),
                "同色染料で無駄に再クラフトできた");

        final ItemStack blueAlbum = populatedAlbum();
        AlbumItem.setColor(blueAlbum, DyeColor.BLUE);
        final CraftingInput recolor = CraftingInput.of(2, 1, List.of(blueAlbum, dye(DyeColor.RED)));
        helper.assertTrue(red.matches(recolor, helper.getLevel()), "別の色へ染め直せない");
        helper.assertTrue(AlbumItem.getColor(assemble(red, recolor, helper)) == DyeColor.RED,
                "染め直した結果が赤にならない");
        helper.succeed();
    }

    /** 16色すべてが通常の shapeless として読み込まれ、1本の advancement から解放されること。 */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void everyAlbumDyeingColorLoadsAndUnlocks(GameTestHelper helper) {
        for (final DyeColor color : DyeColor.values()) {
            recipe(helper, "album_dyeing_" + color.getName());
        }

        //? if >=1.21.11 {
        final var id = Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "recipes/misc/album_dyeing");
        //?} else {
        /*final var id = ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "recipes/misc/album_dyeing");
        *///?}
        final var holder = helper.getLevel().getServer().getAdvancements().get(id);
        helper.assertTrue(holder != null, "RecipeManager に album_dyeing の advancement が読み込まれていない");
        final var advancement = holder.value();
        helper.assertTrue(advancement.criteria().keySet().containsAll(List.of("has_material", "has_the_recipe")),
                "Album 染色の advancement に material/recipe criterion が無い");

        //? if >=26.2 {
        final var trigger = (net.minecraft.advancements.triggers.InventoryChangeTrigger.TriggerInstance)
                advancement.criteria().get("has_material").triggerInstance();
        //?} elif >=1.21.11 {
        /*final var trigger = (net.minecraft.advancements.criterion.InventoryChangeTrigger.TriggerInstance)
                advancement.criteria().get("has_material").triggerInstance();
        *///?} else {
        /*final var trigger = (net.minecraft.advancements.critereon.InventoryChangeTrigger.TriggerInstance)
                advancement.criteria().get("has_material").triggerInstance();
        *///?}
        for (final ItemStack material : List.of(new ItemStack(ModItems.ALBUM.get()),
                new ItemStack(ModItems.GOLDEN_ALBUM.get()))) {
            helper.assertTrue(trigger.items().stream().anyMatch(predicate -> predicate.test(material)),
                    "Album 染色の解放条件が " + material + " を受け付けない");
        }
        helper.assertTrue(trigger.items().stream()
                        .noneMatch(predicate -> predicate.test(new ItemStack(Items.STONE))),
                "無関係な石が Album 染色を解放する");

        final var rewards = advancement.rewards().recipes();
        helper.assertTrue(rewards.size() == 16, "Album 染色の解放レシピが16本ではない: " + rewards.size());
        for (final DyeColor color : DyeColor.values()) {
            final String expected = "music_disc_maker:album_dyeing_" + color.getName();
            helper.assertTrue(rewards.stream().anyMatch(reward -> reward.toString().contains(expected)),
                    "Album 染色の解放に " + expected + " が無い");
        }
        helper.succeed();
    }

    private static ItemStack populatedAlbum() {
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        album.set(DataComponents.CUSTOM_NAME, Component.literal("保存すべき Album 名"));
        final ItemStack first = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        first.set(ModDataComponents.CUSTOM_TRACK.get(), track("first"));
        first.set(DataComponents.CUSTOM_NAME, Component.literal("一曲目の名前"));
        final ItemStack second = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        second.set(ModDataComponents.CUSTOM_TRACK.get(), track("second"));
        AlbumItem.setContents(album, new AlbumContents(List.of(first, second)));
        return album;
    }

    private static CustomTrackData track(String id) {
        return new CustomTrackData("https://example.invalid/album-" + id, id, "test", 123_000L, "", false);
    }

    private static ItemStack dye(DyeColor color) {
        //? if >=1.21.11 {
        final var item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(color.getName() + "_dye"));
        //?} elif >=1.21.2 {
        /*final var item = BuiltInRegistries.ITEM.getValue(ResourceLocation.withDefaultNamespace(color.getName() + "_dye"));
        *///?} else {
        /*final var item = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(color.getName() + "_dye"));
        *///?}
        if (item == null) {
            throw new IllegalStateException("Missing vanilla dye: " + color.getName());
        }
        return new ItemStack(item);
    }

    /** datapackへ実際に読まれた染色レシピを取得する。 */
    private static AlbumDyeRecipe recipe(GameTestHelper helper, String path) {
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
        helper.assertTrue(holder != null && holder.value() instanceof ShapelessRecipe,
                "RecipeManager の " + path + " が通常の shapeless レシピではない");
        helper.assertTrue(holder != null && holder.value() instanceof AlbumDyeRecipe,
                "RecipeManager の " + path + " が AlbumDyeRecipe ではない");
        return (AlbumDyeRecipe) holder.value();
    }

    private static ItemStack assemble(AlbumDyeRecipe recipe, CraftingInput input, GameTestHelper helper) {
        //? if >=26.1 {
        return recipe.assemble(input);
        //?} else {
        /*return recipe.assemble(input, helper.getLevel().registryAccess());
        *///?}
    }
}
//?} else {
//?}
