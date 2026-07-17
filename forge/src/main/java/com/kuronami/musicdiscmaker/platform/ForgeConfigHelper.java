package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.platform.services.IConfigHelper;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Forge 実装: client 設定 ({@code ForgeConfigSpec})。entry が {@link #SPEC} を CLIENT config として登録する。
 * 共通の {@code com.kuronami.musicdiscmaker.Config} と FQN が衝突しないよう、spec はここに保持する。
 */
public class ForgeConfigHelper implements IConfigHelper {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.DoubleValue VOLUME_MULTIPLIER = BUILDER
            .comment("Playback volume multiplier for custom discs (relative to the Records sound slider).")
            .defineInRange("volumeMultiplier", 0.5, 0.0, 1.0);

    public static final ForgeConfigSpec.IntValue MAX_CONCURRENT = BUILDER
            .comment("Maximum number of custom discs playing simultaneously (memory protection).")
            .defineInRange("maxConcurrent", 16, 1, 64);

    public static final ForgeConfigSpec.IntValue PLAYBACK_RANGE = BUILDER
            .comment("Distance in blocks at which custom disc audio fades to silence (vanilla discs use 16).")
            .defineInRange("playbackRange", 64, 16, 256);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

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
}
