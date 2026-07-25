package com.kuronami.musicdiscmaker.client.audio;

/**
 * 音量・可聴範囲そのものを毎 tick 供給する任意能力。{@link LiveConfigAnchor} が「再読元の
 * {@link net.minecraft.core.BlockPos}」を返して {@link DiscSoundInstance} 側に BE を引かせるのに対し、
 * こちらは値の解決をアンカーに任せる。
 *
 * <p>複数の候補点 (音源本体と各スピーカー) から listener に最も近い 1 点を選ぶ
 * {@link MultiSpeakerAnchor} のように、再読元が 1 箇所に定まらないアンカーのための能力。
 *
 * <p>いずれのメソッドも「今は分からない」を負値で表す。呼び出し側はその tick の更新を見送り、
 * 現在値 (payload の初期値、または前回解決した値) を保持する。
 */
public interface LiveAudioConfigAnchor {

    /** 現 tick の音量 (%)。負値 = 不明 (現在値を保持)。 */
    int liveVolumePercent();

    /** 現 tick の可聴範囲 (ブロック)。負値 = 不明 (現在値を保持)。 */
    int liveRangeBlocks();
}
