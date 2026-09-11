package com.kuronami.musicdiscmaker.client.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.network.SpeakerEntry;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;
import com.kuronami.musicdiscmaker.speaker.SpeakerSelection;
import com.kuronami.musicdiscmaker.speaker.SpeakerCone;
import com.kuronami.musicdiscmaker.speaker.SpeakerTransition;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 一つのGolden音源に紐づく固定speaker集合の聴取アンカー。
 *
 * <p>音声ストリームは持たず、毎 tick listener に最も適した聴取点を選ぶだけである。これにより
 * speaker設定の更新は再デコードを起こさず、途中で届いた集合も進行中の再生へ反映できる。
 * ホーンの向きは音量係数へ適用する。候補の選択は従来どおり距離で行う。
 */
public final class MultiSpeakerAnchor implements DiscAnchor, LiveAudioConfig {

    private volatile SpeakerSetPayload payload;
    private DiscAnchor sourceAnchor;
    private final SpeakerTransition transition = new SpeakerTransition();
    private final Map<BlockPos, Double> coneGains = new HashMap<>();
    private volatile SpeakerTransition.Frame frame;
    private long selectionTick = Long.MIN_VALUE;

    public MultiSpeakerAnchor(SpeakerSetPayload payload) {
        this(payload, new StaticAnchor(payload.sourcePos()));
    }

    public MultiSpeakerAnchor(SpeakerSetPayload payload, DiscAnchor sourceAnchor) {
        this.sourceAnchor = java.util.Objects.requireNonNull(sourceAnchor);
        update(payload);
    }

    /** 再生中の音源を保持したまま集合を合成する。集合同士を入れ子にしない。 */
    void useSourceAnchor(DiscAnchor source) {
        if (source == this) return;
        sourceAnchor = source instanceof MultiSpeakerAnchor combined
                ? combined.sourceAnchor : java.util.Objects.requireNonNull(source);
    }

    /** serverからの集合更新を取り込む。既存の音声インスタンスはこのanchorを継続して読む。 */
    public void update(SpeakerSetPayload next) {
        this.payload = next;
        final HashSet<BlockPos> retained = new HashSet<>();
        for (SpeakerEntry entry : next.speakers()) retained.add(entry.pos());
        coneGains.keySet().retainAll(retained);
    }

    /** Snapshot for the independent-voice playback group. */
    SpeakerSetPayload payload() {
        return payload;
    }

    boolean hasSpeakers() {
        return !payload.speakers().isEmpty();
    }

    @Override
    public boolean isValid() {
        // speakerが有る間は未ロードsourceをairと誤読しない。最後のspeakerが外れた後も
        // 元の移動anchorを保持し、その存在判定へ戻す。
        return payload.speakers().isEmpty() ? sourceAnchor.isValid() : true;
    }

    @Override
    public Vec3 worldPos(float partialTicks) {
        refreshSelection();
        final SpeakerTransition.Frame current = frame;
        return current == null ? sourceAnchor.worldPos(partialTicks) : new Vec3(current.x(), current.y(), current.z());
    }

    @Override
    public int rangeBlocks() {
        refreshSelection();
        return rangeForFrame(frame, currentConfig().rangeBlocks());
    }

    @Override
    public int volumePercent() {
        refreshSelection();
        final SpeakerTransition.Frame current = frame;
        return current == null ? 0 : current.volumePercent();
    }

    @Override
    public double gainMultiplier() {
        refreshSelection();
        final SpeakerTransition.Frame current = frame;
        return current == null ? 0.0D : current.gainMultiplier();
    }

    @Override
    public boolean directional() {
        return currentConfig().directional();
    }

