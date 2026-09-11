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
    private boolean initialUpdate = true;
    private volatile boolean stopped;

    SpeakerVoiceGroup(DiscSoundInstance source, DiscAnchor sourceAnchor, FanoutAudioSource pcm,
            SpeakerSetPayload set) {
        this.source = source;
        this.sourceAnchor = sourceAnchor;
        this.pcm = pcm;
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
        for (SpeakerEntry entry : incoming.values()) {
            if (!stopped && entry.volumePercent() > 0 && !entry.muted()
                    && PlaybackConcurrency.client().sweepAll() < Config.maxConcurrent()) {
                startSpeaker(set, entry, initialUpdate);
            }
        }
        initialUpdate = false;
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
        branch.onDesynchronised(() -> Minecraft.getInstance().execute(() -> recreateDesynchronised(entry.pos())));
        try {
            Minecraft.getInstance().getSoundManager().play(voice);
        } catch (RuntimeException failure) {
            removeSpeaker(entry.pos());
            throw failure;
        }
    }

    private void recreateDesynchronised(BlockPos pos) {
        final FanoutAudioSource.Branch branch = branches.get(pos);
        if (stopped || branch == null || !branch.desynchronised()) return;
        final SpeakerEntry entry = latestSet == null ? null : latestSet.speakers().stream()
                .filter(candidate -> candidate.pos().equals(pos)).findFirst().orElse(null);
        removeSpeaker(pos);
        if (entry != null && !entry.muted() && entry.volumePercent() > 0
                && PlaybackConcurrency.client().sweepAll() < Config.maxConcurrent()) startSpeaker(latestSet, entry, false);
    }

    private void removeSpeaker(BlockPos pos) {
        final SpeakerEmitterAnchor anchor = anchors.remove(pos);
        if (anchor != null) anchor.remove();
        final DiscSoundInstance voice = voices.remove(pos);
        if (voice != null) voice.stopAndRelease();
        final FanoutAudioSource.Branch branch = branches.remove(pos);
        if (branch != null) branch.close();
    }

    @Override public boolean isVoiceStopped() {
        if (stopped || source.isVoiceStopped()) {
            stopAndRelease();
            return true;
        }
        for (BlockPos pos : new ArrayList<>(voices.keySet())) {
            if (voices.get(pos).isVoiceStopped()) {
                removeSpeaker(pos);
            }
        }
        return false;
    }
    @Override public int voiceCount() { return stopped ? 0 : 1 + voices.size(); }
    @Override public void setDirectional(boolean value) { source.setDirectional(value); voices.values().forEach(v -> v.setDirectional(value)); }
    @Override public void setRangeBlocks(int value) { source.setRangeBlocks(value); }
    @Override public void setVolumePercent(int value) { source.setVolumePercent(value); }
    @Override public void stopAndRelease() {
        if (stopped) return;
        stopped = true;
        for (BlockPos pos : new ArrayList<>(voices.keySet())) removeSpeaker(pos);
        source.stopAndRelease();
    }
}
