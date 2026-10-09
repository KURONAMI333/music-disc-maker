package com.kuronami.musicdiscmaker.client.audio;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.component.PauseAwarePlaybackClock;
import com.kuronami.musicdiscmaker.component.PlaybackClockAccess;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 金ジューク付きトロッコの聴取アンカー。entity 追従に
 * <b>「server の keep-alive がまだ届いているか」の生存判定</b>を足したもの
 * ({@link BoomboxAnchor} と同じ契約で、追従先は entity のみ — cart は「置く/持つ」の
 * 張り替えが無く、entity そのものが鳴る)。
 *
 * <h2>停止経路の受け皿</h2>
 * entity の破壊・拾い直し・次元移動では server が stop packet を撃つが、
 * ログアウト・chunk 取りこぼし・packet ロスでは宛先そのものが消える。keep-alive の途絶を
 * client 側の停止条件にしておけば、{@code DiscSoundInstance#tick} が毎 tick
 * {@link #isValid()} を呼ぶので client tick フックを増やさずに自己修復する。
 *
 * <h2>追従先の生存</h2>
 * 追従中の entity が client world から外れた場合 ({@code isRemoved}) も停止する。
 * 位置が分からない移動音源を鳴らし続けることができないためで、entity が戻れば次の
 * keep-alive で lease が掛け直されて復帰する。
 *
 * <h2>末尾の猶予帯</h2>
 * {@link BoomboxLiveness} を共有する。server が尺の終端で意図的に黙る場合に備えて
 * {@link #armTailSilence} で窓を開く (cart の server driver は現状 終端で黙らないが、
 * 受け側を時計に依存させないために同じ手順を置く)。
 */
public final class CartAnchor implements DiscAnchor, LiveAudioConfig {

    /**
     * この時間 keep-alive が来なければ停止する (ms)。server の再送間隔
     * ({@code GoldenJukeboxMinecartEntity.KEEPALIVE_INTERVAL_TICKS} = 1 秒) の 3 倍に取る。
     */
    public static final long TIMEOUT_MS = 3_000L;

    /** 追従中の entity。視界から外れると null 化せず weak ではなく強参照のまま → isRemoved で判定。 */
    private final EntityAnchor following;

    private volatile long lastSeenMillis = playbackTimeMs();

    /** {@link BoomboxLiveness.TailSilence#NONE} = 従来の判定。 */
    private volatile BoomboxLiveness.TailSilence tailSilence = BoomboxLiveness.TailSilence.NONE;

    // LiveAudioConfig: keep-alive の payload ごとに更新され、DiscSoundInstance /
    // VanillaSpeakerSoundInstance が毎 tick 読む。
    private volatile int volumePercent;
    private volatile int rangeBlocks;
    private volatile boolean directional = true;

    private CartAnchor(Entity entity) {
        this.following = new EntityAnchor(entity);
    }

    /**
     * 追従対象の entity がいま client world に居る時だけアンカーを作る。
     * 居なければ null (呼び出し側は今回の再生要求を諦める。keep-alive で復帰する)。
     */
    @Nullable
    public static CartAnchor forEntity(int entityId) {
        final Minecraft mc = Minecraft.getInstance();
        final Entity entity = mc.level == null ? null : mc.level.getEntity(entityId);
        return entity == null ? null : new CartAnchor(entity);
    }

    /** keep-alive 受信: 生存時刻と live 設定を更新する。 */
    public void refresh(int volumePercent, int rangeBlocks, boolean directional) {
        this.volumePercent = volumePercent;
        this.rangeBlocks = rangeBlocks;
        this.directional = directional;
        this.lastSeenMillis = playbackTimeMs();
    }

    /**
     * この曲の想定終端を登録する = 末尾の猶予帯の沈黙を「server が消えた」と誤解しない。
     *
     * @param remainingMillis いまから曲が終わるまでの想定 (尺 − 開始 offset)
     * @param tailGraceMillis server が終端後も黙って待つ長さ
     */
    public void armTailSilence(long remainingMillis, long tailGraceMillis) {
        this.tailSilence = BoomboxLiveness.tailSilence(playbackTimeMs() + Math.max(0L, remainingMillis),
                tailGraceMillis);
    }

    @Override
    public boolean isValid() {
        if (!BoomboxLiveness.isAlive(playbackTimeMs(), lastSeenMillis, TIMEOUT_MS, tailSilence)) {
            return false;
        }
        return following.isValid();
    }

    @Override
    public Vec3 worldPos(float partialTicks) {
        return following.worldPos(partialTicks);
    }

    @Override
    public int rangeBlocks() {
        return rangeBlocks;
    }

    @Override
    public int volumePercent() {
        return volumePercent;
    }

    @Override
    public boolean directional() {
        return directional;
    }

    private static long playbackTimeMs() {
        final var server = Minecraft.getInstance().getSingleplayerServer();
        return server instanceof PlaybackClockAccess clock
                ? clock.mdm$playbackTimeMs() : PauseAwarePlaybackClock.realTimeMs();
    }
}
