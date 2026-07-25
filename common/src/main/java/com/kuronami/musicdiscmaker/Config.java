package com.kuronami.musicdiscmaker;

import com.kuronami.musicdiscmaker.beat.BeatBand;
import com.kuronami.musicdiscmaker.beat.BeatMode;
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

    // ── ビート連動レッドストーン (server 側) ────────────────────────────

    public static boolean beatEnabled() {
        return Services.CONFIG.beatEnabled();
    }

    /** true = オンセット (打点パルス) / false = エンベロープ。 */
    public static boolean beatOnsetMode() {
        return Services.CONFIG.beatMode() == BeatMode.ONSET;
    }

    public static BeatBand beatBand() {
        return Services.CONFIG.beatBand();
    }

    public static double beatSensitivity() {
        return Services.CONFIG.beatSensitivity();
    }

    public static int beatFloorDb() {
        return Services.CONFIG.beatFloorDb();
    }

    public static int beatAttackMs() {
        return Services.CONFIG.beatAttackMs();
    }

    public static int beatReleaseMs() {
        return Services.CONFIG.beatReleaseMs();
    }

    public static int beatOffsetMs() {
        return Services.CONFIG.beatOffsetMs();
    }

    public static double beatOnsetThreshold() {
        return Services.CONFIG.beatOnsetThreshold();
    }

    public static int beatOnsetPulseTicks() {
        return Services.CONFIG.beatOnsetPulseTicks();
    }

    public static int beatHysteresis() {
        return Services.CONFIG.beatHysteresis();
    }

    public static int beatMinUpdateTicks() {
        return Services.CONFIG.beatMinUpdateTicks();
    }

    public static int beatUncalibratedOffsetMs() {
        return Services.CONFIG.beatUncalibratedOffsetMs();
    }

    public static int beatFftSize() {
        return Services.CONFIG.beatFftSize();
    }

    public static int beatCacheMaxMB() {
        return Services.CONFIG.beatCacheMaxMB();
    }

    public static int beatMaxConcurrentAnalyses() {
        return Services.CONFIG.beatMaxConcurrentAnalyses();
    }

    // ── 音源ローカルキャッシュ (client 側) ────────────────────────────────

    public static boolean audioCacheEnabled() {
        return Services.CONFIG.audioCacheEnabled();
    }

    public static int audioCacheMaxMB() {
        return Services.CONFIG.audioCacheMaxMB();
    }
}
