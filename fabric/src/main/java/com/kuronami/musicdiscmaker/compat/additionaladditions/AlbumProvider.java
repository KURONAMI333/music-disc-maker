package com.kuronami.musicdiscmaker.compat.additionaladditions;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.compat.album.AlbumSupport;

import net.minecraft.world.item.ItemStack;

import one.dqu.additionaladditions.feature.album.AlbumContents;
import one.dqu.additionaladditions.registry.AAMisc;

/**
 * {@link AlbumSupport.Provider} の Additional Additions 実装 (Fabric)。
 * **このクラスと {@link AdditionalAdditionsCompat} だけが AA の型を参照する**。
 *
 * <p>AA のアルバム item は {@code JUKEBOX_PLAYABLE} を持たず、AA 自身も独自の item tag
 * ({@code AAMisc.ALBUMS_TAG}) で識別している。ここでも同じ tag を使う (中身の有無に依らず
 * 「アルバムである」と判定できるので、空のアルバムを挿してから中身を入れる流れも壊さない)。
 */
public final class AlbumProvider implements AlbumSupport.Provider {

    private AlbumProvider() {
    }

    /**
     * AA がロードされている時だけ呼ぶこと。呼び出し元が {@code isModLoaded} ゲートを通すので、
     * AA 不在環境ではこのクラスが class-load されず AA 型の解決も走らない。
     */
    public static void install() {
        AlbumSupport.install(new AlbumProvider());
    }

    @Override
    public boolean isAlbum(ItemStack stack) {
        return stack.is(AAMisc.ALBUMS_TAG);
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
