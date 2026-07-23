package com.kuronami.musicdiscmaker.client.audio;

import net.minecraft.client.resources.sounds.SoundInstance;

/**
 * loader mixin ({@code MixinSoundEngineChannels}) が {@code SoundEngine} に実装する duck interface。
 * SoundEngine の private {@code instanceToChannel} を経由して、再生中のチャンネルの線形減衰半径を
 * ライブ更新する。common は SoundEngine の private フィールドに触れないため、実体は loader 層に置く。
 *
 * <p>vanilla の {@code SoundEngine#play}/{@code tickNonPaused} の bytecode には一切介入しない (mixin が
 * 追加する新メソッド + {@code @Shadow} フィールド読みのみ)。よって @Redirect が 26.2 で streaming を
 * 壊した型の破損は原理的に起きない。
 */
public interface SoundEngineChannelAccess {

    /**
     * 指定 SoundInstance に現在割り当てられているチャンネルの線形減衰半径 (max distance) を更新する。
     * まだチャンネルが割り当てられていなければ (play 直後の 1 tick 窓) 何もしない。
     *
     * @param instance          対象の再生インスタンス
     * @param linearAttenuation 新しい線形減衰半径。{@code SoundEngine#play} と同じ式
     *                          ({@code max(getVolume(),1) * attenuationDistance}) で算出した値を渡す。
     */
    void mdm$updateLinearAttenuation(SoundInstance instance, float linearAttenuation);
}
