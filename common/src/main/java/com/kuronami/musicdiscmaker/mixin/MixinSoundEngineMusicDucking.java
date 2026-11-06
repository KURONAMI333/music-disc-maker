package com.kuronami.musicdiscmaker.mixin;

import java.util.Map;
import java.util.Set;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.client.audio.MusicDucking;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Listener;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * 盤の曲の間 BGM を止めるための {@link MusicDucking.Access}。再生中の音の一覧 ({@code instanceToChannel}) と
 * listener の位置を読み、「音楽」分類のチャンネルを一時停止 / 再開する。ゲームのポーズ解除で戻された BGM は止め直す。
 */
@Mixin(SoundEngine.class)
public abstract class MixinSoundEngineMusicDucking implements MusicDucking.Access {

    @Shadow
    @Final
    private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;

    @Shadow
    @Final
    private Listener listener;

    @Inject(method = "resume", at = @At("TAIL"))
    private void mdm$keepMusicHeld(CallbackInfo ci) {
        MusicDucking.afterEngineResume(this);
    }

    @Override
    public boolean mdm$isDiscAudible() {
        //? if >=1.21 {
        final Vec3 ear = this.listener.getTransform().position();
        //?} else {
        /*final Vec3 ear = this.listener.getListenerPosition();
        *///?}
        for (final SoundInstance instance : this.instanceToChannel.keySet()) {
            if (MusicDucking.isAudibleDisc(instance, ear)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void mdm$pauseMusic(Set<SoundInstance> into) {
        this.instanceToChannel.forEach((instance, handle) -> {
            if (instance.getSource() == SoundSource.MUSIC) {
                handle.execute(Channel::pause);
                into.add(instance);
            }
        });
    }

    @Override
    public void mdm$unpause(Set<SoundInstance> instances) {
        for (final SoundInstance instance : instances) {
            final ChannelAccess.ChannelHandle handle = this.instanceToChannel.get(instance);
            if (handle != null) {
                handle.execute(Channel::unpause);
            }
        }
    }
}
