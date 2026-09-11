package com.kuronami.musicdiscmaker.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;

import com.kuronami.musicdiscmaker.client.audio.CustomAudioStreamProvider;
import com.kuronami.musicdiscmaker.client.audio.DiscSoundInstance;
import com.kuronami.musicdiscmaker.client.audio.VanillaSpeakerSoundInstance;

import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
//? if >=1.21.2 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}

/**
 * Fabric: custom disc に LavaPlayer PCM ({@link DiscSoundInstance#getCustomStream()}) を供給する。
 * 自分で SoundEngine を redirect すると fabric-sound-api-v1 の SoundSystemMixin と @Redirect 衝突して
 * クラッシュするため、Fabric 公式の {@link FabricSoundInstance#getAudioStream} を override する
 * (この interface は全 SoundInstance に interface injection で暗黙実装されている)。
 * 26.2: getAudioStream の第2引数は {@code ResourceLocation}→{@code Identifier} に rename 済み。
 */
@Mixin({DiscSoundInstance.class, VanillaSpeakerSoundInstance.class})
public abstract class MixinDiscSoundInstance implements FabricSoundInstance {

    @Override
    //? if >=1.21.2 {
    public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, Identifier id, boolean repeatInstantly) {
    //?} else {
    /*public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, ResourceLocation id, boolean repeatInstantly) {
    *///?}
        return ((CustomAudioStreamProvider) this).createAudioStream(loader, null, repeatInstantly);
    }
}
