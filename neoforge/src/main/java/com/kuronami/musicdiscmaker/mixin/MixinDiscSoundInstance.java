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
 * NeoForge: custom disc に LavaPlayer PCM ({@link DiscSoundInstance#getCustomStream()}) を供給する。
 *
 * <p>NeoForge patch の {@code SoundInstance#getStream(SoundBufferLibrary, Sound, boolean)} (interface
 * default) を DiscSoundInstance だけで override する。SoundEngine の {@code play} を @Redirect する
 * 旧方式は、26.2 (play の戻り値が {@code SoundEngine.PlayResult} 化) で redirect ラッパを通すと
 * streaming チャンネルへの stream 供給が壊れ、custom/vanilla を問わず streaming 音 (records) が全て
 * 無音になった。DiscSoundInstance 自身の getStream override なら vanilla の再生経路には一切触れず、
 * Fabric 側の {@link MixinDiscSoundInstance 相当}({@code FabricSoundInstance#getAudioStream} override)
 * と同型になる。common の {@link DiscSoundInstance} は vanilla neoForm classpath でこの patch メソッドが
 * 見えないため、override は loader 層 (ここ) に置く。
 *
 * <p>1.21.1 でもこの default は存在する (NeoForge 21.1.227 の {@code SoundInstance} を javap で確認)。
 * 加えて 1.21.1 だけは {@code SoundEngine} の @Redirect も残っているが、二重には生成されない
 * (直再生は redirect が先に返し、Sound Physics の包み経由の再生は redirect の判定を外れて包みが
 * 元インスタンスのこの override へ委譲する)。
 */
@Mixin({DiscSoundInstance.class, VanillaSpeakerSoundInstance.class})
public abstract class MixinDiscSoundInstance {

    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return ((CustomAudioStreamProvider) this).createAudioStream(buffers, sound, looping);
    }
}
