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

    /** 通常 jukebox の custom disc 可聴距離 (ブロック単位)。既定 64。 */
    int playbackRange();

    /**
     * 強化版ジュークボックスの per-block 範囲設定の上限 (ブロック単位)。既定 256。
     * 実効範囲 = min(ブロック設定, この値) なので、{@link #playbackRange()} と分離することで
     * Fabric の固定 64 に頭打ちされず 256 まで届く。通常 jukebox の挙動には影響しない。
     */
    int maxPlaybackRange();

    /**
     * スピーカーを音源 (強化版ジュークボックス) にリンクできる最大距離 (ブロック)。既定 128。
     * <b>server 側で読む値</b> (設置時の検証)。NeoForge は SERVER config、Fabric は既定値。
     */
    int speakerLinkRange();

    /**
     * 1 つの音源にぶら下げられるスピーカーの最大台数。既定 16。
     * <b>server 側で読む値</b> (リンク時の検証と payload の歯止め)。
     */
    int maxSpeakersPerSource();
}
