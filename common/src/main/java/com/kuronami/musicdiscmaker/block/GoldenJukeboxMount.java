package com.kuronami.musicdiscmaker.block;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.VanillaTrackData;
import com.kuronami.musicdiscmaker.network.PlaybackSourceStamp;

import net.minecraft.world.entity.player.Player;

/**
 * 金のジュークボックスを載せて動かすもの (金のジュークボックス付きトロッコ)。
 *
 * <p>載せた {@link GoldenJukeboxBlockEntity} は設置した金のジュークボックスと同じ処理で曲を選び、
 * 送り、止め、保存する。違うのは「どこで鳴るか」と「誰に知らせるか」だけで、それをここが受け持つ。
 * 載せた BE は世界の座標に置かれていないので、座標に結び付いた処理 (赤石入力・コンパレータ・
 * blockstate・Speaker のリンク・座標宛ての再生 payload) は使わない。
 */
public interface GoldenJukeboxMount {

    /**
     * 曲を鳴らす (再生開始・頭出し・曲送り)。{@code track} と {@code vanilla} はどちらか一方が非 null。
     *
     * @param offsetMs 曲の頭からの位置
     */
    void play(@Nullable CustomTrackData track, @Nullable VanillaTrackData vanilla, long offsetMs,
            int rangeBlocks, int volumePercent, boolean directional, PlaybackSourceStamp identity);

    /** 鳴っている音を止める。{@code naturalEnd} は曲を鳴らし切ったことによる停止。 */
    void stop(PlaybackSourceStamp identity, boolean naturalEnd);

    /** 状態 (設定・再生位置・盤) を client の画面へ反映する。 */
    void sync();

    /** 保存が必要になった。 */
    void markDirty();

    /**
     * この player が GUI を開いたまま操作できる距離にいるか。
     *
     * <p><b>{@code stillValid} という名前にしない。</b> 実装側の {@code GoldenJukeboxMinecartEntity} は
     * {@code Container#stillValid(Player)} も override していて、Forge 1.20.1 の再難読化では
     * その override が SRG 名 {@code m_6542_} に置き換わる。インターフェース側の名前が
     * {@code stillValid} のままだと、実装クラスから {@code stillValid} が消えて
     * 「GoldenJukeboxMinecartEntity does not define the method stillValid(Player)」の
     * AbstractMethodError になる（3.1.3 の 1.20.1 で実報告あり。2026-11-06）。
     */
    boolean stillValidFor(Player player);
}
