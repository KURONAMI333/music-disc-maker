package com.kuronami.musicdiscmaker;

import com.kuronami.musicdiscmaker.client.audio.SoundEngineChannelAccess;
import com.kuronami.musicdiscmaker.client.audio.SoundEngineHolder;
import com.kuronami.musicdiscmaker.platform.Services;

import net.minecraft.client.Minecraft;

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

    /**
     * engine が現在持つ実 streaming channel pool の上限値。
     * 取得は loader mixin の duck ({@code MixinSoundEngineChannels}) 経由。呼び出しは client main thread。
     */
    public static int engineStreamingLimit() {
        final var engine = ((SoundEngineHolder) Minecraft.getInstance().getSoundManager()).mdm$soundEngine();
        return ((SoundEngineChannelAccess) engine).mdm$streamingPoolLimit();
    }

    /** 実 streaming pool から BGM 等の余白を引いた、MDM の auto 同時再生数。 */
    public static int automaticMaxConcurrent() {
        return effectiveMaxConcurrent(0);
    }

    /** 設定値を実 streaming pool から求めた安全上限へ丸める。0 は auto。 */
    public static int effectiveMaxConcurrent(int configuredLimit) {
        return com.kuronami.musicdiscmaker.client.audio.StreamingChannelPool
                .effectiveMdmLimit(configuredLimit, engineStreamingLimit());
    }

    public static int playbackRange() {
        return Services.CONFIG.playbackRange();
    }

    public static int maxPlaybackRange() {
        return Services.CONFIG.maxPlaybackRange();
    }

    public static int boomboxRange() {
        return Services.CONFIG.boomboxRange();
    }
}
