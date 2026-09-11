package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;
import com.kuronami.musicdiscmaker.recipe.AlbumDyeRecipe;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;
//? if >=26.1 {
//?} elif >=1.21.11 {
//?} else {
/*import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
*///?}

/** MDM 固有のクラフト serializer。 */
public final class ModRecipeSerializers {

    public static final RegistrationProvider<RecipeSerializer<?>> SERIALIZERS =
            RegistrationProvider.get(Registries.RECIPE_SERIALIZER, MusicDiscMaker.MODID);

    public static final RegistryHolder<RecipeSerializer<AlbumDyeRecipe>> ALBUM_DYEING =
            //? if >=26.1 {
            SERIALIZERS.register("album_dyeing", () -> new RecipeSerializer<>(
                    com.mojang.serialization.MapCodec.unit(new AlbumDyeRecipe()),
                    net.minecraft.network.codec.StreamCodec.unit(new AlbumDyeRecipe())));
            //?} elif >=1.21.11 {
            /*SERIALIZERS.register("album_dyeing", () -> new net.minecraft.world.item.crafting.CustomRecipe.Serializer<>(AlbumDyeRecipe::new));
            *///?} elif >=1.21 {
            /*SERIALIZERS.register("album_dyeing", () -> new SimpleCraftingRecipeSerializer<>(AlbumDyeRecipe::new));
            *///?} else {
            /*SERIALIZERS.register("album_dyeing", () -> new net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer<>(AlbumDyeRecipe::new));
            *///?}

    private ModRecipeSerializers() {
    }

    public static void init() {
    }
}
