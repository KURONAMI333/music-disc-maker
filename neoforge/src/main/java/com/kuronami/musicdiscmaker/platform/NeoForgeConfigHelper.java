package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.platform.services.IConfigHelper;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge 実装: client 設定 ({@code ModConfigSpec})。entry が {@link #SPEC} を CLIENT config として登録する。
 */
public class NeoForgeConfigHelper implements IConfigHelper {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue VOLUME_MULTIPLIER = BUILDER
            .comment("Playback volume multiplier for custom discs (relative to the Records sound slider).")
            .defineInRange("volumeMultiplier", 0.5, 0.0, 1.0);

    public static final ModConfigSpec.IntValue MAX_CONCURRENT = BUILDER
            .comment("Maximum number of custom discs playing simultaneously (memory protection).")
            .defineInRange("maxConcurrent", 16, 1, 64);

    public static final ModConfigSpec.IntValue PLAYBACK_RANGE = BUILDER
            .comment("Distance in blocks at which custom disc audio fades to silence (vanilla discs use 16).")
            .defineInRange("playbackRange", 64, 16, 256);

    public static final ModConfigSpec.IntValue MAX_PLAYBACK_RANGE = BUILDER
            .comment("Upper cap for the Golden Jukebox per-block range setting (blocks). "
                    + "Effective range = min(block setting, this). Separate from playbackRange.")
            .defineInRange("maxPlaybackRange", 256, 16, 256);

    public static final ModConfigSpec SPEC = BUILDER.build();

    @Override
    public double volumeMultiplier() {
        return VOLUME_MULTIPLIER.get();
    }

    @Override
    public int maxConcurrent() {
        return MAX_CONCURRENT.get();
    }

    @Override
    public int playbackRange() {
        return PLAYBACK_RANGE.get();
    }

    @Override
    public int maxPlaybackRange() {
        return MAX_PLAYBACK_RANGE.get();
    }
}
