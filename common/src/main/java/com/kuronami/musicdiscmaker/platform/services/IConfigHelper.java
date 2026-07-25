package com.kuronami.musicdiscmaker.platform.services;

import com.kuronami.musicdiscmaker.beat.BeatBand;
import com.kuronami.musicdiscmaker.beat.BeatMode;

/**
 * 設定の loader 抽象。NeoForge は {@code ModConfigSpec} を読み、Fabric は既定値を返す。
 *
 * <p>再生系 (volumeMultiplier / maxConcurrent / playbackRange / maxPlaybackRange) は client 設定。
 * スピーカーのリンク制約とビート連動 ({@code beat*}) は <b>server 設定</b> — 解析もコンパレータ出力も
 * server で起きるので、CLIENT spec に置くと dedicated server で読んだ瞬間に落ちる。
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

    // ── ビート連動レッドストーン (すべて server 側で読む) ────────────────
    // 強化版ジュークボックスのコンパレータ出力は「再生中のビート強度 0..15 / 停止中 0」で固定。
    // 見た目の調整はここに全部出す (プレイ中に触る想定 = NeoForge は config GUI から変えられる)。

    /** ビート連動そのものの ON/OFF。false = コンパレータは常に 0。既定 true。 */
    boolean beatEnabled();

    /** 出力の作り方 (エンベロープ / オンセット)。既定 {@link BeatMode#ENVELOPE}。 */
    BeatMode beatMode();

    /** 強度を取る周波数帯。既定 {@link BeatBand#LOW} (キック)。 */
    BeatBand beatBand();

    /** エンベロープの感度 (ゲイン)。大きいほど小さい音でも強く出る。既定 1.0。 */
    double beatSensitivity();

    /** 基準レベルから何 dB 下を 0 とみなすか。既定 -36。 */
    int beatFloorDb();

    /** 立ち上がりの時定数 (ms)。0 = 即応。既定 0。 */
    int beatAttackMs();

    /** 落ちの時定数 (ms)。Create の慣性に合わせる値。既定 180。 */
    int beatReleaseMs();

    /**
     * 出力オフセット (ms)。正 = 遅らせる / <b>負 = 音より早く出す</b>。
     * 負にできるのがサーバ権威ビートマップの強みで、Create 側の機械遅延を先撃ちで相殺できる。既定 0。
     */
    int beatOffsetMs();

    /** オンセット判定の閾値 (0..1)。既定 0.5。 */
    double beatOnsetThreshold();

    /** オンセット 1 打点あたりのパルス長 (tick)。既定 2。 */
    int beatOnsetPulseTicks();

    /** この幅以内の揺れは出力を据え置く (レッドストーン更新の間引き)。既定 1。 */
    int beatHysteresis();

    /** コンパレータ更新の最小間隔 (tick)。重い回路で上げる。既定 1。 */
    int beatMinUpdateTicks();

    /**
     * client から実鳴動時刻の報告が来なかった時に使う一律オフセット (ms)。
     * 無人サーバ・client 側 mod 無しの経路で効く。既定 0。
     */
    int beatUncalibratedOffsetMs();

    /** 解析の FFT 長。大きいほど低音の分離が良く、負荷が上がる。既定 2048。 */
    int beatFftSize();

    /** ビートマップキャッシュの上限 (MB)。0 = 無制限。既定 64。 */
    int beatCacheMaxMB();

    /** 同時に走らせる解析の本数。既定 2。 */
    int beatMaxConcurrentAnalyses();
}
