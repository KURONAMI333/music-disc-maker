package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.beat.BeatBand;
import com.kuronami.musicdiscmaker.beat.BeatMode;
import com.kuronami.musicdiscmaker.platform.services.IConfigHelper;

/**
 * Fabric 実装: 既定値を返す。Fabric 版には config 画面も config ファイルも無い (v1 からの方針)。
 *
 * <p>ビート連動の調整値もここでは固定になる。NeoForge 版は同じ項目を config GUI から変えられる。
 * Fabric へ config 機構を入れるかは既存の方針そのものの裁定なので、P4 では既存の形に揃える
 * (スピーカーの server 設定が同じ状態)。
 */
public class FabricConfigHelper implements IConfigHelper {

    @Override
    public double volumeMultiplier() {
        return 0.5;
    }

    @Override
    public int maxConcurrent() {
        return 16;
    }

    @Override
    public int playbackRange() {
        return 64;
    }

    @Override
    public int maxPlaybackRange() {
        // Fabric は config 画面が無いので既定 256。強化版ジュークボックスはこの上限まで到達できる。
        return 256;
    }

    @Override
    public int speakerLinkRange() {
        return 128;
    }

    @Override
    public int maxSpeakersPerSource() {
        return 16;
    }

    // ── ビート連動 (NeoForge 版の既定値と一致させること) ──────────────────

    @Override
    public boolean beatEnabled() {
        return true;
    }

    @Override
    public BeatMode beatMode() {
        return BeatMode.ENVELOPE;
    }

    @Override
    public BeatBand beatBand() {
        return BeatBand.LOW;
    }

    @Override
    public double beatSensitivity() {
        return 1.0;
    }

    @Override
    public int beatFloorDb() {
        return -36;
    }

    @Override
    public int beatAttackMs() {
        return 0;
    }

    @Override
    public int beatReleaseMs() {
        return 180;
    }

    @Override
    public int beatOffsetMs() {
        return 0;
    }

    @Override
    public double beatOnsetThreshold() {
        return 0.5;
    }

    @Override
    public int beatOnsetPulseTicks() {
        return 2;
    }

    @Override
    public int beatHysteresis() {
        return 1;
    }

    @Override
    public int beatMinUpdateTicks() {
        return 1;
    }

    @Override
    public int beatUncalibratedOffsetMs() {
        return 0;
    }

    @Override
    public int beatFftSize() {
        return 2048;
    }

    @Override
    public int beatCacheMaxMB() {
        return 64;
    }

    @Override
    public int beatMaxConcurrentAnalyses() {
        return 2;
    }

    // ── 音源ローカルキャッシュ ────────────────────────────────────────────
    // NeoForge 版の既定値と一致させること。既定 false = オプトインという kura 裁定どおりだが、
    // Fabric には config 機構が無いので<b>実質いつも無効</b>になる。Fabric に config を新設するかは
    // 既存方針そのものの裁定 (スピーカーの server 設定・ビートの調整値と同じ棚)。

    @Override
    public boolean audioCacheEnabled() {
        return false;
    }

    @Override
    public int audioCacheMaxMB() {
        return 1024;
    }
}
