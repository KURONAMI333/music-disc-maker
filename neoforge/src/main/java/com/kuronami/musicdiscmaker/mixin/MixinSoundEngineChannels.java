package com.kuronami.musicdiscmaker.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.kuronami.musicdiscmaker.client.audio.SoundEngineChannelAccess;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;

/**
 * SoundEngine の private {@code instanceToChannel} を経由して、再生中チャンネルの線形減衰半径と
 * 相対座標フラグを ライブ更新する ({@link SoundEngineChannelAccess})。強化版ジュークボックスの範囲
 * スライダーと指向性トグルを、音量と同様に再ストリームなしで即反映するために使う。
 *
 * <p>{@code @Shadow} フィールド読み + duck メソッド追加のみ = {@code play}/{@code tickNonPaused} の
 * bytecode には触れない。旧 {@code MixinSoundEngine} の {@code @Redirect} が 26.2 で streaming を
 * 壊した型とは別系統。
 */
@Mixin(SoundEngine.class)
public abstract class MixinSoundEngineChannels implements SoundEngineChannelAccess {

    @Shadow
    private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;

    @Override
    public void mdm$updateLinearAttenuation(SoundInstance instance, float linearAttenuation) {
        final ChannelAccess.ChannelHandle handle = this.instanceToChannel.get(instance);
        if (handle != null) {
            handle.execute(channel -> channel.linearAttenuation(linearAttenuation));
        }
    }

    @Override
    public void mdm$setRelative(SoundInstance instance, boolean relative) {
        final ChannelAccess.ChannelHandle handle = this.instanceToChannel.get(instance);
        if (handle != null) {
            handle.execute(channel -> channel.setRelative(relative));
        }
    }
}
