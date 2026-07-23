package com.kuronami.musicdiscmaker.compat.aeronautics;

import com.kuronami.musicdiscmaker.client.audio.DiscAnchor;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Create Aeronautics (物理エンジン Sable) の変換式 sub-level に載った音源のアンカー。変換式は捕獲式
 * (Create 本家) と違いブロックが plot (off-map の実ブロック領域) に実在し tick し続けるため、client の
 * 音源座標だけを毎 tick 剛体 pose で world へ写す。
 *
 * <p>ブロックは sub-level の plot 内 BlockPos ({@code plotPos}) に実在する。その plot を含む
 * {@link ClientSubLevel} を {@code Sable.HELPER.getContainingClient(plot 中心)} で解決し、
 * {@code renderPose(partialTicks).transformPosition(plot 中心)} で描画フレーム補間込みの world 座標を得る。
 * これは Aeronautics 自身が sub-level 上のブロック音を配置する経路
 * ({@code BalloonBurnerSoundInstance}: {@code getContainingClient} → {@code logicalPose().transformPosition})
 * と同じ変換 API。partialTicks 補間は {@code renderPose(partialTicks)} が担う。
 *
 * <p>このクラスは Sable 型 ({@link Sable}/{@link ClientSubLevel}) を参照するため、{@link SableAudioClient}
 * が {@link SubLevelPlayDiscPayload} 受信時にのみ class-load する (= Sable 導入時のみ)。捕獲式の
 * {@code ContraptionAnchor} と同型の isolation。
 */
public final class SableSubLevelAnchor implements DiscAnchor {

    /** sub-level plot 内でのブロック中心座標 (plot ローカル空間)。 */
    private final Vec3 plotCenter;

    public SableSubLevelAnchor(BlockPos plotPos) {
        this.plotCenter = Vec3.atCenterOf(plotPos);
    }

    @Override
    public boolean isValid() {
        // client level 未生成の過渡状態は「停止しない」= true に倒す (StaticAnchor と同じ遠距離誤消音防止)。
        if (Minecraft.getInstance().level == null) {
            return true;
        }
        // 解体で plot が消える → 含む sub-level が引けなくなる → false → 自己停止し、world へ戻ったブロックの
        // 通常再生 (StaticAnchor 経路) に引き継がれる。
        return Sable.HELPER.getContainingClient(plotCenter) != null;
    }

    @Override
    public Vec3 worldPos(float partialTicks) {
        final ClientSubLevel sub = Sable.HELPER.getContainingClient(plotCenter);
        if (sub == null) {
            return plotCenter; // sub-level 未解決 (過渡): plot 座標のまま (次 tick で isValid=false に落ちる)。
        }
        // plot ローカル → world の剛体変換 (平行移動 + 回転 + 描画補間)。
        return sub.renderPose(partialTicks).transformPosition(plotCenter);
    }
}
