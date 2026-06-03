package com.kuronami.musicdiscmaker;

import net.neoforged.neoforge.common.ModConfigSpec;

/** client 設定 (再生は client 側で行うため CLIENT type)。 */
public final class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue VOLUME_MULTIPLIER = BUILDER
            .comment("Playback volume multiplier for custom discs (relative to the Records sound slider).")
            .defineInRange("volumeMultiplier", 0.5, 0.0, 1.0);

    public static final ModConfigSpec.IntValue MAX_CONCURRENT = BUILDER
            .comment("Maximum number of custom discs playing simultaneously (memory protection).")
            .defineInRange("maxConcurrent", 16, 1, 64);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
