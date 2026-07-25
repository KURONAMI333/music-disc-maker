package com.kuronami.musicdiscmaker.client.audio;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.network.SpeakerEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 「1 論理再生 + マルチアンカー聴取」モデルの聴取側。音源 1 つにつき {@link DiscSoundInstance} は
 * 1 本のままで、このアンカーが毎 tick「listener に最も近い有効点 (音源本体 or 非ミュートで範囲内の
 * スピーカー)」の座標・音量・可聴範囲を供給する。台数に対して O(1) (デコード 1 本・streaming
 * チャンネル 1 本)。
 *
 * <p>スピーカー集合が空のときは {@link StaticAnchor} と完全に同じ挙動に縮退する: 位置は音源の中心、
 * 音量/範囲は「client world に強化版ジュークボックスの BE が実在するときだけ」再読する。バニラ
 * ジュークボックス (BE が {@link GoldenJukeboxBlockEntity} でない) では負値を返し、payload の初期値が
 * そのまま生き続ける。
 *
 * <p>存在判定 ({@link #isValid()}) は内部の {@link StaticAnchor} へ委譲する (chunk 未ロードでは停止
 * しない規律をそのまま保つ)。
 */
public final class MultiSpeakerAnchor implements DiscAnchor, LiveAudioConfigAnchor {

    /**
     * 最近傍の切り替えに要する距離差 (ブロック)。等距離付近で毎 tick 選択が反転すると、範囲が違う
     * 候補間で {@code applyLinearAttenuation} が毎 tick 走る (無駄なチャンネル操作) ため、現在の選択を
     * この分だけ優遇する。
     */
    private static final double SWITCH_MARGIN = 0.5;

    private final StaticAnchor source;
    private final BlockPos sourcePos;
    private final Vec3 sourceCenter;

    /** server から届いた有効スピーカー集合 (ミュート済みは含まれない)。差し替えは再生を止めない。 */
    private volatile List<SpeakerEntry> speakers = List.of();

    /** 現在選ばれている点。null = 音源本体。 */
    @Nullable
    private BlockPos chosen;
    private int chosenVolumePercent = -1;
    private int chosenRangeBlocks = -1;

    public MultiSpeakerAnchor(BlockPos sourcePos) {
        this.source = new StaticAnchor(sourcePos);
        this.sourcePos = sourcePos.immutable();
        this.sourceCenter = new Vec3(sourcePos.getX() + 0.5, sourcePos.getY() + 0.5, sourcePos.getZ() + 0.5);
    }

    /** server の {@code SpeakerSetPayload} を反映する。再生インスタンスは作り直さない。 */
    public void setSpeakers(List<SpeakerEntry> value) {
        this.speakers = value == null ? List.of() : List.copyOf(value);
    }

    @Override
    public boolean isValid() {
        return source.isValid();
    }

    @Override
    public Vec3 worldPos(float partialTicks) {
        select();
        final BlockPos pick = chosen;
        return pick == null ? sourceCenter : new Vec3(pick.getX() + 0.5, pick.getY() + 0.5, pick.getZ() + 0.5);
    }

    @Override
    public int liveVolumePercent() {
        return chosenVolumePercent;
    }

    @Override
    public int liveRangeBlocks() {
        return chosenRangeBlocks;
    }

    /**
     * listener に最も近い有効点を選び、その音量/範囲を解決する。
     * {@link DiscSoundInstance#tick()} は {@code worldPos} を先に呼ぶので、能力メソッドは
     * ここで確定した値を返せばよい。
     */
    private void select() {
        final Minecraft mc = Minecraft.getInstance();
        final int[] srcConfig = sourceConfig(mc);
        final Vec3 ear = listenerPos(mc);
        if (ear == null) {
            this.chosen = null;
            this.chosenVolumePercent = srcConfig[0];
            this.chosenRangeBlocks = srcConfig[1];
            return;
        }

        // 音源は常に候補 (全スピーカーが範囲外/ミュートなら音源単体の現行挙動へ縮退する)。
        BlockPos bestPos = null;
        double bestDistance = ear.distanceTo(sourceCenter);
        int bestVolume = srcConfig[0];
        int bestRange = srcConfig[1];

        // 前回の選択がまだ候補として生きているか (ヒステリシスの判定材料)。
        boolean currentAlive = chosen == null;
        double currentDistance = bestDistance;
        int currentVolume = srcConfig[0];
        int currentRange = srcConfig[1];

        for (final SpeakerEntry entry : speakers) {
            final int[] config = speakerConfig(mc, entry);
            if (config == null) {
                continue; // client 側 BE がミュート済み (server の再送を待たずに外す)
            }
            final BlockPos pos = entry.pos();
            final Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            final double distance = ear.distanceTo(center);
            if (distance > config[1]) {
                continue; // このスピーカーの可聴範囲の外
            }
            if (pos.equals(chosen)) {
                currentAlive = true;
                currentDistance = distance;
                currentVolume = config[0];
                currentRange = config[1];
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                bestPos = pos;
                bestVolume = config[0];
                bestRange = config[1];
            }
        }

        // 等距離付近で毎 tick 反転しないよう、前回の選択に切り替え余裕を与える。
        if (currentAlive && currentDistance <= bestDistance + SWITCH_MARGIN) {
            this.chosenVolumePercent = currentVolume;
            this.chosenRangeBlocks = currentRange;
            return;
        }
        this.chosen = bestPos;
        this.chosenVolumePercent = bestVolume;
        this.chosenRangeBlocks = bestRange;
    }

    @Nullable
    private static Vec3 listenerPos(Minecraft mc) {
        final Entity camera = mc.getCameraEntity();
        if (camera != null) {
            return camera.getEyePosition();
        }
        return mc.player != null ? mc.player.getEyePosition() : null;
    }

    /**
     * 音源の音量/範囲。{@link StaticAnchor} + {@code DiscSoundInstance} の既存分岐と同じ条件
     * (level 生成済み・chunk ロード済み・BE が強化版ジュークボックス) でのみ値を返し、それ以外は
     * {@code {-1, -1}} = 不明。
     */
    private int[] sourceConfig(Minecraft mc) {
        final Level level = mc.level;
        if (level != null && level.isLoaded(sourcePos)
                && level.getBlockEntity(sourcePos) instanceof GoldenJukeboxBlockEntity jukebox) {
            return new int[] { jukebox.getVolumePercent(), jukebox.getRangeBlocks() };
        }
        return new int[] { -1, -1 };
    }

    /**
     * スピーカーの音量/範囲。client 側 BE がロード済みならそこから (スライダー操作の即反映)、
     * そうでなければ payload のスナップショットから解決する。ミュート済みなら {@code null} = 候補外。
     */
    @Nullable
    private static int[] speakerConfig(Minecraft mc, SpeakerEntry entry) {
        final Level level = mc.level;
        if (level != null && level.isLoaded(entry.pos())
                && level.getBlockEntity(entry.pos()) instanceof SpeakerBlockEntity speaker) {
            if (speaker.isMuted()) {
                return null;
            }
            return new int[] { speaker.getVolumePercent(), speaker.getRangeBlocks() };
        }
        return new int[] { entry.volumePercent(), entry.rangeBlocks() };
    }
}
