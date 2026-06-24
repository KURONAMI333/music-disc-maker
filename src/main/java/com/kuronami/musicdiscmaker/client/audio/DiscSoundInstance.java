package com.kuronami.musicdiscmaker.client.audio;

import java.util.concurrent.CompletableFuture;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.register.ModSounds;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public class DiscSoundInstance extends AbstractTickableSoundInstance {

    private final IAudioSource source;
    @Nullable
    private final Entity followEntity;

    public DiscSoundInstance(BlockPos pos, IAudioSource source) {
        super(ModSounds.CUSTOM_DISC_PLAYBACK.get(), SoundSource.RECORDS, RandomSource.create());
        this.source = source;
        this.followEntity = null;
        this.x = pos.getX() + 0.5;
        this.y = pos.getY() + 0.5;
        this.z = pos.getZ() + 0.5;
        initCommon();
    }

    public DiscSoundInstance(Entity entity, IAudioSource source) {
        super(ModSounds.CUSTOM_DISC_PLAYBACK.get(), SoundSource.RECORDS, RandomSource.create());
        this.source = source;
        this.followEntity = entity;
        this.x = entity.getX();
        this.y = entity.getY();
        this.z = entity.getZ();
        initCommon();
    }

    private void initCommon() {
        this.volume = Config.VOLUME_MULTIPLIER.get().floatValue();
        this.pitch = 1.0F;
        this.looping = false;
        this.relative = false;
        this.attenuation = Attenuation.LINEAR;
    }

    @Override
    public void tick() {
        if (followEntity != null) {
            if (followEntity.isRemoved()) {
                stop();
                return;
            }
            if (followEntity instanceof Player player) {
                final Vec3 look = player.getLookAngle();
                this.x = player.getX() + look.x;
                this.y = player.getEyeY() + look.y;
                this.z = player.getZ() + look.z;
            } else {
                this.x = followEntity.getX();
                this.y = followEntity.getY();
                this.z = followEntity.getZ();
            }
        }
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager handler) {
        WeighedSoundEvents result = super.resolve(handler);
        if (this.sound != null && this.sound != SoundManager.EMPTY_SOUND) {
            int range = Config.PLAYBACK_RANGE.get();
            this.sound = new Sound(
                    this.sound.getLocation(),
                    this.sound.getVolume(),
                    this.sound.getPitch(),
                    this.sound.getWeight(),
                    this.sound.getType(),
                    this.sound.shouldStream(),
                    this.sound.shouldPreload(),
                    range);
        }
        return result;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(new LavaPlayerAudioStream(source));
    }

    public void requestStop() {
        this.stop();
    }
}
