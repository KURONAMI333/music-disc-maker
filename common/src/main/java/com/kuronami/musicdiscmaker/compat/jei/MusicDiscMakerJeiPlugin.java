package com.kuronami.musicdiscmaker.compat.jei;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.register.ModItems;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.registration.ISubtypeRegistration;

//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;

/**
 * Album / Golden Album の色を JEI の subtype として登録する。
 * これが無いと JEI は色違いを同じ品としてまとめてしまい、右側の一覧が 1 色しか出ない。
 * JEI は compileOnly。未導入の環境ではこのクラスは誰からも読まれない。
 */
@JeiPlugin
public final class MusicDiscMakerJeiPlugin implements IModPlugin {

    private static final ISubtypeInterpreter<ItemStack> ALBUM_COLOR = new ISubtypeInterpreter<>() {
        @Override
        public Object getSubtypeData(ItemStack stack, UidContext context) {
            final DyeColor color = AlbumItem.getColor(stack);
            return color == null ? "" : color.getName();
        }

        //? if <=1.21.1 {
        /*// JEI 19 以前はこのメソッドも interface の一部。20 以降は不要。
        public String getLegacyStringSubtypeInfo(ItemStack stack, UidContext context) {
            final DyeColor color = AlbumItem.getColor(stack);
            return color == null ? "" : color.getName();
        }
        *///?}
    };

    @Override
    public void registerItemSubtypes(ISubtypeRegistration registration) {
        registration.registerSubtypeInterpreter(ModItems.ALBUM.get(), ALBUM_COLOR);
        registration.registerSubtypeInterpreter(ModItems.GOLDEN_ALBUM.get(), ALBUM_COLOR);
    }

    //? if >=1.21.11 {
    @Override
    public Identifier getPluginUid() {
        return Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "jei_plugin");
    }
    //?} else {
    /*@Override
    public ResourceLocation getPluginUid() {
        //? if <=1.20.1 {
        return new ResourceLocation(MusicDiscMaker.MODID, "jei_plugin");
        //?} else {
        return ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "jei_plugin");
        //?}
    }
    *///?}
}
