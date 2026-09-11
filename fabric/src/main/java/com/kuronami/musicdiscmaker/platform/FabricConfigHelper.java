package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.platform.services.IConfigHelper;

/**
 * Fabric 実装: client 設定。Fabric には config 画面を設けないため既定値を返す
 * (NeoForge の ModConfigSpec 既定値と一致させる)。再生は client 側のみなので参照も client 経路から。
 */
public class FabricConfigHelper implements IConfigHelper {

    @Override
    public double volumeMultiplier() {
        return 0.5;
    }

    @Override
    public int maxConcurrent() {
        // config 画面が無いので常時 auto。実 pool から BGM 等の余白を引き、最大 64 にする。
        return com.kuronami.musicdiscmaker.Config.automaticMaxConcurrent();
    }

    @Override
    public int playbackRange() {
        return 64;
    }

    @Override
    public int boomboxRange() {
        // Fabric は config 画面が無いので既定 16 固定 (playbackRange の 64 固定と同じ扱い)。
        return com.kuronami.musicdiscmaker.component.BoomboxContents.RANGE_DEFAULT;
    }

    @Override
    public int maxPlaybackRange() {
        // Fabric は config 画面が無いので既定 256。強化版ジュークボックスはこの上限まで到達できる。
        return 256;
    }
}
