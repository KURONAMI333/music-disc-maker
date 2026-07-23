package com.kuronami.musicdiscmaker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.kuronami.musicdiscmaker.client.audio.SoundEngineHolder;

import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;

/**
 * SoundManager の private {@code soundEngine} を common へ公開する ({@link SoundEngineHolder})。
 * pure な {@code @Shadow} フィールド読み + duck メソッド追加のみ = 既存メソッドの bytecode 変換なし。
 */
@Mixin(SoundManager.class)
public abstract class MixinSoundManager implements SoundEngineHolder {

    @Shadow
    private SoundEngine soundEngine;

    @Override
    public SoundEngine mdm$soundEngine() {
        return this.soundEngine;
    }
}
