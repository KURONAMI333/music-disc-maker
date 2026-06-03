package com.kuronami.musicdiscmaker.client.audio;

import java.util.concurrent.CompletableFuture;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.register.ModSounds;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * jukebox 位置で鳴る custom disc の SoundInstance。NeoForge の {@code getStream} 拡張点を
 * override し、登録 OGG の代わりに LavaPlayer PCM ({@link LavaPlayerAudioStream}) を供給する。
 * SoundSource.RECORDS + 位置 + 線形減衰で、vanilla disc 同様の空間音声になる。
 */
public class DiscSoundInstance extends AbstractTickableSoundInstance {

    private final IAudioSource source;

    public DiscSoundInstance(BlockPos pos, IAudioSource source) {
        super(ModSounds.CUSTOM_DISC_PLAYBACK.get(), SoundSource.RECORDS, RandomSource.create());
        this.source = source;
        this.x = pos.getX() + 0.5;
        this.y = pos.getY() + 0.5;
        this.z = pos.getZ() + 0.5;
        this.volume = Config.VOLUME_MULTIPLIER.get().floatValue();
        this.pitch = 1.0F;
        this.looping = false;
        this.relative = false;
        this.attenuation = Attenuation.LINEAR;
    }

    @Override
    public void tick() {
        // ライフサイクルは requestStop() / SoundManager.stop で制御するので何もしない
    }

    /** NeoForge 拡張点: vanilla の OGG ロードを置き換えて LavaPlayer ストリームを返す。 */
    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(new LavaPlayerAudioStream(source));
    }

    public void requestStop() {
        this.stop();
    }
}
