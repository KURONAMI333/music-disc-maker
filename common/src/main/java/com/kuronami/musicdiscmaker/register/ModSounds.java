package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
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
                            new ResourceLocation(MusicDiscMaker.MODID, "custom_disc_playback")));

    /**
     * RecordItem 化した custom disc が「鳴らす」無音 SoundEvent。バニラはジュークボックスの
     * levelEvent 1010 でこれを再生する (= 実質無音)。実際の音声は LavaPlayer ストリーム。
     */
    public static final RegistryHolder<SoundEvent> SILENCE =
            SOUNDS.register("silence",
                    () -> SoundEvent.createVariableRangeEvent(
                            new ResourceLocation(MusicDiscMaker.MODID, "silence")));

    private ModSounds() {
    }

    public static void init() {
    }
}
