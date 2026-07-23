package com.kuronami.musicdiscmaker.client.audio;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * entity に追従するアンカー (Sophisticated Backpacks を背負うプレイヤー等)。
 *
 * <p>プレイヤーは視線方向へ僅かにオフセットし頭部から鳴らす。それ以外の entity は原点座標。これは
 * anchor 抽象化前の {@code DiscSoundInstance.tick()} の entity 追従分岐と同一の座標計算。
 */
public final class EntityAnchor implements DiscAnchor {

    private final Entity entity;

    public EntityAnchor(Entity entity) {
        this.entity = entity;
    }

    @Override
    public boolean isValid() {
        return !entity.isRemoved();
    }

    @Override
    public Vec3 worldPos(float partialTicks) {
        if (entity instanceof Player player) {
            final Vec3 look = player.getLookAngle();
            return new Vec3(player.getX() + look.x, player.getEyeY() + look.y, player.getZ() + look.z);
        }
        return new Vec3(entity.getX(), entity.getY(), entity.getZ());
    }
}
