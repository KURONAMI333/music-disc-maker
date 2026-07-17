package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.platform.services.IConfigHelper;

/**
 * Fabric 実装: client 設定。Fabric には v1 で config 画面を設けないため既定値を返す。
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
}
