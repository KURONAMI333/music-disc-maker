package com.kuronami.musicdiscmaker.compat.create;

import com.kuronami.musicdiscmaker.client.audio.DiscAnchor;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Create 捕獲式 contraption に載った音源のアンカー (変換式でなく捕獲式 = ブロックは AIR 化され
 * MovementBehaviour だけが駆動する)。contraption 内 local 座標を {@code AbstractContraptionEntity} の
 * 剛体変換で毎 tick world 座標へ写す。
 *
 * <p>このクラスは Create 型 ({@link AbstractContraptionEntity}) を参照するため、{@link CreateAudioClient}
 * が {@link com.kuronami.musicdiscmaker.network.ContraptionPlayDiscPayload} 受信時にのみ class-load する
 * (= Create 導入時のみ)。
 */
public final class ContraptionAnchor implements DiscAnchor {

    private final AbstractContraptionEntity contraption;
    /** contraption 内 local 空間でのブロック中心座標。 */
    private final Vec3 localCenter;

    public ContraptionAnchor(AbstractContraptionEntity contraption, BlockPos localPos) {
        this.contraption = contraption;
        this.localCenter = Vec3.atCenterOf(localPos);
    }

    @Override
    public boolean isValid() {
        // contraption entity が生きている限り再生継続。解体・アンロードで除去 → false → 自己停止し、
        // world へ戻ったブロックの BE が既存経路 (serverTick) で通常再生を再開する。
        return contraption != null && !contraption.isRemoved();
    }

    @Override
    public Vec3 worldPos(float partialTicks) {
        // local → world の剛体変換 (平行移動 + 回転)。Create が context.position を算出するのと同じ経路。
        return contraption.toGlobalVector(localCenter, partialTicks);
    }
}
