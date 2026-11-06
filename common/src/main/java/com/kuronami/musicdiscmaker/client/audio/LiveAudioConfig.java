package com.kuronami.musicdiscmaker.client.audio;

/**
 * payload でライブ更新される聴取設定を供給する任意能力。
 *
 * <p>固定speaker集合は、同じデコーダを保ったまま選択中の聴取点だけを変える。そのため
 * {@link DiscSoundInstance} はこの能力を持つanchorから、範囲・音量・聴取モデルを毎 tick 読む。
 */
public interface LiveAudioConfig {

    /** 現在の可聴範囲（block）。 */
    int rangeBlocks();

    /** 現在の選択点を含めた実効音量（%）。 */
    int volumePercent();

    /**
     * 利用者が設定した音量（%）。選択点・空間ゲート・遷移補正を含めない。
     *
     * <p>{@link #volumePercent()} は範囲外やアンカー未選択の間 0 を返しうる。それを
     * 「利用者が無音にした」と誤認しないため、{@code mutedOut()} はこちらを見る。
     */
    default int configuredVolumePercent() {
        return volumePercent();
    }

    /** true = positional、false = 既存のフラット聴取。 */
    boolean directional();

    /**
     * 選択点の切替を滑らかにする追加gain。通常のanchorは1、固定speakerの遷移中だけ0..1を返す。
     * 音量設定そのものと分けることで、元のGolden/speaker音量を変更せず同じdecoderへ適用できる。
     */
    default double gainMultiplier() {
        return 1.0D;
    }
}
