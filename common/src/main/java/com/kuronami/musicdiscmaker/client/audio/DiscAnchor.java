package com.kuronami.musicdiscmaker.client.audio;

import net.minecraft.world.phys.Vec3;

/**
 * custom disc 音源の「毎 tick の world 座標と存在」を供給する抽象。{@link DiscSoundInstance} は
 * 固定 BlockPos を直接見るのをやめ、この anchor に委譲する。これにより固定ジュークボックス
 * ({@link StaticAnchor})・entity 追従 ({@link EntityAnchor})・移動構造物 (VS2 船 / Create contraption 等の
 * 互換アダプタ) を同じ再生経路で扱える。
 *
 * <p>責務は「位置」と「まだ鳴らすか」だけに限定する。強化版ジュークボックスの音量/範囲 live 再読の
 * ような音源固有の挙動は anchor には持たせず、{@link DiscSoundInstance} 側で anchor の実型を見て
 * 分岐する。
 */
public interface DiscAnchor {

    /**
     * この音源をまだ再生し続けるべきか。{@code false} を返した tick で {@link DiscSoundInstance} が
     * 自己停止する。判定できない状況 (client level 未生成・chunk 未ロード等) は「停止しない」= {@code true}
     * に倒し、遠距離の正常再生を誤って切らない。
     */
    boolean isValid();

    /**
     * 現 tick の音源 world 座標。{@code partialTicks} は移動構造物の描画フレーム補間用。固定・entity 追従
     * のアンカーは補間しないため引数を無視する (従来挙動)。
     */
    Vec3 worldPos(float partialTicks);
}
