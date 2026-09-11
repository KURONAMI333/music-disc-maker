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
import net.minecraft.resources.ResourceKey;
//?} elif >=1.21.2 {
/*import net.minecraft.resources.Identifier;
*///?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
//? if <1.21.2 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}

/** Album の染色は、外装だけを変え、収納物と名前を完全に残す。 */
public final class AlbumDyeingGameTests {
    private static final String TEMPLATE = "empty8x3x8";

    private AlbumDyeingGameTests() {
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void dyeingKeepsAlbumMetadataAndContents(GameTestHelper helper) {
        final ItemStack album = populatedAlbum();
        helper.assertTrue(AlbumItem.getColor(album) == null, "旧無色 Album が既定色componentを持っている");

        final AlbumDyeRecipe recipe = recipe(helper);
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

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void dyeingRejectsExtraWrongAndSameColorInputs(GameTestHelper helper) {
        final AlbumDyeRecipe recipe = recipe(helper);
        final ItemStack album = populatedAlbum();
        helper.assertTrue(!recipe.matches(CraftingInput.of(3, 1,
                        List.of(album, dye(DyeColor.RED), dye(DyeColor.BLUE))), helper.getLevel()),
                "染料2個を受け入れた");
        helper.assertTrue(!recipe.matches(CraftingInput.of(2, 1,
                        List.of(album, new ItemStack(Items.STONE))), helper.getLevel()),
                "Album と無関係な材料を受け入れた");

        AlbumItem.setColor(album, DyeColor.RED);
        helper.assertTrue(!recipe.matches(CraftingInput.of(2, 1,
                        List.of(album, dye(DyeColor.RED))), helper.getLevel()),
                "同色染料で無駄に再クラフトできた");
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
        //? if >=1.21.2 {
        final var item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(color.getName() + "_dye"));
        //?} else {
        /*final var item = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(color.getName() + "_dye"));
        *///?}
        if (item == null) {
            throw new IllegalStateException("Missing vanilla dye: " + color.getName());
        }
        return new ItemStack(item);
    }

    /** datapackへ実際に読まれた special recipe を取得する。 */
    private static AlbumDyeRecipe recipe(GameTestHelper helper) {
        //? if >=1.21.11 {
        final var id = ResourceKey.<net.minecraft.world.item.crafting.Recipe<?>>create(
                Registries.RECIPE, Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "album_dyeing"));
        final var holder = helper.getLevel().getServer().getRecipeManager().byKey(id).orElse(null);
        //?} else {
        /*final var id = ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "album_dyeing");
        final var holder = helper.getLevel().getRecipeManager().byKey(id).orElse(null);
        *///?}
        helper.assertTrue(holder != null, "RecipeManager に album_dyeing が読み込まれていない");
        helper.assertTrue(holder != null && holder.value() instanceof AlbumDyeRecipe,
                "RecipeManager の album_dyeing が AlbumDyeRecipe ではない");
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
