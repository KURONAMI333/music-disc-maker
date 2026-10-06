package com.kuronami.musicdiscmaker.client.audio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.network.SpeakerEntry;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Logical playback owner for the Golden voice and every linked speaker voice. */
final class SpeakerVoiceGroup implements PlaybackVoice {
    private final DiscSoundInstance source;
    private final DiscAnchor sourceAnchor;
    private final FanoutAudioSource pcm;
    private final Map<BlockPos, DiscSoundInstance> voices = new HashMap<>();
    private final Map<BlockPos, SpeakerEmitterAnchor> anchors = new HashMap<>();
    private final Map<BlockPos, FanoutAudioSource.Branch> branches = new HashMap<>();
    private SpeakerSetPayload latestSet;
    /** 枠不足でまだ始めていない子だけ。自然終了した子はここへ戻さない。 */
    private final java.util.Set<BlockPos> deferred = new java.util.HashSet<>();
    private boolean initialUpdate = true;
    private volatile boolean stopped;

    SpeakerVoiceGroup(DiscSoundInstance source, DiscAnchor sourceAnchor, FanoutAudioSource pcm,
            SpeakerSetPayload set) {
        this.source = source;
        this.sourceAnchor = sourceAnchor;
        this.pcm = pcm;
        this.latestSet = set;
    }

    /** 他MODによる遅延再投入でmasterが後から鳴った場合も、未開始のspeakerを現時点へ合わせる。 */
    void onFirstAudio() {
        if (stopped || !initialUpdate) return;
        initialUpdate = false;
        update(latestSet);
    }

    void update(SpeakerSetPayload set) {
        latestSet = set;
        if (sourceAnchor instanceof GoldenEmitterAnchor golden) golden.update(set);
        final Map<BlockPos, SpeakerEntry> incoming = new HashMap<>();
        for (SpeakerEntry entry : set.speakers()) incoming.put(entry.pos(), entry);
        for (BlockPos pos : new ArrayList<>(anchors.keySet())) {
            final SpeakerEntry entry = incoming.remove(pos);
            if (entry == null || entry.muted() || entry.volumePercent() <= 0) {
                removeSpeaker(pos);
            } else {
                anchors.get(pos).update(set, entry);
            }
        }
        deferred.removeIf(pos -> set.speakers().stream().noneMatch(entry -> entry.pos().equals(pos)
                && !entry.muted() && entry.volumePercent() > 0));
        for (SpeakerEntry entry : incoming.values()) {
            if (!stopped && entry.volumePercent() > 0 && !entry.muted()) {
                if (PlaybackConcurrency.client().sweepAll() < Config.maxConcurrent()) {
                    deferred.remove(entry.pos());
                    startSpeaker(set, entry, initialUpdate);
                } else deferred.add(entry.pos());
            }
        }
        initialUpdate = false;
    }

    /**
     * 枠不足で未開始だった子を、client END tickで現在のPCM位置から補充する。
     * sweep/isVoiceStoppedから呼ばない。掃除中の再帰計数を避け、自然EOFや意図停止は再開しない。
     */
    void retryDeferredSpeakers() {
        if (stopped || deferred.isEmpty()) return;
        if (source.isVoiceStopped() || !sourceAnchor.isValid()) { stopAndRelease(); return; }
        for (BlockPos pos : new ArrayList<>(deferred)) {
            final SpeakerEntry entry = latestSet.speakers().stream()
                    .filter(candidate -> candidate.pos().equals(pos)).findFirst().orElse(null);
            if (entry == null || entry.muted() || entry.volumePercent() <= 0 || voices.containsKey(pos)) {
                deferred.remove(pos);
                continue;
            }
            if (PlaybackConcurrency.client().sweepAll() >= Config.maxConcurrent()) break;
            if (stopped) return; // sweep中にmaster終了を拾った場合は補充しない。
            deferred.remove(pos);
            startSpeaker(latestSet, entry, false);
        }
    }

