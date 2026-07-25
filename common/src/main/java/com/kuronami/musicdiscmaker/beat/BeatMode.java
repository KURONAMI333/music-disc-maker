package com.kuronami.musicdiscmaker.beat;

/**
 * コンパレータ出力の作り方。config ({@code beatMode}) で切り替える。
 */
public enum BeatMode {

    /** 帯域の音量エンベロープをそのまま 0..15 に写す。曲の盛り上がりに連れて強度が動く = 既定。 */
    ENVELOPE,
    /** 打点 (スペクトラルフラックスの立ち上がり) を検出して 15 のパルスを撃つ。 */
    ONSET
}
