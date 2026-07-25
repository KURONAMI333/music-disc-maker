package com.kuronami.musicdiscmaker;

import com.kuronami.musicdiscmaker.platform.Services;

/**
 * 設定への loader 非依存アクセサ。実体は {@link Services#CONFIG} (NeoForge=ModConfigSpec /
 * Fabric=既定値)。
 *
 * <p>再生系 (volumeMultiplier / maxConcurrent / playbackRange / maxPlaybackRange) は client 設定、
 * スピーカーのリンク制約 (speakerLinkRange / maxSpeakersPerSource) は server 設定。NeoForge では
 * 別々の spec に分けてあるので、dedicated server で client spec を引いて落ちることはない。
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

    public static int maxPlaybackRange() {
        return Services.CONFIG.maxPlaybackRange();
    }

    /** スピーカーのリンク上限距離 (ブロック)。server 側で読む。 */
    public static int speakerLinkRange() {
        return Services.CONFIG.speakerLinkRange();
    }

    /** 1 音源あたりのスピーカー上限台数。server 側で読む。 */
    public static int maxSpeakersPerSource() {
        return Services.CONFIG.maxSpeakersPerSource();
    }
}
