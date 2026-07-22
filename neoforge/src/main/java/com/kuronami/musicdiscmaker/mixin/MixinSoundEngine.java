package com.kuronami.musicdiscmaker.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.kuronami.musicdiscmaker.client.audio.DiscSoundInstance;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;

/**
 * custom disc 再生時、NeoForge の {@code SoundEngine.play} が呼ぶ {@code soundInstance.getStream}
 * (NeoForge 拡張点) を横取りし、登録 OGG の代わりに LavaPlayer PCM
 * ({@link DiscSoundInstance#getCustomStream()}) を供給する。
 */
@Mixin(SoundEngine.class)
public class MixinSoundEngine {

    @Redirect(
            method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/resources/sounds/SoundInstance;getStream(Lnet/minecraft/client/sounds/SoundBufferLibrary;Lnet/minecraft/client/resources/sounds/Sound;Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<AudioStream> mdm$redirectStream(SoundInstance instance, SoundBufferLibrary buffers, Sound sound, boolean looping) {
        if (instance instanceof DiscSoundInstance disc) {
            return disc.getCustomStream();
        }
        return instance.getStream(buffers, sound, looping);
    }
}
