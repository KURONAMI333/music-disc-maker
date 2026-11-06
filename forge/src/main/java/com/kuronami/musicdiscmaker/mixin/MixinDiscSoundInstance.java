package com.kuronami.musicdiscmaker.mixin;

import java.util.concurrent.CompletableFuture;

import org.spongepowered.asm.mixin.Mixin;

import com.kuronami.musicdiscmaker.client.audio.CustomAudioStreamProvider;
import com.kuronami.musicdiscmaker.client.audio.DiscSoundInstance;
import com.kuronami.musicdiscmaker.client.audio.VanillaSpeakerSoundInstance;

import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;

/**
 * Forge: custom disc に LavaPlayer PCM ({@link DiscSoundInstance#getCustomStream()}) を供給する。
 *
 * <p>Forge patch の {@code SoundInstance#getStream(SoundBufferLibrary, Sound, boolean)} (interface
 * default) を DiscSoundInstance だけで override する。{@link MixinSoundEngine} の @Redirect だけでは、
 * Sound Physics のように {@code SoundEngine#play} を横取りして自前の包み (元インスタンスの
 * getStream へ委譲する) で鳴らし直す MOD の経路で {@code instanceof CustomAudioStreamProvider} が
 * 成り立たず、登録済みの無音 OGG が返ってしまう。インスタンス自身の override なら、委譲が
 * そのままこのメソッドへ来る。direct 再生では redirect が先に返すので PCM は二重に生成されない。
 */
@Mixin({DiscSoundInstance.class, VanillaSpeakerSoundInstance.class})
public abstract class MixinDiscSoundInstance {

    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return ((CustomAudioStreamProvider) this).createAudioStream(buffers, sound, looping);
    }
}
