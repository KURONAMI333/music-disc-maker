package com.kuronami.musicdiscmaker.compat.valkyrienskies;

import org.joml.Matrix4dc;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.ClientShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import com.kuronami.musicdiscmaker.client.audio.DiscAnchor;
import com.kuronami.musicdiscmaker.client.audio.StaticAnchor;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Valkyrien Skies 2 の物理船 (変換式) に載った jukebox 音源のアンカー。VS2 は捕獲式 (Create) と違い
 * ブロックを shipyard 座標に実在させ tick し続けるため、trigger も payload も要らず音は既に鳴る
 * ({@link StaticAnchor#isValid()} がそのまま通る)。問題は音源座標だけ ── shipyard 座標のまま world へ
 * 変換されずズレる (VS2 の「見えないスピーカー」) ので、ここで毎 tick 剛体変換して補正する。
 *
 * <p>存在判定は {@link StaticAnchor} に委譲する (shipyard 座標のブロックが JUKEBOX/強化版のままか)。
 * 座標は {@code ClientShip.getRenderTransform().getShipToWorld()} (joml {@link Matrix4dc}) で
 * shipyard→world へ変換する。船から外れた (解体等で ship 管理外になった) 時は生の shipyard 座標を返す。
 *
 * <p>このクラスは VS2 型 ({@link VSGameUtilsKt}/{@link ClientShip}) と joml を参照するため、
 * {@link VS2Compat#resolveShipAnchor} が {@code isModLoaded} gate を通過した後にのみ class-load される
 * (SophisticatedCoreCompat と同型の soft-dep パターン)。Eureka!/Clockwork も VS2 backend なので追加コード不要。
 */
public final class VSShipAnchor implements DiscAnchor {

    private final ClientLevel level;
    /** ブロックの実座標 (shipyard 空間)。VS2 は原ブロックをここに実在させ続ける。 */
    private final BlockPos shipyardPos;
    /** shipyard 空間でのブロック中心 (pos + 0.5)。 */
    private final Vec3 shipyardCenter;
    /** 存在判定は固定アンカーと同一 (shipyard 座標のブロックが撤去されていないか)。 */
    private final StaticAnchor validity;

    private VSShipAnchor(ClientLevel level, BlockPos pos) {
        this.level = level;
        this.shipyardPos = pos.immutable();
        this.shipyardCenter = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        this.validity = new StaticAnchor(pos);
    }

    /**
     * pos が VS2 船の管理下なら追従アンカーを生成する。船外 (通常 world) なら {@code null} を返し、
     * 呼び出し側が従来の {@link StaticAnchor} で再生する。VS2 導入時のみ呼ばれる ({@link VS2Compat} の gate 後)。
     */
    static DiscAnchor tryCreate(ClientLevel level, BlockPos pos) {
        final ClientShip ship = VSGameUtilsKt.getShipObjectManagingPos(level, pos);
        return ship != null ? new VSShipAnchor(level, pos) : null;
    }

    @Override
    public boolean isValid() {
        return validity.isValid();
    }

    @Override
    public Vec3 worldPos(float partialTicks) {
        final ClientShip ship = VSGameUtilsKt.getShipObjectManagingPos(level, shipyardPos);
        if (ship == null) {
            return shipyardCenter; // 船から外れた (解体等) → 生の座標。isValid が撤去なら別途停止する。
        }
        // render 変換 (フレーム補間済み) で shipyard→world。audio は tick 精度で十分だが、render 変換は
        // 現フレームの滑らかな位置を返すので瞬間的なカクつきも避けられる。
        final Matrix4dc shipToWorld = ship.getRenderTransform().getShipToWorld();
        final Vector3d w = shipToWorld.transformPosition(
                new Vector3d(shipyardCenter.x, shipyardCenter.y, shipyardCenter.z));
        return new Vec3(w.x, w.y, w.z);
    }
}
