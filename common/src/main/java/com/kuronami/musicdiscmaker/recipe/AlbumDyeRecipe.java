package com.kuronami.musicdiscmaker.recipe;

import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModRecipeSerializers;

//? if >=1.21 {
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.crafting.CraftingInput;
//?} else {
/*import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
*///?}
import net.minecraft.world.item.DyeColor;
//? if >=26.1 {
import net.minecraft.core.component.DataComponents;
//?} else {
//?}
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/** Album 1冊とバニラ染料1個だけを受け、Album の全データを保ったまま外装色を替える。 */
public final class AlbumDyeRecipe extends CustomRecipe {

    //? if >=26.1 {
    public AlbumDyeRecipe() {
    }
    //?} elif >=1.21 {
    /*public AlbumDyeRecipe(CraftingBookCategory category) {
        super(category);
    }
    *///?} else {
    /*public AlbumDyeRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }
    *///?}

    @Override
    //? if >=1.21 {
    public boolean matches(CraftingInput input, Level level) {
        return findAlbum(input) != ItemStack.EMPTY && findDye(input) != null;
    }
    //?} else {
    /*public boolean matches(CraftingContainer input, Level level) {
        return findAlbum(input) != ItemStack.EMPTY && findDye(input) != null;
    }
    *///?}

    @Override
    //? if >=26.1 {
    public ItemStack assemble(CraftingInput input) {
    //?} elif >=1.21 {
    /*public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
    *///?} else {
    /*public ItemStack assemble(CraftingContainer input, RegistryAccess registries) {
    *///?}
        final ItemStack album = findAlbum(input);
        final DyeColor color = findDye(input);
        if (album == ItemStack.EMPTY || color == null) {
            return ItemStack.EMPTY;
        }
        final ItemStack result = album.copy();
        result.setCount(1);
        AlbumItem.setColor(result, color);
        return result;
    }

    //? if <1.21.11 {
    /*@Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }
    *///?}

    @Override
    public RecipeSerializer<? extends CustomRecipe> getSerializer() {
        return ModRecipeSerializers.ALBUM_DYEING.get();
    }

    //? if >=1.21 {
    private static ItemStack findAlbum(CraftingInput input) {
        ItemStack album = ItemStack.EMPTY;
        int found = 0;
        for (int slot = 0; slot < input.size(); slot++) {
            final ItemStack stack = input.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            found++;
            if (stack.is(ModItems.ALBUM.get())) {
                if (!album.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                album = stack;
            }
        }
        return found == 2 ? album : ItemStack.EMPTY;
    }

    private static DyeColor findDye(CraftingInput input) {
        DyeColor color = null;
        int found = 0;
        ItemStack album = ItemStack.EMPTY;
        for (int slot = 0; slot < input.size(); slot++) {
            final ItemStack stack = input.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            found++;
            if (stack.is(ModItems.ALBUM.get())) {
                album = stack;
            } else if (dyeColor(stack) != null && color == null) {
                color = dyeColor(stack);
            } else {
                return null;
            }
        }
        return found == 2 && color != null && !album.isEmpty() && color != AlbumItem.getColor(album) ? color : null;
    }

    private static DyeColor dyeColor(ItemStack stack) {
        //? if >=26.1 {
        return stack.get(DataComponents.DYE);
        //?} else {
        /*return stack.getItem() instanceof DyeItem dye ? dye.getDyeColor() : null;
        *///?}
    }
    //?} else {
    /*private static ItemStack findAlbum(CraftingContainer input) {
        ItemStack album = ItemStack.EMPTY;
        int found = 0;
        for (int slot = 0; slot < input.getContainerSize(); slot++) {
            final ItemStack stack = input.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            found++;
            if (stack.is(ModItems.ALBUM.get())) {
                if (!album.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                album = stack;
            }
        }
        return found == 2 ? album : ItemStack.EMPTY;
    }

    private static DyeColor findDye(CraftingContainer input) {
        DyeColor color = null;
        int found = 0;
        ItemStack album = ItemStack.EMPTY;
        for (int slot = 0; slot < input.getContainerSize(); slot++) {
            final ItemStack stack = input.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            found++;
            if (stack.is(ModItems.ALBUM.get())) {
                album = stack;
            } else if (dyeColor(stack) != null && color == null) {
                color = dyeColor(stack);
            } else {
                return null;
            }
        }
        return found == 2 && color != null && !album.isEmpty() && color != AlbumItem.getColor(album) ? color : null;
    }

    private static DyeColor dyeColor(ItemStack stack) {
        return stack.getItem() instanceof DyeItem dye ? dye.getDyeColor() : null;
    }
    *///?}
}
