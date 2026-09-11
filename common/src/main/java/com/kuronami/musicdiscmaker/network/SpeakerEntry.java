package com.kuronami.musicdiscmaker.network;

import net.minecraft.core.BlockPos;

/** 読み込まれている固定スピーカーの聴取設定。向きは床・壁・天井×水平4方向×ホーン3段階。 */
public record SpeakerEntry(BlockPos pos, int volumePercent, boolean muted, int orientation) {
    public SpeakerEntry {
        pos = pos.immutable();
        volumePercent = Math.max(0, Math.min(200, volumePercent));
        if (orientation < 0 || orientation >= 36) {
            throw new IllegalArgumentException("Invalid speaker orientation: " + orientation);
        }
    }
}
