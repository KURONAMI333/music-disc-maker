package com.kuronami.musicdiscmaker.client.audio;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * ブームボックスの聴取アンカー。座標は持ち主の {@link EntityAnchor} に委譲し、
 * ①「server の keep-alive がまだ届いているか」の生存判定 ②音量・指向性のライブ供給 を足す。
 *
 * <h2>停止経路の受け皿</h2>
 * 持ち主がインベントリから出せば server が 0.5 秒以内に停止 packet を撃つが、ログアウト・
 * 次元移動・server 側の取りこぼしでは宛先そのものが消える。keep-alive の途絶を client 側の
 * 停止条件にしておけば、client tick フックを増やさずに ({@code DiscSoundInstance#tick} が毎 tick
 * これを呼ぶので) 自己修復する。
 *
 * <h2>ライブ設定</h2>
 * {@link LiveAudioConfigAnchor} を実装しているので、専用 GUI の音量・指向性の変更が
 * <b>鳴らし直さずに</b>効く。server は変更のたびに keep-alive を 1 通撃ち、その値をここへ書く。
 * {@code DiscSoundInstance#tick} が毎 tick 読み取って反映する — 強化版ジュークボックスの
 * スライダーが即反映されるのと同じ経路。
 *
 * <p>持ち主の entity は持ち替えでも変わらないので、アンカーは再生セッションと同じ寿命を持つ。
 */
public final class BoomboxAnchor implements DiscAnchor, LiveAudioConfigAnchor {

    /** この時間 keep-alive が来なければ停止する (ms)。server の走査間隔より十分長く取る。 */
    private static final long TIMEOUT_MS = 3_000L;

    private final EntityAnchor entity;
    private volatile long lastSeenMillis = System.currentTimeMillis();
    private volatile int volumePercent;
    private volatile int rangeBlocks;
    private volatile boolean directional;

    public BoomboxAnchor(Entity entity, int volumePercent, int rangeBlocks, boolean directional) {
        this.entity = new EntityAnchor(entity);
        this.volumePercent = volumePercent;
        this.rangeBlocks = rangeBlocks;
        this.directional = directional;
    }

    /** keep-alive 受信: 生存時刻の更新と、設定のライブ反映。 */
    public void refresh(int volume, boolean directionalValue) {
        this.volumePercent = volume;
        this.directional = directionalValue;
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

    @Override
    public int liveVolumePercent() {
        return volumePercent;
    }

    @Override
    public int liveRangeBlocks() {
        return rangeBlocks;
    }

    @Override
    public int liveDirectional() {
        return directional ? 1 : 0;
    }
}
