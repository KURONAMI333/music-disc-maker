package com.kuronami.musicdiscmaker.client.audio;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;

import net.minecraft.core.BlockPos;

/**
 * 強化版ジュークボックスの音量/範囲を {@link DiscSoundInstance#tick()} が毎 tick ライブ再読するための
 * 任意能力。この能力を実装するアンカーは、client world 上で音源の {@link GoldenJukeboxBlockEntity} が
 * 実在する {@link BlockPos} を供給する。{@code tick} はその BE から volume/range を再読し、スライダー
 * 操作を再ロードなしで即反映する。
 *
 * <p>この能力を実装するのは、原ブロックが client world に実在し続けるアンカーだけ:
 * {@link StaticAnchor} (固定ジューク) と VS2 変換式の {@code VSShipAnchor} (shipyard 座標に実在)。
 * Create 捕獲式 ({@code ContraptionAnchor}) は組立時にブロックが AIR 化され再読元が無いため実装せず、
 * payload の初期値のまま再生する (スコープ外)。{@link EntityAnchor} も per-block 設定を持たない。
 *
 * <p>能力を独立インターフェースにする理由: {@link DiscAnchor} 本体は「位置」と「存在」だけに責務を
 * 限定しており、音源固有の live 再読を持たせない。また VS2 型を参照する {@code VSShipAnchor} を
 * {@code DiscSoundInstance} が concrete instanceof で見ると、VS2 非導入でも class-load が走り
 * verification 時に VS2 クラス解決を招く恐れがある。common のこの能力インターフェース越しに分岐すれば
 * soft-dep を保ったまま安全に拡張できる。
 */
public interface LiveConfigAnchor {

    /**
     * client world 上で音源の {@link GoldenJukeboxBlockEntity} が実在する位置。{@code tick} はこの位置が
     * ロード済みで強化版ジュークボックスの時だけ volume/range を再読する。
     */
    BlockPos configPos();
}
