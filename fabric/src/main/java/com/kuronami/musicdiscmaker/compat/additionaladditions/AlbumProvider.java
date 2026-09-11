package com.kuronami.musicdiscmaker.compat.additionaladditions;

//? if >=26.1 {
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.compat.additionaladditions.AdditionalAdditionsAlbumSupport;

import net.minecraft.world.item.ItemStack;

import one.dqu.additionaladditions.feature.album.AlbumContents;
import one.dqu.additionaladditions.registry.AAMisc;
import one.dqu.additionaladditions.registry.AATags;

public final class AlbumProvider implements AdditionalAdditionsAlbumSupport.Provider {

    private AlbumProvider() {
    }

    public static void install() {
        AdditionalAdditionsAlbumSupport.install(new AlbumProvider());
    }

    @Override
    public boolean isAlbum(ItemStack stack) {
        return stack.is(AATags.ALBUMS);
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
//?} elif >=1.21.2 {
/*import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.compat.additionaladditions.AdditionalAdditionsAlbumSupport;

import net.minecraft.world.item.ItemStack;

import one.dqu.additionaladditions.feature.album.AlbumContents;
import one.dqu.additionaladditions.registry.AAMisc;


public final class AlbumProvider implements AdditionalAdditionsAlbumSupport.Provider {

    private AlbumProvider() {
    }

    public static void install() {
        AdditionalAdditionsAlbumSupport.install(new AlbumProvider());
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
*/
//?} elif >=1.21 {
/*import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.compat.additionaladditions.AdditionalAdditionsAlbumSupport;

import net.minecraft.world.item.ItemStack;

import one.dqu.additionaladditions.feature.album.AlbumContents;
import one.dqu.additionaladditions.registry.AAMisc;

public final class AlbumProvider implements AdditionalAdditionsAlbumSupport.Provider {

    private AlbumProvider() {
    }

    public static void install() {
        AdditionalAdditionsAlbumSupport.install(new AlbumProvider());
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
*/
//?} else {
//?}
