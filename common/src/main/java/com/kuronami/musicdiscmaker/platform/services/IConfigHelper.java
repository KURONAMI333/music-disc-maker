package com.kuronami.musicdiscmaker.platform.services;

/**
 * client 設定の loader 抽象。NeoForge は {@code ModConfigSpec} を読み、Fabric は
 * 既定値 (or 簡易 config) を返す。再生は client 側のみなので参照も client 経路から。
 */
public interface IConfigHelper {

    /** custom disc 再生音量の倍率 (Records スライダー相対)。既定 0.5。 */
    double volumeMultiplier();

    /**
     * 同時再生する custom disc の最大数 (メモリ保護)。
     * 既定は auto。実 streaming pool から BGM・バニラ盤・環境音の余白 8 枠を引き、
     * 64 を上限にする。source 数が少ないデバイスでは実 pool に合わせて下がる。
     */
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
     * ブームボックスの可聴距離 (ブロック単位)。既定 16。
     *
     * <p>金ジュークの {@link #playbackRange()} と別項目にしてある。
     * 「範囲は金ジューク専権」という差別化は<b>既定値の側</b>に置き、ここは
     * 既定が気に入らない人の逃げ道にする (設計上の決定 2026-09-07)。
     * 上限を金ジュークの 256 まで開けないのはそのため。
     */
    int boomboxRange();
}
