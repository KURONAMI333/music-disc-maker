package com.kuronami.musicdiscmaker.client.audio;

import com.kuronami.musicdiscmaker.component.PauseAwarePlaybackClock;
import com.kuronami.musicdiscmaker.component.PlaybackClockAccess;
import net.minecraft.client.Minecraft;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * ブームボックスの聴取アンカー。座標は「持ち歩き = 持ち主に追従 / 設置 = その位置」の 2 通りで、
 * そこに<b>「server の keep-alive がまだ届いているか」の生存判定</b>を足す。
 *
 * <h2>張り付け先は途中で変わる</h2>
 * 鳴ったまま地面に置く / 鳴ったまま壊して拾い直す、で<b>セッションは続いたまま音源だけが
 * 持ち主 ⇄ 座標へ移る</b>。曲は変わらないので client は keep-alive を {@code REFRESH} として
 * 受け、ストリームには触らない。そこでアンカーの張り付け先を差し替えられるようにしてある
 * ({@link #retargetCarried} / {@link #retargetPlaced})。差し替えないと、置いた機体の音が
 * 持ち主に付いてきたり、拾った機体の音が壊した場所に残ったりする。
 *
 * <h2>停止経路の受け皿</h2>
 * インベントリから出せば server が {@code BoomboxPlayback.STALE_MS} 以内に停止 packet を撃つが、
 * ログアウト・次元移動・server 側の取りこぼしでは宛先そのものが消える。keep-alive の途絶を
 * client 側の停止条件にしておけば、client tick フックを増やさずに
 * ({@code DiscSoundInstance#tick} が毎 tick {@link #isValid()} を呼ぶので) 自己修復する。
 *
 * <p><b>{@link LiveConfigAnchor} は実装しない。</b> 携帯は範囲固定・指向性なし
 * (設計上の決定 2026-09-07) なので、毎 tick 読み直す設定が存在しない。あの能力は金ジュークの
 * スライダーのための口で、{@code configPos()} が {@link BlockPos} を返す契約になっている。
 */
public final class BoomboxAnchor implements DiscAnchor {

    /**
     * この時間 keep-alive が来なければ停止する (ms)。server の打刻切れ判定
     * ({@code BoomboxPlayback.STALE_MS} = 1.5 秒) より十分長く取る — こちらが先に切れると、
     * 一時的な遅延で鳴っているものを勝手に止めてしまう。
     */
    public static final long TIMEOUT_MS = 3_000L;

    /** 設置されている機体を指す entity id。 */
    private static final int PLACED = -1;

    /** 持ち歩き。{@code null} なら設置。 */
    @Nullable
    private volatile EntityAnchor carrier;
    /** 追従先の entity id。設置なら {@link #PLACED}。差し替えが要るかの判定に使う。 */
    private volatile int carrierEntityId;
    /** 設置位置 (持ち歩きでは未使用)。 */
    private volatile Vec3 placed;

    private volatile long lastSeenMillis = playbackTimeMs();

    private static long playbackTimeMs() {
        final var server = Minecraft.getInstance().getSingleplayerServer();
        return server instanceof PlaybackClockAccess clock
                ? clock.mdm$playbackTimeMs() : PauseAwarePlaybackClock.realTimeMs();
    }

    private BoomboxAnchor(@Nullable Entity carrier, int carrierEntityId, Vec3 placed) {
        this.carrier = carrier == null ? null : new EntityAnchor(carrier);
        this.carrierEntityId = carrierEntityId;
        this.placed = placed;
    }

    /**
     * 持ち主に追従するアンカー。
     *
     * @param carrier 持ち主
     * @return アンカー
     */
    public static BoomboxAnchor carried(Entity carrier) {
        return new BoomboxAnchor(carrier, carrier.getId(), Vec3.ZERO);
    }

    /**
     * 設置位置に固定されたアンカー。
     *
     * <p>{@link StaticAnchor} を使わないのは、あちらが「そのブロックが air になったら止める」を
     * 見るため。ブームボックスの停止は keep-alive の途絶 1 本に寄せてあるので、判定源を 2 つに
     * 増やさない。
     *
     * @param pos 設置位置
     * @return アンカー
     */
    public static BoomboxAnchor placed(BlockPos pos) {
        return new BoomboxAnchor(null, PLACED, Vec3.atCenterOf(pos));
    }

    /** keep-alive 受信: 生存時刻を更新する。 */
    public void refresh() {
        this.lastSeenMillis = playbackTimeMs();
    }

    /**
     * いま追従している entity の id。設置なら負。
     *
     * @return entity id、または負 (設置)
     */
    public int carrierEntityId() {
        return carrierEntityId;
    }

    /**
     * いま固定されている座標 (持ち歩き中は意味を持たない)。
     *
     * @return 設置位置
     */
    public Vec3 placedTarget() {
        return placed;
    }

    /**
     * 追従先の entity へ張り替える (壊して拾った・置いたものを回収した)。
     *
     * @param entity 新しい追従先
     */
    public void retargetCarried(Entity entity) {
        this.carrier = new EntityAnchor(entity);
        this.carrierEntityId = entity.getId();
    }

    /**
     * 固定座標へ張り替える (鳴らしたまま地面に置いた)。
     *
     * @param pos 新しい音源座標
     */
    public void retargetPlaced(Vec3 pos) {
        this.carrier = null;
        this.carrierEntityId = PLACED;
        this.placed = pos;
    }

    @Override
    public boolean isValid() {
        if (playbackTimeMs() - lastSeenMillis > TIMEOUT_MS) {
            return false;
        }
        final EntityAnchor following = carrier;
        return following == null || following.isValid();
    }

    @Override
    public Vec3 worldPos(float partialTicks) {
        final EntityAnchor following = carrier;
        return following == null ? placed : following.worldPos(partialTicks);
    }
}
