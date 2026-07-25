package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

public final class ModSounds {

    public static final RegistrationProvider<SoundEvent> SOUNDS =
            RegistrationProvider.get(Registries.SOUND_EVENT, MusicDiscMaker.MODID);

    /**
     * custom disc 再生用の streaming sound。実際の音声は {@code DiscSoundInstance#getStream} が
     * LavaPlayer から供給するので、紐づく OGG は (検証通過用の) 無音で良い。
     */
    public static final RegistryHolder<SoundEvent> CUSTOM_DISC_PLAYBACK =
            SOUNDS.register("custom_disc_playback",
                    () -> SoundEvent.createVariableRangeEvent(
                            Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "custom_disc_playback")));

    /**
     * jukebox_song が参照する無音 SoundEvent。custom disc に JUKEBOX_PLAYABLE component を付け
     * バニラの「再生中」状態に乗せるために使う (実際の音声は LavaPlayer ストリーム)。
     */
    public static final RegistryHolder<SoundEvent> SILENCE =
            SOUNDS.register("silence",
                    () -> SoundEvent.createVariableRangeEvent(
                            Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "silence")));

    private ModSounds() {
    }

    public static void init() {
    }
}
