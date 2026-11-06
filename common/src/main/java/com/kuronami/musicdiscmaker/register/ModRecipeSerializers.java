package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;
import com.kuronami.musicdiscmaker.recipe.AlbumDyeRecipe;
import com.kuronami.musicdiscmaker.recipe.GoldenAlbumRecipe;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.crafting.RecipeSerializer;

/** MDM 固有のクラフト serializer。 */
public final class ModRecipeSerializers {

    public static final RegistrationProvider<RecipeSerializer<?>> SERIALIZERS =
            RegistrationProvider.get(Registries.RECIPE_SERIALIZER, MusicDiscMaker.MODID);

    /** 色ごとに1本ずつある Album 染色レシピ。中身を残すため結果は入力 Album から組み立てる。 */
    public static final RegistryHolder<RecipeSerializer<AlbumDyeRecipe>> ALBUM_DYEING =
            //? if >=26.1 {
            SERIALIZERS.register("album_dyeing",
                    () -> new RecipeSerializer<>(AlbumDyeRecipe.MAP_CODEC, AlbumDyeRecipe.STREAM_CODEC));
            //?} else {
            /*SERIALIZERS.register("album_dyeing", AlbumDyeRecipe.Serializer::new);
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