    private void startSpeaker(SpeakerSetPayload set, SpeakerEntry entry, boolean initial) {
        final SpeakerEmitterAnchor anchor = new SpeakerEmitterAnchor(sourceAnchor, set, entry);
        final FanoutAudioSource.Branch branch = initial ? pcm.openInitialBranch() : pcm.openLateBranch();
        final DiscSoundInstance voice = new DiscSoundInstance(anchor, branch, anchor.rangeBlocks(),
                anchor.volumePercent(), null);
        voices.put(entry.pos(), voice);
        anchors.put(entry.pos(), anchor);
        branches.put(entry.pos(), branch);
        // Child end/fault is never a second user-facing playback failure; the Golden master owns reporting.
        voice.setFailureSink(ignored -> {});
        final Runnable recreate = () -> Minecraft.getInstance().execute(() -> recreateSpeaker(entry.pos(), branch));
        branch.onDesynchronised(recreate);
        branch.onUnexpectedClose(recreate);
        try {
            Minecraft.getInstance().getSoundManager().play(voice);
        } catch (RuntimeException failure) {
            removeSpeaker(entry.pos());
            throw failure;
        }
    }

    /**
     * 同期喪失と、EOF を伴わない子 channel の close を現在の master 位置へ接続し直す。
     * 古い branch の遅延通知は同座標の後継を触らない。掃除中にも呼ぶので sweepAll を再帰しない。
     * 既存の子枠を1本置換するだけで、同時再生数は増やさない。
     */
    private void recreateSpeaker(BlockPos pos, FanoutAudioSource.Branch branch) {
        if (stopped || branches.get(pos) != branch || !branch.needsRecreation()) return;
        if (source.isVoiceStopped()) {
            stopAndRelease();
            return;
        }
        final SpeakerEntry entry = latestSet == null ? null : latestSet.speakers().stream()
                .filter(candidate -> candidate.pos().equals(pos)).findFirst().orElse(null);
        removeSpeaker(pos);
        if (sourceAnchor.isValid() && entry != null && !entry.muted() && entry.volumePercent() > 0) {
            startSpeaker(latestSet, entry, false);
        }
    }

    private void removeSpeaker(BlockPos pos) {
        final SpeakerEmitterAnchor anchor = anchors.remove(pos);
        if (anchor != null) anchor.remove();
        final DiscSoundInstance voice = voices.remove(pos);
        // main thread の execute は即時実行もある。停止が branch.close を呼ぶ前に所有を外す。
        final FanoutAudioSource.Branch branch = branches.remove(pos);
        if (voice != null) voice.stopAndRelease();
        if (branch != null) branch.close();
    }

    @Override public boolean isVoiceStopped() {
        if (stopped || source.isVoiceStopped()) {
            stopAndRelease();
            return true;
        }
        for (BlockPos pos : new ArrayList<>(voices.keySet())) {
            if (stopped) return true;
            final FanoutAudioSource.Branch branch = branches.get(pos);
            if (branch != null && branch.needsRecreation()) {
                recreateSpeaker(pos, branch);
            } else if ((branch != null && branch.isClosed()) || voices.get(pos).isVoiceStopped()) {
                removeSpeaker(pos);
            }
        }
        return stopped;
    }
    @Override public int voiceCount() { return stopped ? 0 : 1 + voices.size(); }
    @Override public void setDirectional(boolean value) { source.setDirectional(value); voices.values().forEach(v -> v.setDirectional(value)); }
    @Override public void setRangeBlocks(int value) { source.setRangeBlocks(value); }
    @Override public void setVolumePercent(int value) { source.setVolumePercent(value); }
    @Override public void stopAndRelease() {
        if (stopped) return;
        stopped = true;
        deferred.clear();
        for (BlockPos pos : new ArrayList<>(voices.keySet())) removeSpeaker(pos);
        source.stopAndRelease();
    }
}
