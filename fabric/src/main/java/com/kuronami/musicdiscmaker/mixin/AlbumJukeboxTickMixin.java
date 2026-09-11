package com.kuronami.musicdiscmaker.mixin;

//? if >=1.21 {
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.compat.additionaladditions.AdditionalAdditionsCompat;
import com.kuronami.musicdiscmaker.platform.Services;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(JukeboxBlockEntity.class)
public class AlbumJukeboxTickMixin {

    @Unique
    private static Boolean musicdiscmaker$aaEnabled = null;

    @Unique
    private static boolean musicdiscmaker$disabled = false;

    @Inject(method = "tick", at = @At("TAIL"))
    private static void musicdiscmaker$mirrorAlbum(Level level, BlockPos pos, BlockState state,
            JukeboxBlockEntity jukebox, CallbackInfo ci) {
        if (musicdiscmaker$disabled) {
            return;
        }
        if (musicdiscmaker$aaEnabled == null) {
            musicdiscmaker$aaEnabled = Services.PLATFORM.isModLoaded("additionaladditions");
        }
        if (!musicdiscmaker$aaEnabled) {
            return;
        }
        try {
            AdditionalAdditionsCompat.onServerTick(level, pos, jukebox);
        } catch (final Throwable t) {
            musicdiscmaker$disabled = true;
            MusicDiscMaker.LOGGER.error(
                    "[MDM] Additional Additions album compat disabled due to an error", t);
        }
    }
}
//?} else {
//?}
