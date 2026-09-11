package com.kuronami.musicdiscmaker.compat.valkyrienskies;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/** VS2導入確認後だけ呼ぶ。client型を参照せず、shipyard位置をserverの実座標へ変換する。 */
public final class VS2ServerAudio {
    private VS2ServerAudio() {}

    public static boolean isMovingSource(ServerLevel level, BlockPos pos) {
        return VSGameUtilsKt.getShipObjectManagingPos(level, pos) != null;
    }

    public static Vec3 worldPosition(ServerLevel level, BlockPos pos) {
        final LoadedServerShip ship = VSGameUtilsKt.getShipObjectManagingPos(level, pos);
        final Vec3 center = new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        if (ship == null) return center;
        final Vector3d transformed = ship.getTransform().getShipToWorld()
                .transformPosition(new Vector3d(center.x, center.y, center.z));
        return new Vec3(transformed.x, transformed.y, transformed.z);
    }
}
