package com.kuronami.musicdiscmaker.platform.services;

/**
 * client 設定の loader 抽象。NeoForge は {@code ModConfigSpec} を読み、Fabric は
 * 既定値 (or 簡易 config) を返す。再生は client 側のみなので参照も client 経路から。
 */
public interface IConfigHelper {

    /** custom disc 再生音量の倍率 (Records スライダー相対)。既定 0.5。 */
    double volumeMultiplier();

    /** 同時再生する custom disc の最大数 (メモリ保護)。既定 16。 */
    int maxConcurrent();

    /** custom disc の可聴距離 (ブロック単位)。既定 64。 */
    int playbackRange();
}
