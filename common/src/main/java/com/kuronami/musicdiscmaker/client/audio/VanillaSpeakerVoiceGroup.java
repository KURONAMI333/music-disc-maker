package com.kuronami.musicdiscmaker.client.audio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.network.SpeakerEntry;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Independent positional voices for a resource-pack/vanilla Golden record. */
final class VanillaSpeakerVoiceGroup implements PlaybackVoice {
    private final VanillaSpeakerSoundInstance source;
    private final GoldenEmitterAnchor sourceAnchor;
    private final String soundEventId;
    private final long offsetMs;
    private final long receivedPlaybackMs;
    private final LongSupplier playbackClockMs;
    private final Map<BlockPos, VanillaSpeakerSoundInstance> voices = new HashMap<>();
    private final Map<BlockPos, SpeakerEmitterAnchor> anchors = new HashMap<>();
    /** The set applied just after the Golden source starts must share its exact payload offset. */
    private boolean initialUpdate = true;
    private boolean stopped;

    VanillaSpeakerVoiceGroup(VanillaSpeakerSoundInstance source, GoldenEmitterAnchor sourceAnchor,
            String soundEventId, long offsetMs, long receivedPlaybackMs, LongSupplier playbackClockMs) {
        this.source = source; this.sourceAnchor = sourceAnchor; this.soundEventId = soundEventId;
        this.offsetMs = offsetMs; this.receivedPlaybackMs = receivedPlaybackMs;
        this.playbackClockMs = playbackClockMs;
    }
    void update(SpeakerSetPayload set) {
        sourceAnchor.update(set);
        final Map<BlockPos, SpeakerEntry> incoming = new HashMap<>();
        for (SpeakerEntry entry : set.speakers()) incoming.put(entry.pos(), entry);
        for (BlockPos pos : new ArrayList<>(anchors.keySet())) {
            final SpeakerEntry entry = incoming.remove(pos);
            if (entry == null || entry.muted() || entry.volumePercent() <= 0) {
                anchors.remove(pos).remove();
                final VanillaSpeakerSoundInstance voice = voices.remove(pos);
                if (voice != null) voice.stopAndRelease();
            } else anchors.get(pos).update(set, entry);
        }
        for (SpeakerEntry entry : incoming.values()) {
            if (!stopped && !entry.muted() && entry.volumePercent() > 0
                    && PlaybackConcurrency.client().sweepAll() < Config.maxConcurrent()) start(set, entry, initialUpdate);
        }
        initialUpdate = false;
    }
    private void start(SpeakerSetPayload set, SpeakerEntry entry, boolean initial) {
        final SpeakerEmitterAnchor anchor = new SpeakerEmitterAnchor(sourceAnchor, set, entry);
        final VanillaSpeakerSoundInstance voice = initial
                ? new VanillaSpeakerSoundInstance(soundEventId, offsetMs, anchor, ignored -> {}, () -> {})
                : new VanillaSpeakerSoundInstance(soundEventId,
                        () -> offsetMs + Math.max(0L, playbackClockMs.getAsLong() - receivedPlaybackMs), anchor,
                        ignored -> {}, () -> {});
        anchors.put(entry.pos(), anchor); voices.put(entry.pos(), voice);
        if (!SoundEngineAcceptance.start(voice, voice::stopAndRelease, ignored -> voice.stopAndRelease())) {
            voices.remove(entry.pos());
            anchors.remove(entry.pos()).remove();
        }
    }
    @Override public boolean isVoiceStopped() {
        if (stopped || source.isVoiceStopped()) { stopAndRelease(); return true; }
        for (BlockPos pos : new ArrayList<>(voices.keySet())) {
            if (voices.get(pos).isVoiceStopped()) {
                voices.remove(pos);
                final SpeakerEmitterAnchor anchor = anchors.remove(pos);
                if (anchor != null) anchor.remove();
            }
        }
        return false;
    }
    @Override public int voiceCount() { return stopped ? 0 : 1 + voices.size(); }
    @Override public void setDirectional(boolean value) { source.setDirectional(value); voices.values().forEach(v -> v.setDirectional(value)); }
    @Override public void setRangeBlocks(int value) { source.setRangeBlocks(value); }
    @Override public void setVolumePercent(int value) { source.setVolumePercent(value); }
    @Override public void stopAndRelease() {
        if (stopped) return; stopped = true;
        anchors.values().forEach(SpeakerEmitterAnchor::remove); anchors.clear();
        voices.values().forEach(VanillaSpeakerSoundInstance::stopAndRelease); voices.clear(); source.stopAndRelease();
    }
}
