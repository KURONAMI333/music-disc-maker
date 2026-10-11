package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;
import com.kuronami.musicdiscmaker.recipe.AlbumDyeRecipe;
import com.kuronami.musicdiscmaker.recipe.GoldenAlbumRecipe;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
//? if >=26.1 {
//?} elif >=1.21.4 {
//?} else {
/*import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
*///?}

/** MDM 固有のクラフト serializer。 */
public final class ModRecipeSerializers {

    public static final RegistrationProvider<RecipeSerializer<?>> SERIALIZERS =
            RegistrationProvider.get(Registries.RECIPE_SERIALIZER, MusicDiscMaker.MODID);

    public static final RegistryHolder<RecipeSerializer<AlbumDyeRecipe>> ALBUM_DYEING =
            //? if >=26.1 {
            SERIALIZERS.register("album_dyeing", () -> {
                // unit の送信時は等値性が必要。読込側と同じ状態なしレシピを共有する。
                final AlbumDyeRecipe recipe = new AlbumDyeRecipe();
                return new RecipeSerializer<>(
                        com.mojang.serialization.MapCodec.unit(recipe),
                        net.minecraft.network.codec.StreamCodec.unit(recipe));
            });
            //?} elif >=1.21.4 {
            /*SERIALIZERS.register("album_dyeing", () -> new net.minecraft.world.item.crafting.CustomRecipe.Serializer<>(AlbumDyeRecipe::new));
            *///?} elif >=1.21 {
            /*SERIALIZERS.register("album_dyeing", () -> new SimpleCraftingRecipeSerializer<>(AlbumDyeRecipe::new));
            *///?} else {
            /*SERIALIZERS.register("album_dyeing", () -> new net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer<>(AlbumDyeRecipe::new));
            *///?}

    /** Album を Golden Album にする、中身を引き継ぐ shaped レシピ。 */
    public static final RegistryHolder<RecipeSerializer<GoldenAlbumRecipe>> GOLDEN_ALBUM =
            //? if >=26.1 {
            SERIALIZERS.register("golden_album",
                    () -> new RecipeSerializer<>(GoldenAlbumRecipe.MAP_CODEC, GoldenAlbumRecipe.STREAM_CODEC));
            //?} else {
            /*SERIALIZERS.register("golden_album", GoldenAlbumRecipe.Serializer::new);
            *///?}

    private ModRecipeSerializers() {
    }

    public static void init() {
    }
}