    private void refreshSelection() {
        final SpeakerSetPayload currentPayload = payload;
        if (currentPayload == null) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            frame = null;
            return;
        }
        final long currentTick = minecraft.level == null ? minecraft.player.tickCount : minecraft.level.getGameTime();
        if (!shouldAdvanceSelection(selectionTick, currentTick)) {
            return;
        }
        final Vec3 listener = DiscSoundInstance.listenerPos();
        if (listener == null) {
            frame = null;
            return;
        }
        final AudioConfig config = currentConfig();
        final int effectiveRange = effectiveRange(config.rangeBlocks());
        final List<SpeakerSelection.Candidate> candidates = new ArrayList<>(currentPayload.speakers().size() + 1);
        candidates.add(sourceCandidate(effectiveRange, config.volumePercent()));
        if (!config.directional()) coneGains.clear();
        for (SpeakerEntry entry : currentPayload.speakers()) {
            double gain = 1.0D;
            if (config.directional()) {
                final double target = SpeakerCone.gain(entry.orientation(),
                        entry.pos().getX() + 0.5D, entry.pos().getY() + 0.5D, entry.pos().getZ() + 0.5D,
                        listener.x, listener.y, listener.z);
                gain = SpeakerCone.approach(coneGains.getOrDefault(entry.pos(), 1.0D), target);
                coneGains.put(entry.pos(), gain);
            }
            candidates.add(candidate(sourceId(entry.pos()), entry.pos(), effectiveRange,
                    combinedVolume(config.volumePercent(), entry.volumePercent()), entry.muted(), gain));
        }
        frame = transition.advance(new SpeakerSelection.Listener(listener.x, listener.y, listener.z),
                candidates, config.directional());
        selectionTick = currentTick;
    }

    /** 同一game tickのpayload差し替えは次tickへ送る。1tick内に遷移stepを複数進めない。 */
    static boolean shouldAdvanceSelection(long lastSelectionTick, long currentTick) {
        return lastSelectionTick != currentTick;
    }

    /** 遷移中はFrameのrangeを返し、OpenALのmax distanceを補正gainの分母と揃える。 */
    static int rangeForFrame(SpeakerTransition.Frame current, int fallback) {
        return current == null ? fallback : Math.max(0, (int) Math.round(current.rangeBlocks()));
    }

    SpeakerSelection.Candidate sourceCandidate(int rangeBlocks, int volumePercent) {
        final Vec3 center = sourceAnchor.worldPos(1.0F);
        return new SpeakerSelection.Candidate("source:" + sourceId(payload.sourcePos()),
                center.x, center.y, center.z, rangeBlocks, volumePercent, false, 1.0D);
    }

    private static SpeakerSelection.Candidate candidate(String id, BlockPos pos, int rangeBlocks,
                                                        int volumePercent, boolean muted, double gain) {
        final Vec3 center = centerOf(pos);
        return new SpeakerSelection.Candidate(id, center.x, center.y, center.z, rangeBlocks, volumePercent, muted, gain);
    }

    private static int combinedVolume(int sourceVolume, int speakerVolume) {
        return (int) Math.min(Integer.MAX_VALUE, Math.round(sourceVolume * (double) speakerVolume / 100.0D));
    }

    private static int effectiveRange(int rangeBlocks) {
        return rangeBlocks > 0 ? Math.min(rangeBlocks, Config.maxPlaybackRange()) : Config.playbackRange();
    }

    /**
     * empty Set の後はserverからspeaker更新が止まる。その時だけ従来StaticAnchorと同様に
     * clientのGolden BEを再読し、未ロードならSet(empty)に載った最新値を初期値として残す。
     */
    private AudioConfig currentConfig() {
        final SpeakerSetPayload currentPayload = payload;
        if (!currentPayload.speakers().isEmpty()) {
            return new AudioConfig(currentPayload.rangeBlocks(), currentPayload.volumePercent(),
                    currentPayload.directional());
        }
        final BlockPos configPos = sourceAnchor instanceof LiveConfigAnchor live ? live.configPos() : null;
        final Minecraft minecraft = Minecraft.getInstance();
        if (configPos != null && minecraft.level != null && minecraft.level.isLoaded(configPos)
                && minecraft.level.getBlockEntity(configPos) instanceof GoldenJukeboxBlockEntity golden) {
            return new AudioConfig(golden.getRangeBlocks(), golden.getVolumePercent(), golden.isDirectional());
        }
        return new AudioConfig(currentPayload.rangeBlocks(), currentPayload.volumePercent(),
                currentPayload.directional());
    }

    private record AudioConfig(int rangeBlocks, int volumePercent, boolean directional) {
    }

    private static String sourceId(BlockPos pos) {
        return Long.toString(pos.asLong());
    }

    private static Vec3 centerOf(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }
}
