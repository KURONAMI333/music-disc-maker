package com.kuronami.musicdiscmaker.client.audio;

import net.minecraft.client.sounds.SoundEngine;

/**
 * loader mixin ({@code MixinSoundManager}) が {@code SoundManager} に実装する duck interface。
 * SoundManager の private {@code soundEngine} を common へ公開する。SoundEngine 自体は vanilla の
 * public 型なので、これで {@link SoundEngineChannelAccess} へ到達できる。
 */
public interface SoundEngineHolder {

    /** この SoundManager が保持する SoundEngine。 */
    SoundEngine mdm$soundEngine();
}
