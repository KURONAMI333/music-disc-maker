package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link SoundEngineAcceptance#isUserMuted} は「利用者が直せる無音」だけを拾う。
 *
 * <p>BlackWolfLeon の報告 (2026-10-26) は、範囲外で意図的に黙っている音を {@code [MUTED]} と
 * 表示していた。空間ゲートを判定へ渡さないことをここで固定する。
 */
class SoundEngineAcceptanceMutedTest {

    @Test
    void allVolumesAudible() {
        assertFalse(SoundEngineAcceptance.isUserMuted(1.0D, 1.0D, 1.0D));
    }

    @Test
    void eachVolumeAloneSilences() {
        assertTrue(SoundEngineAcceptance.isUserMuted(0.0D, 1.0D, 1.0D));
        assertTrue(SoundEngineAcceptance.isUserMuted(1.0D, 0.0D, 1.0D));
        assertTrue(SoundEngineAcceptance.isUserMuted(1.0D, 1.0D, 0.0D));
    }

    @Test
    void negativeIsSilentButAnyPositiveValueIsAudible() {
        assertTrue(SoundEngineAcceptance.isUserMuted(-1.0D, 1.0D, 1.0D));
        assertFalse(SoundEngineAcceptance.isUserMuted(0.0001D, 1.0D, 1.0D));
    }

    /**
     * speaker/トロッコ経路は実効音量ではなく利用者設定音量で判定する。
     *
     * <p>{@link LiveAudioConfig#volumePercent()} は範囲外・アンカー遷移中に 0 になる。
     * 設定音量が 100 のままなら {@code [MUTED]} にしない。
     */
    @Test
    void speakerPathUsesConfiguredVolumeNotEffectiveVolume() {
        final LiveAudioConfig outOfRange = new FakeConfig(0, 100);
        assertFalse(SoundEngineAcceptance.isUserMuted(
                SoundEngineAcceptance.configuredVolumePercent(outOfRange), 1.0D, 1.0D));

        final LiveAudioConfig userMuted = new FakeConfig(0, 0);
        assertTrue(SoundEngineAcceptance.isUserMuted(
                SoundEngineAcceptance.configuredVolumePercent(userMuted), 1.0D, 1.0D));
    }

    @Test
    void speakerPathWithoutLiveConfigDefaultsToFullVolume() {
        assertEquals(100, SoundEngineAcceptance.configuredVolumePercent(null));
        assertFalse(SoundEngineAcceptance.isUserMuted(
                SoundEngineAcceptance.configuredVolumePercent(null), 1.0D, 1.0D));
    }

    private record FakeConfig(int volumePercent, int configuredVolumePercent) implements LiveAudioConfig {

        @Override
        public int rangeBlocks() {
            return 16;
        }

        @Override
        public boolean directional() {
            return true;
        }
    }
}
