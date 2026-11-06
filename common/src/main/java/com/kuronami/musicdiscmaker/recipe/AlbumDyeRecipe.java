package com.kuronami.musicdiscmaker.recipe;

import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.register.ModRecipeSerializers;

//? if >=1.21 {
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
//?} else {
/*import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.Ingredient;
*///?}
//? if >=1.21 && <26.1 {
import net.minecraft.core.HolderLookup;
//?}
//? if >=26.1 {
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
//?}
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;

/**
 * Album 1冊と色の付いた染料1個を、その色の Album へ染める。通常のクラフトは材料のデータを捨てるので、
 * 入力 Album のコピーへ盤・順序・名前を残したまま外装色だけを替える。
 *
 * <p>レシピ本と JEI に普通の形で出るよう {@link ShapelessRecipe} を継承し、JSON もバニラの
 * crafting_shapeless と同じ形にする。色ごとに1本ずつレシピを持つので、結果の見本にも同じ色を載せる。
 */
public final class AlbumDyeRecipe extends ShapelessRecipe {

    //? if >=26.1 {
    public static final MapCodec<AlbumDyeRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                    Recipe.CommonInfo.MAP_CODEC.forGetter(o -> o.commonInfo),
                    CraftingRecipe.CraftingBookInfo.MAP_CODEC.forGetter(o -> o.bookInfo),
                    ItemStackTemplate.CODEC.fieldOf("result").forGetter(o -> o.template),
                    Ingredient.CODEC.listOf(1, 9)
                            .fieldOf("ingredients").forGetter(o -> o.ingredientList))
            .apply(i, AlbumDyeRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, AlbumDyeRecipe> STREAM_CODEC = StreamCodec.composite(
            Recipe.CommonInfo.STREAM_CODEC, o -> o.commonInfo,
            CraftingRecipe.CraftingBookInfo.STREAM_CODEC, o -> o.bookInfo,
            ItemStackTemplate.STREAM_CODEC, o -> o.template,
            Ingredient.CONTENTS_STREAM_CODEC.apply(ByteBufCodecs.list()), o -> o.ingredientList,
            AlbumDyeRecipe::new);

    private final Recipe.CommonInfo commonInfo;
    private final CraftingRecipe.CraftingBookInfo bookInfo;
    private final ItemStackTemplate template;
    private final List<Ingredient> ingredientList;
    private final DyeColor color;

    public AlbumDyeRecipe(Recipe.CommonInfo commonInfo, CraftingRecipe.CraftingBookInfo bookInfo,
            ItemStackTemplate result, List<Ingredient> ingredients) {
        super(commonInfo, bookInfo, result, ingredients);
        this.commonInfo = commonInfo;
        this.bookInfo = bookInfo;
        this.template = result;
        this.ingredientList = ingredients;
        this.color = templateColor(result);
    }

    /**
     * 結果の見本に載っている色を、保存データの component から直接読む。
     *
     * <p>datapack の読込中は {@link ItemStackTemplate#create()} も {@code Item#components()} も
     * 呼べない (component が未束縛)。復号済みのパッチを分割した地図から
     * {@code music_disc_maker:album_color} だけを取り出す。
     */
    private static DyeColor templateColor(ItemStackTemplate template) {
        return template.components().split().added().get(ModDataComponents.ALBUM_COLOR.get());
    }

    @Override
    public ItemStack assemble(CraftingInput input) {
        final ItemStack album = findAlbum(input);
        return album.isEmpty() ? ItemStack.EMPTY : tinted(album);
    }

    @Override
    @SuppressWarnings("unchecked")
    public RecipeSerializer<ShapelessRecipe> getSerializer() {
        return (RecipeSerializer<ShapelessRecipe>) (RecipeSerializer<?>) ModRecipeSerializers.ALBUM_DYEING.get();
    }
    //?} elif >=1.21.4 {
    /*public static final MapCodec<AlbumDyeRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                    Codec.STRING.optionalFieldOf("group", "").forGetter(o -> o.groupName),
                    CraftingBookCategory.CODEC.fieldOf("category").orElse(CraftingBookCategory.MISC).forGetter(o -> o.bookCategory),
                    ItemStack.STRICT_CODEC.fieldOf("result").forGetter(o -> o.resultStack),
                    Codec.lazyInitialized(() -> Ingredient.CODEC.listOf(1, 9))
                            .fieldOf("ingredients").forGetter(o -> o.ingredientList))
            .apply(i, AlbumDyeRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, AlbumDyeRecipe> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, o -> o.groupName,
            CraftingBookCategory.STREAM_CODEC, o -> o.bookCategory,
            ItemStack.STREAM_CODEC, o -> o.resultStack,
            Ingredient.CONTENTS_STREAM_CODEC.apply(ByteBufCodecs.list()), o -> o.ingredientList,
            AlbumDyeRecipe::new);

    private final String groupName;
    private final CraftingBookCategory bookCategory;
    private final ItemStack resultStack;
    private final List<Ingredient> ingredientList;
    private final DyeColor color;

    public AlbumDyeRecipe(String group, CraftingBookCategory category, ItemStack result, List<Ingredient> ingredients) {
        super(group, category, result, ingredients);
        this.groupName = group;
        this.bookCategory = category;
        this.resultStack = result;
        this.ingredientList = ingredients;
        this.color = AlbumItem.getColor(result);
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        final ItemStack album = findAlbum(input);
        return album.isEmpty() ? ItemStack.EMPTY : tinted(album);
    }

    @Override
    @SuppressWarnings("unchecked")
    public RecipeSerializer<ShapelessRecipe> getSerializer() {
        return (RecipeSerializer<ShapelessRecipe>) (RecipeSerializer<?>) ModRecipeSerializers.ALBUM_DYEING.get();
    }

    public static final class Serializer implements RecipeSerializer<AlbumDyeRecipe> {
        @Override public MapCodec<AlbumDyeRecipe> codec() { return MAP_CODEC; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, AlbumDyeRecipe> streamCodec() { return STREAM_CODEC; }
    }
    *///?} elif >=1.21 {
    /*private static final Codec<NonNullList<Ingredient>> INGREDIENTS_CODEC =
            Ingredient.CODEC_NONEMPTY.listOf().xmap(
                    list -> NonNullList.of(Ingredient.EMPTY, list.toArray(Ingredient[]::new)),
                    nonNull -> nonNull);

    public static final MapCodec<AlbumDyeRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                    Codec.STRING.optionalFieldOf("group", "").forGetter(o -> o.groupName),
                    CraftingBookCategory.CODEC.fieldOf("category").orElse(CraftingBookCategory.MISC).forGetter(o -> o.bookCategory),
                    ItemStack.STRICT_CODEC.fieldOf("result").forGetter(o -> o.resultStack),
                    INGREDIENTS_CODEC.fieldOf("ingredients").forGetter(o -> o.ingredientList))
            .apply(i, AlbumDyeRecipe::new));
    public static final StreamCodec<RegistryFriendlyByteBuf, AlbumDyeRecipe> STREAM_CODEC = StreamCodec.of(
            (buffer, recipe) -> {
                buffer.writeUtf(recipe.groupName);
                buffer.writeEnum(recipe.bookCategory);
                buffer.writeVarInt(recipe.ingredientList.size());
                for (final Ingredient ingredient : recipe.ingredientList) {
                    Ingredient.CONTENTS_STREAM_CODEC.encode(buffer, ingredient);
                }
                ItemStack.STREAM_CODEC.encode(buffer, recipe.resultStack);
            },
            buffer -> {
                final String group = buffer.readUtf();
                final CraftingBookCategory category = buffer.readEnum(CraftingBookCategory.class);
                final NonNullList<Ingredient> ingredients = NonNullList.withSize(buffer.readVarInt(), Ingredient.EMPTY);
                ingredients.replaceAll(ignored -> Ingredient.CONTENTS_STREAM_CODEC.decode(buffer));
                return new AlbumDyeRecipe(group, category, ItemStack.STREAM_CODEC.decode(buffer), ingredients);
            });

    private final String groupName;
    private final CraftingBookCategory bookCategory;
    private final ItemStack resultStack;
    private final NonNullList<Ingredient> ingredientList;
    private final DyeColor color;

    public AlbumDyeRecipe(String group, CraftingBookCategory category, ItemStack result, NonNullList<Ingredient> ingredients) {
        super(group, category, result, ingredients);
        this.groupName = group;
        this.bookCategory = category;
        this.resultStack = result;
        this.ingredientList = ingredients;
        this.color = AlbumItem.getColor(result);
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        final ItemStack album = findAlbum(input);
        return album.isEmpty() ? ItemStack.EMPTY : tinted(album);
    }

    @Override
    public RecipeSerializer<AlbumDyeRecipe> getSerializer() {
        return ModRecipeSerializers.ALBUM_DYEING.get();
    }

    public static final class Serializer implements RecipeSerializer<AlbumDyeRecipe> {
        @Override public MapCodec<AlbumDyeRecipe> codec() { return MAP_CODEC; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, AlbumDyeRecipe> streamCodec() { return STREAM_CODEC; }
    }
    *///?} else {
    /*private final String groupName;
    private final CraftingBookCategory bookCategory;
    private final ItemStack resultStack;
    private final NonNullList<Ingredient> ingredientList;
    private final DyeColor color;

    public AlbumDyeRecipe(ResourceLocation id, String group, CraftingBookCategory category, ItemStack result,
            NonNullList<Ingredient> ingredients) {
        super(id, group, category, result, ingredients);
        this.groupName = group;
        this.bookCategory = category;
        this.resultStack = result;
        this.ingredientList = ingredients;
        this.color = AlbumItem.getColor(result);
    }

    @Override
    public ItemStack assemble(CraftingContainer input, RegistryAccess registries) {
        final ItemStack album = findAlbum(input);
        return album.isEmpty() ? ItemStack.EMPTY : tinted(album);
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.ALBUM_DYEING.get();
    }

    public static final class Serializer implements RecipeSerializer<AlbumDyeRecipe> {
        @Override
        public AlbumDyeRecipe fromJson(ResourceLocation id, JsonObject json) {
            // バニラ 1.20.1 の結果の読み取りは item と count だけで、nbt は Forge だけが読む。
            // Fabric でも色付きの見本になるよう、結果の色は JSON から自分で読んで付ける。
            final ShapelessRecipe base = RecipeSerializer.SHAPELESS_RECIPE.fromJson(id, json);
            final ItemStack result = base.getResultItem(null).copy();
            final JsonObject resultJson = json.getAsJsonObject("result");
            if (resultJson != null && resultJson.has("nbt") && resultJson.get("nbt").isJsonObject()
                    && resultJson.getAsJsonObject("nbt").has("album_color")) {
                final DyeColor dye = DyeColor.byName(
                        resultJson.getAsJsonObject("nbt").get("album_color").getAsString(), null);
                if (dye != null) AlbumItem.setColor(result, dye);
            }
            return new AlbumDyeRecipe(base.getId(), base.getGroup(), base.category(), result,
                    base.getIngredients());
        }

        @Override
        public AlbumDyeRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            return of(RecipeSerializer.SHAPELESS_RECIPE.fromNetwork(id, buffer));
        }

        @Override
        public void toNetwork(FriendlyByteBuf buffer, AlbumDyeRecipe recipe) {
            RecipeSerializer.SHAPELESS_RECIPE.toNetwork(buffer, recipe);
        }

        private static AlbumDyeRecipe of(ShapelessRecipe base) {
            return new AlbumDyeRecipe(base.getId(), base.getGroup(), base.category(),
                    base.getResultItem(null), base.getIngredients());
        }
    }
    *///?}

    @Override
    //? if >=1.21 {
    public boolean matches(CraftingInput input, Level level) {
    //?} else {
    /*public boolean matches(CraftingContainer input, Level level) {
    *///?}
        if (color == null) {
            return false;
        }
        final ItemStack album = findAlbum(input);
        return !album.isEmpty() && AlbumItem.getColor(album) != color && super.matches(input, level);
    }

    //? if <1.21.2 {
    /*@Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }
    *///?}

    private ItemStack tinted(ItemStack album) {
        final ItemStack result = album.copy();
        result.setCount(1);
        AlbumItem.setColor(result, color);
        return result;
    }

    //? if >=1.21 {
    private static ItemStack findAlbum(CraftingInput input) {
        for (int slot = 0; slot < input.size(); slot++) {
            final ItemStack stack = input.getItem(slot);
            if (AlbumItem.isAlbum(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
    //?} else {
    /*private static ItemStack findAlbum(CraftingContainer input) {
        for (int slot = 0; slot < input.getContainerSize(); slot++) {
            final ItemStack stack = input.getItem(slot);
            if (AlbumItem.isAlbum(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
    *///?}
}
