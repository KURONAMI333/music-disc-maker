package com.kuronami.musicdiscmaker;

import com.kuronami.musicdiscmaker.platform.Services;

/**
 * client 設定への loader 非依存アクセサ。実体は {@link Services#CONFIG} (NeoForge=ModConfigSpec /
 * Fabric=既定値)。再生は client 側で行うため参照も client 経路から。
 */
public final class Config {

    private Config() {
    }

    public static double volumeMultiplier() {
        return Services.CONFIG.volumeMultiplier();
    }

    public static int maxConcurrent() {
        return Services.CONFIG.maxConcurrent();
    }

    public static int playbackRange() {
        return Services.CONFIG.playbackRange();
    }
}
