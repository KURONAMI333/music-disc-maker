package com.kuronami.musicdiscmaker.client.audio;

import com.kuronami.musicdiscmaker.Config;

import net.minecraft.world.phys.Vec3;

/**
 * ブームボックスで鳴らすバニラ・他 MOD の盤の音の付き先。
 *
 * <p>位置と生存 (送り直しが途絶えたら止まる) は {@link BoomboxAnchor} のまま、音量・範囲・指向性を
 * {@link VanillaSpeakerSoundInstance} が読める形にする。範囲は MDM の盤と同じ client 設定
 * ({@link Config#boomboxRange()})、指向性は持たない (携帯は範囲の中ならどこでも同じ音量)。
 */
final class BoomboxVanillaAnchor implements DiscAnchor, LiveAudioConfig {

    private final BoomboxAnchor anchor;
    private volatile int volumePercent;

    BoomboxVanillaAnchor(BoomboxAnchor anchor, int volumePercent) {
        this.anchor = anchor;
        this.volumePercent = volumePercent;
    }

    void setVolumePercent(int value) {
        this.volumePercent = value;
    }

    @Override
    public boolean isValid() {
        return anchor.isValid();
    }

    @Override
    public Vec3 worldPos(float partialTicks) {
        return anchor.worldPos(partialTicks);
    }

    @Override
    public int rangeBlocks() {
        return Config.boomboxRange();
    }

    @Override
    public int volumePercent() {
        return volumePercent;
    }

    @Override
    public boolean directional() {
        return false;
    }
}
