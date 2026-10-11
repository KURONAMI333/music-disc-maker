package com.kuronami.musicdiscmaker.recipe;

import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModRecipeSerializers;

//? if >=1.21 {
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
//?} else {
/*import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.Ingredient;
*///?}
//? if >=26.1 {
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
//?} elif >=1.21 {
/*import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.crafting.CraftingBookCategory;
*///?} else {
/*import net.minecraft.world.item.crafting.CraftingBookCategory;
*///?}
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;

/**
 * 金インゴットで囲んだ Album を Golden Album にする。通常のクラフトは材料のデータを捨てるので、
 * 盤・順番・色・名前を結果へ引き継ぐ。レシピ本と JEI に普通の形で出るよう ShapedRecipe を継承し、
 * JSON の形もバニラの crafting_shaped と同じにする。
 */
public final class GoldenAlbumRecipe extends ShapedRecipe {

    //? if >=26.1 {
    public static final MapCodec<GoldenAlbumRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                    Recipe.CommonInfo.MAP_CODEC.forGetter(o -> o.commonInfo),
                    CraftingRecipe.CraftingBookInfo.MAP_CODEC.forGetter(o -> o.bookInfo),
                    ShapedRecipePattern.MAP_CODEC.forGetter(o -> o.shape),
                    ItemStackTemplate.CODEC.fieldOf("result").forGetter(o -> o.template))
            .apply(i, GoldenAlbumRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, GoldenAlbumRecipe> STREAM_CODEC = StreamCodec.composite(
            Recipe.CommonInfo.STREAM_CODEC, o -> o.commonInfo,
            CraftingRecipe.CraftingBookInfo.STREAM_CODEC, o -> o.bookInfo,
            ShapedRecipePattern.STREAM_CODEC, o -> o.shape,
            ItemStackTemplate.STREAM_CODEC, o -> o.template,
            GoldenAlbumRecipe::new);

    private final ShapedRecipePattern shape;
    private final ItemStackTemplate template;

    public GoldenAlbumRecipe(Recipe.CommonInfo commonInfo, CraftingRecipe.CraftingBookInfo bookInfo,
            ShapedRecipePattern pattern, ItemStackTemplate result) {
        super(commonInfo, bookInfo, pattern, result);
        this.shape = pattern;
        this.template = result;
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        final ItemStack album = findAlbum(input);
        return album.isEmpty() ? super.assemble(input) : album.transmuteCopy(ModItems.GOLDEN_ALBUM.get(), 1);
    }

    @Override
    @SuppressWarnings("unchecked")
    public RecipeSerializer<ShapedRecipe> getSerializer() {
        return (RecipeSerializer<ShapedRecipe>) (RecipeSerializer<?>) ModRecipeSerializers.GOLDEN_ALBUM.get();
    }
    //?} elif >=1.21 {
    /*public static final MapCodec<GoldenAlbumRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                    Codec.STRING.optionalFieldOf("group", "").forGetter(o -> o.groupName),
                    CraftingBookCategory.CODEC.fieldOf("category").orElse(CraftingBookCategory.MISC).forGetter(o -> o.bookCategory),
                    ShapedRecipePattern.MAP_CODEC.forGetter(o -> o.shape),
                    ItemStack.STRICT_CODEC.fieldOf("result").forGetter(o -> o.resultStack),
                    Codec.BOOL.optionalFieldOf("show_notification", true).forGetter(o -> o.notify))
            .apply(i, GoldenAlbumRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, GoldenAlbumRecipe> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, o -> o.groupName,
            ByteBufCodecs.idMapper(i -> CraftingBookCategory.values()[i], CraftingBookCategory::ordinal), o -> o.bookCategory,
            ShapedRecipePattern.STREAM_CODEC, o -> o.shape,
            ItemStack.STREAM_CODEC, o -> o.resultStack,
            ByteBufCodecs.BOOL, o -> o.notify,
            GoldenAlbumRecipe::new);

    private final String groupName;
    private final CraftingBookCategory bookCategory;
    private final ShapedRecipePattern shape;
    private final ItemStack resultStack;
    private final boolean notify;

    public GoldenAlbumRecipe(String group, CraftingBookCategory category, ShapedRecipePattern pattern,
            ItemStack result, boolean showNotification) {
        super(group, category, pattern, result, showNotification);
        this.groupName = group;
        this.bookCategory = category;
        this.shape = pattern;
        this.resultStack = result;
        this.notify = showNotification;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        final ItemStack album = findAlbum(input);
        return album.isEmpty() ? super.assemble(input, registries) : album.transmuteCopy(ModItems.GOLDEN_ALBUM.get(), 1);
    }

    @Override
    public RecipeSerializer<GoldenAlbumRecipe> getSerializer() {
        return ModRecipeSerializers.GOLDEN_ALBUM.get();
    }

    public static final class Serializer implements RecipeSerializer<GoldenAlbumRecipe> {
        @Override public MapCodec<GoldenAlbumRecipe> codec() { return MAP_CODEC; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, GoldenAlbumRecipe> streamCodec() { return STREAM_CODEC; }
    }
    *///?} else {
    /*public GoldenAlbumRecipe(ResourceLocation id, String group, CraftingBookCategory category, int width, int height,
            NonNullList<Ingredient> ingredients, ItemStack result, boolean showNotification) {
        super(id, group, category, width, height, ingredients, result, showNotification);
    }

    private static GoldenAlbumRecipe of(ShapedRecipe shaped) {
        return new GoldenAlbumRecipe(shaped.getId(), shaped.getGroup(), shaped.category(), shaped.getWidth(),
                shaped.getHeight(), shaped.getIngredients(), shaped.getResultItem(null), shaped.showNotification());
    }

    @Override
    public ItemStack assemble(CraftingContainer input, RegistryAccess registries) {
        for (int slot = 0; slot < input.getContainerSize(); slot++) {
            final ItemStack stack = input.getItem(slot);
            if (stack.is(ModItems.ALBUM.get())) {
                final ItemStack result = new ItemStack(ModItems.GOLDEN_ALBUM.get());
                if (stack.getTag() != null) result.setTag(stack.getTag().copy());
                return result;
            }
        }
        return super.assemble(input, registries);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.GOLDEN_ALBUM.get();
    }

    public static final class Serializer implements RecipeSerializer<GoldenAlbumRecipe> {
        @Override
        public GoldenAlbumRecipe fromJson(ResourceLocation id, JsonObject json) {
            return of(RecipeSerializer.SHAPED_RECIPE.fromJson(id, json));
        }

        @Override
        public GoldenAlbumRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            return of(RecipeSerializer.SHAPED_RECIPE.fromNetwork(id, buf));
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, GoldenAlbumRecipe recipe) {
            RecipeSerializer.SHAPED_RECIPE.toNetwork(buf, recipe);
        }
    }
    *///?}

    //? if >=1.21 {
    private static ItemStack findAlbum(CraftingInput input) {
        for (int slot = 0; slot < input.size(); slot++) {
            final ItemStack stack = input.getItem(slot);
            if (stack.is(ModItems.ALBUM.get())) return stack;
        }
        return ItemStack.EMPTY;
    }
    //?}
}
