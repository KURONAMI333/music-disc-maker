package com.kuronami.musicdiscmaker.compat.additionaladditions;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.compat.additionaladditions.AdditionalAdditionsAlbumSupport;

//? if >=26.2 {
//?} elif >=26.1 {
/*import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
*///?} else {
//?}
import net.minecraft.world.item.ItemStack;

import one.dqu.additionaladditions.feature.album.AlbumContents;
import one.dqu.additionaladditions.registry.AAMisc;
//? if >=26.2 {
import one.dqu.additionaladditions.registry.AATags;
//?} else {
//?}

/**
 * {@link AdditionalAdditionsAlbumSupport.Provider} の Additional Additions 実装 (NeoForge)。
 * **このクラスと {@link AdditionalAdditionsCompat} だけが AA の型を参照する**。
 *
 * <p>帯固有の読み替え (mod-047 参照実装は AA 10.0.5・こちらは 10.0.10): 参照実装はアルバム item を
 * {@code AAMisc.ALBUMS_TAG} で識別していたが、10.0.10 でその tag フィールドは {@code AAMisc} から
 * {@link AATags#ALBUMS} へ移動している (album 本体の item tag データ ({@code data/additionaladditions/
 * tags/item/albums.json}) 自体は健在で、染色バリアント 17 種を含む)。tag のまま識別する
 * (中身の有無に依らず「アルバムである」と判定できるので、空のアルバムを挿してから中身を入れる流れも
 * 壊さない。datapack でアルバム item が足された場合もこの tag が拾う)。
 */
public final class AlbumProvider implements AdditionalAdditionsAlbumSupport.Provider {
    //? if >=26.2 {
    //?} elif >=26.1 {

    /*private static final TagKey<Item> ALBUMS_TAG =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("additionaladditions", "albums"));
    *///?} else {
    //?}

    private AlbumProvider() {
    }

    /**
     * AA がロードされている時だけ呼ぶこと。呼び出し元が {@code isModLoaded} ゲートを通すので、
     * AA 不在環境ではこのクラスが class-load されず AA 型の解決も走らない。
     */
    public static void install() {
        AdditionalAdditionsAlbumSupport.install(new AlbumProvider());
    }

    @Override
    public boolean isAlbum(ItemStack stack) {
        //? if >=26.2 {
        return stack.is(AATags.ALBUMS);
        //?} elif >=26.1 {
        /*return stack.is(ALBUMS_TAG);
        *///?} else {
        /*return stack.is(AAMisc.ALBUMS_TAG);
        *///?}
    }

    @Override
    @Nullable
    public List<ItemStack> contents(ItemStack stack) {
        if (!isAlbum(stack)) {
            return null;
        }
        final AlbumContents contents =
                stack.getOrDefault(AAMisc.ALBUM_CONTENTS_COMPONENT.get(), AlbumContents.EMPTY);
        return contents.items();
    }
}

