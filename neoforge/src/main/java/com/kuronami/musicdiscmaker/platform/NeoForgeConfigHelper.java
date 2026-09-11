package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.platform.services.IConfigHelper;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge 実装: client 設定 ({@code ModConfigSpec})。entry が {@link #SPEC} を CLIENT config として登録する。
 * 共通の {@code com.kuronami.musicdiscmaker.Config} と FQN が衝突しないよう、spec はここに保持する。
 */
public class NeoForgeConfigHelper implements IConfigHelper {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue VOLUME_MULTIPLIER = BUILDER
            .comment("Playback volume multiplier for custom discs (relative to the Records sound slider).")
            .defineInRange("volumeMultiplier", 0.5, 0.0, 1.0);

    public static final ModConfigSpec.IntValue MAX_CONCURRENT = BUILDER
            .comment("Maximum number of custom discs playing simultaneously (memory protection).",
                    "0 = automatic (normally 64; reduced on devices with fewer audio sources). Recommended.",
                    "Automatic mode keeps 8 streaming channels free for music, vanilla records, and ambience.")
            .defineInRange("maxConcurrentDiscs", 0, 0, 64);

    public static final ModConfigSpec.IntValue PLAYBACK_RANGE = BUILDER
            .comment("Distance in blocks at which custom disc audio fades to silence (vanilla discs use 16).")
            .defineInRange("playbackRange", 64, 16, 256);

    public static final ModConfigSpec.IntValue MAX_PLAYBACK_RANGE = BUILDER
            //? if >=1.21.2 {
            .comment("Upper cap for the Golden Jukebox per-block range setting (blocks). "
            //?} else {
            /*.comment("Upper cap for the Enhanced Jukebox per-block range setting (blocks). "
            *///?}
                    + "Effective range = min(block setting, this). Separate from playbackRange.")
            .defineInRange("maxPlaybackRange", 256, 16, 256);

    public static final ModConfigSpec.IntValue BOOMBOX_RANGE = BUILDER
            .comment("Distance in blocks at which a Boombox fades to silence.",
                    "The Boombox is meant to be heard around you, not across a base - "
                    + "range and directional audio belong to the Golden Jukebox.")
            .defineInRange("boomboxRange", 16, 4, 64);

    public static final ModConfigSpec SPEC = BUILDER.build();

    @Override
    public double volumeMultiplier() {
        return VOLUME_MULTIPLIER.get();
    }

    @Override
    public int maxConcurrent() {
        // 0 = auto: 実 streaming pool から BGM 等の余白を引き、最大 64 にする。
        // 既定値は spec build 時点では engine 実枠が確定していないので、auto を 0 で表す。
        final int v = MAX_CONCURRENT.get();
        return com.kuronami.musicdiscmaker.Config.effectiveMaxConcurrent(v);
    }

    @Override
    public int playbackRange() {
        return PLAYBACK_RANGE.get();
    }

    @Override
    public int boomboxRange() {
        return BOOMBOX_RANGE.get();
    }

    @Override
    public int maxPlaybackRange() {
        return MAX_PLAYBACK_RANGE.get();
    }
}
