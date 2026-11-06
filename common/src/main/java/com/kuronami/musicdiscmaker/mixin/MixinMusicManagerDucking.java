package com.kuronami.musicdiscmaker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.client.audio.MusicDucking;

import net.minecraft.client.sounds.MusicManager;

/** 盤の曲が聞こえている間は BGM を一時停止し、MusicManager を進めない (次の BGM を始めない) ({@link MusicDucking})。 */
@Mixin(MusicManager.class)
public abstract class MixinMusicManagerDucking {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void mdm$holdMusicDuringDiscs(CallbackInfo ci) {
        if (MusicDucking.holdMusic()) {
            ci.cancel();
        }
    }
}
