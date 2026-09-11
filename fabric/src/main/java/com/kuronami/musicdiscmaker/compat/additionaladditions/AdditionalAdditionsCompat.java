package com.kuronami.musicdiscmaker.compat.additionaladditions;

//? if >=1.21 {
import java.util.List;

import com.kuronami.musicdiscmaker.event.ExternalPlaybackMirror;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

import one.dqu.additionaladditions.feature.album.AlbumContents;
import one.dqu.additionaladditions.feature.album.AlbumJukeboxExtension;
import one.dqu.additionaladditions.registry.AAMisc;

public final class AdditionalAdditionsCompat {

    private AdditionalAdditionsCompat() {
    }

    public static void onServerTick(Level level, BlockPos pos, JukeboxBlockEntity jukebox) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!(jukebox instanceof AlbumJukeboxExtension album)) {
            return;
        }
        final ItemStack held = jukebox.getTheItem();

        ItemStack mdmDisc = null;
        if (album.additionaladditions$isPlaying()) {
            final int track = album.additionaladditions$getTrack();
            if (track >= 0) {
                final AlbumContents contents =
                        held.getOrDefault(AAMisc.ALBUM_CONTENTS_COMPONENT.get(), AlbumContents.EMPTY);
                final List<ItemStack> items = contents.items();
                if (track < items.size()) {
                    final ItemStack candidate = items.get(track);
                    if (candidate.is(ModItems.CUSTOM_MUSIC_DISC.get())
                            && candidate.has(ModDataComponents.CUSTOM_TRACK.get())) {
                        mdmDisc = candidate;
                    }
                }
            }
        }

        ExternalPlaybackMirror.mirrorAlbumJukebox(serverLevel, pos, held, mdmDisc);
    }
}
//?} else {
//?}
