package com.kuronami.musicdiscmaker.client.audio;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 手持ちブームボックスの聴取アンカー。座標は {@link EntityAnchor} に委譲し、生存判定に
 * 「server の keep-alive がまだ届いているか」を足す。
 *
 * <p>これが停止経路の受け皿になる。持ち主がブームボックスをしまえば server が即時に停止 packet を
 * 出すが、地面に落とした・チェストへ移した場合は {@code inventoryTick} 自体が来なくなるので server は
 * 気づけない。keep-alive の途絶を client 側の停止条件にしておけば、client tick フックを増やさずに
 * ({@code DiscSoundInstance#tick} が毎 tick これを呼ぶので) 自己修復する。
 */
public final class BoomboxAnchor implements DiscAnchor {

    /** この時間 keep-alive が来なければ停止する (ms)。server の心拍間隔より十分長く取る。 */
    private static final long TIMEOUT_MS = 3_000L;

    private final EntityAnchor entity;
    private volatile long lastSeenMillis = System.currentTimeMillis();

    public BoomboxAnchor(Entity entity) {
        this.entity = new EntityAnchor(entity);
    }

    /** keep-alive 受信。 */
    public void refresh() {
        this.lastSeenMillis = System.currentTimeMillis();
    }

    @Override
    public boolean isValid() {
        return entity.isValid() && System.currentTimeMillis() - lastSeenMillis <= TIMEOUT_MS;
    }

    @Override
    public Vec3 worldPos(float partialTicks) {
        return entity.worldPos(partialTicks);
    }
}
