package com.kuronami.musicdiscmaker.client.audio;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.network.SpeakerEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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

    private final StaticAnchor source;
    private final BlockPos sourcePos;
    private final Vec3 sourceCenter;

    /** server から届いた有効スピーカー集合 (ミュート済みは含まれない)。差し替えは再生を止めない。 */
    private volatile List<SpeakerEntry> speakers = List.of();

    /**
     * 指向性。音源 (強化版ジュークボックス) の設定で、スピーカー経由で聴いている listener にも同じ値が
     * 効く。{@code SpeakerSetPayload} が運ぶ = 音源の chunk を持たない listener にも届く。
     */
    private volatile boolean directional = true;

    /** 現在選ばれている点。null = 音源本体。 */
    @Nullable
    private BlockPos chosen;
    private int chosenVolumePercent;
    private int chosenRangeBlocks;

    /**
     * 最後に解決できた音源の音量/範囲。初期値は {@code PlayDiscPayload} で来た値。client に音源の chunk が
     * 無い間 (この機能では普通の状況) はこの値を返し続ける。
     */
    private int lastSourceVolumePercent;
    private int lastSourceRangeBlocks;

    public MultiSpeakerAnchor(BlockPos sourcePos, int volumePercent, int rangeBlocks) {
        this.source = new StaticAnchor(sourcePos);
        this.sourcePos = sourcePos.immutable();
        this.sourceCenter = new Vec3(sourcePos.getX() + 0.5, sourcePos.getY() + 0.5, sourcePos.getZ() + 0.5);
        this.lastSourceVolumePercent = volumePercent;
        this.lastSourceRangeBlocks = rangeBlocks;
        this.chosenVolumePercent = volumePercent;
        this.chosenRangeBlocks = rangeBlocks;
    }

    /** server の {@code SpeakerSetPayload} を反映する。再生インスタンスは作り直さない。 */
    public void setSpeakers(List<SpeakerEntry> value) {
        this.speakers = value == null ? List.of() : List.copyOf(value);
    }

    /** 指向性の反映。{@code DiscSoundInstance} が位置の書き方を変えるだけなので再生は止まらない。 */
    public void setDirectional(boolean value) {
        this.directional = value;
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

    @Override
    public int liveDirectional() {
        return directional ? 1 : 0;
    }

    /**
     * listener に最も近い有効点を選び、その音量/範囲を解決する。
     * {@link DiscSoundInstance#tick()} は {@code worldPos} を先に呼ぶので、能力メソッドは
     * ここで確定した値を返せばよい。
     */
    private void select() {
        final Minecraft mc = Minecraft.getInstance();
        final int[] srcConfig = sourceConfig(mc);
        // 最近傍の判定と、フラットモードの範囲ゲート・音源配置は同じ 1 点 (OpenAL の listener) を使う。
        // 1 ブロックずれると境界での切り替えが二重にぶれる。
        final Vec3 ear = DiscSoundInstance.listenerPos();
        if (ear == null) {
            this.chosen = null;
            this.chosenVolumePercent = srcConfig[0];
            this.chosenRangeBlocks = srcConfig[1];
            return;
        }

        // client 側 BE で候補を解決してから、選択則そのものは純関数へ渡す (境界条件を headless で
        // 固定できるようにするため。どの点が選ばれるかは聴こえる/聴こえないを直接決める)。
        final List<SpeakerSelection.Candidate> candidates = new ArrayList<>(speakers.size());
        for (final SpeakerEntry entry : speakers) {
            final int[] config = speakerConfig(mc, entry);
            if (config == null) {
                continue; // client 側 BE がミュート済み / 撤去済み (server の再送を待たずに外す)
            }
            final BlockPos pos = entry.pos();
            candidates.add(new SpeakerSelection.Candidate(pos,
                    new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), config[0], config[1]));
        }
        final SpeakerSelection.Choice choice = SpeakerSelection.pick(ear,
                new SpeakerSelection.Candidate(null, sourceCenter, srcConfig[0], srcConfig[1]),
                candidates, chosen);
        this.chosen = choice.pos();
        this.chosenVolumePercent = choice.volumePercent();
        this.chosenRangeBlocks = choice.rangeBlocks();
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
            lastSourceVolumePercent = jukebox.getVolumePercent();
            lastSourceRangeBlocks = jukebox.getRangeBlocks();
        }
        // 引けない時は最後に解決できた値 (初期値は payload) を返す。負値で「不明」にすると、直前まで
        // 選ばれていたスピーカーの音量/範囲が音源に持ち越されて、位置と設定が別の点を指してしまう。
        return new int[] { lastSourceVolumePercent, lastSourceRangeBlocks };
    }

    /**
     * スピーカーの音量/範囲。client 側 BE がロード済みならそこから (スライダー操作の即反映)、
     * そうでなければ payload のスナップショットから解決する。ミュート済みなら {@code null} = 候補外。
     */
    @Nullable
    private static int[] speakerConfig(Minecraft mc, SpeakerEntry entry) {
        final Level level = mc.level;
        if (level != null && level.isLoaded(entry.pos())) {
            if (!(level.getBlockEntity(entry.pos()) instanceof SpeakerBlockEntity speaker)) {
                // chunk はロード済みなのに BE が無い = 撤去済み。スナップショットへ落とすと消えた位置から
                // 鳴り続けるので候補から外す (server の集合更新が届かなくても自己修復する)。
                return null;
            }
            return speaker.isMuted() ? null
                    : new int[] { speaker.getVolumePercent(), speaker.getRangeBlocks() };
        }
        // chunk 未ロードの時だけ payload のスナップショットを使う (判定できないので落とさない)。
        return new int[] { entry.volumePercent(), entry.rangeBlocks() };
    }
}
