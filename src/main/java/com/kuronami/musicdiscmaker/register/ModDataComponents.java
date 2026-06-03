package com.kuronami.musicdiscmaker.register;

import java.util.function.Supplier;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** custom music disc に載せる DataComponent。 */
public final class ModDataComponents {

    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MusicDiscMaker.MODID);

    public static final Supplier<DataComponentType<CustomTrackData>> CUSTOM_TRACK =
            COMPONENTS.registerComponentType("custom_track", builder -> builder
                    .persistent(CustomTrackData.CODEC)
                    .networkSynchronized(CustomTrackData.STREAM_CODEC));

    private ModDataComponents() {
    }

    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
    }
}
