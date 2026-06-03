package com.kuronami.musicdiscmaker.register;

import java.util.function.Supplier;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, MusicDiscMaker.MODID);

    /**
     * custom disc 再生用の streaming sound。実際の音声は {@code DiscSoundInstance#getStream} が
     * LavaPlayer から供給するので、紐づく OGG は (検証通過用の) 無音で良い。
     */
    public static final Supplier<SoundEvent> CUSTOM_DISC_PLAYBACK = SOUNDS.register("custom_disc_playback",
            () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "custom_disc_playback")));

    private ModSounds() {
    }

    public static void register(IEventBus modEventBus) {
        SOUNDS.register(modEventBus);
    }
}
