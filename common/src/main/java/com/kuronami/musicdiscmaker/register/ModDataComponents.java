package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;

/** custom music disc に載せる DataComponent。 */
public final class ModDataComponents {

    public static final RegistrationProvider<DataComponentType<?>> COMPONENTS =
            RegistrationProvider.get(Registries.DATA_COMPONENT_TYPE, MusicDiscMaker.MODID);

    public static final RegistryHolder<DataComponentType<CustomTrackData>> CUSTOM_TRACK =
            COMPONENTS.register("custom_track",
                    () -> DataComponentType.<CustomTrackData>builder()
                            .persistent(CustomTrackData.CODEC)
                            .networkSynchronized(CustomTrackData.STREAM_CODEC)
                            .build());

    private ModDataComponents() {
    }

    public static void init() {
    }
}
