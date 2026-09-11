package com.kuronami.musicdiscmaker.client.audio;

import java.util.HashMap;
import java.util.Map;
import com.kuronami.musicdiscmaker.network.PlayVanillaDiscPayload;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Main-thread ownership of resource-pack record voices; one voice per Golden source. */
public final class VanillaSpeakerPlayback {
    private record Playing(PlayVanillaDiscPayload payload, long receivedPlaybackMs, PlaybackVoice voice) {}
    private static final Map<BlockPos, MultiSpeakerAnchor> ANCHORS = new HashMap<>();
    private static final Map<BlockPos, Playing> VOICES = new HashMap<>();
    static { PlaybackConcurrency.client().registerCounter(VanillaSpeakerPlayback::sweep); }
    private VanillaSpeakerPlayback() {}

    private static int sweep() {
        VOICES.entrySet().removeIf(entry -> entry.getValue().voice().isVoiceStopped());
        return VOICES.values().stream().mapToInt(playing -> playing.voice().voiceCount()).sum();
    }

    public static void update(SpeakerSetPayload payload) {
        ANCHORS.compute(payload.sourcePos().immutable(), (pos, previous) -> {
            if (previous == null) return new MultiSpeakerAnchor(payload);
            previous.update(payload);
            return previous;
        });
        final Playing playing = VOICES.get(payload.sourcePos().immutable());
        if (playing != null && playing.voice() instanceof VanillaSpeakerVoiceGroup group) group.update(payload);
    }

    public static void play(PlayVanillaDiscPayload payload) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        final BlockPos pos = payload.jukeboxPos().immutable();
        final Playing old = VOICES.get(pos);
        if (old != null && payload.generation() < old.payload().generation()) return;
        stopVanilla(pos);
        final long now = playbackClockMs();
        if (old != null && !old.voice().isVoiceStopped() && old.payload().track().equals(payload.track())
                && old.payload().generation() == payload.generation()) {
            final long expected = old.payload().startOffsetMs() + Math.max(0L, now - old.receivedPlaybackMs());
            if (Math.abs(expected - payload.startOffsetMs()) <= 1000L) return;
        }
        if (old != null) {
            old.voice().stopAndRelease();
            VOICES.remove(pos);
        }
        final int limit = com.kuronami.musicdiscmaker.Config.maxConcurrent();
        if (PlaybackConcurrency.client().sweepAll() >= limit) {
            PlaybackFailureReport.report((com.kuronami.musicdiscmaker.component.CustomTrackData) null,
                    PlaybackFailure.concurrentLimit(limit));
            return;
        }
        final MultiSpeakerAnchor cached = ANCHORS.computeIfAbsent(pos, ignored -> new MultiSpeakerAnchor(
                new SpeakerSetPayload(pos, payload.rangeBlocks(), payload.volumePercent(), payload.directional(), java.util.List.of())));
        final DiscAnchor moving = ClientPlaybackManager.shipAnchor(pos);
        final DiscAnchor source = moving != null ? moving : new StaticAnchor(pos);
        final SpeakerSetPayload set = cached.payload();
        final GoldenEmitterAnchor sourceAnchor = new GoldenEmitterAnchor(source, set);
        final VanillaSpeakerSoundInstance sourceVoice = new VanillaSpeakerSoundInstance(
                payload.track().soundEventId(), payload.startOffsetMs(), sourceAnchor);
        if (SoundEngineAcceptance.start(sourceVoice, sourceVoice::stopAndRelease,
                failure -> PlaybackFailureReport.report(
                        (com.kuronami.musicdiscmaker.component.CustomTrackData) null, failure))) {
            final VanillaSpeakerVoiceGroup voice = new VanillaSpeakerVoiceGroup(sourceVoice, sourceAnchor,
                    payload.track().soundEventId(), payload.startOffsetMs(), now, VanillaSpeakerPlayback::playbackClockMs);
            VOICES.put(pos, new Playing(payload, now, voice));
            // The master is accepted before any child can prefill/read, keeping the initial timeline anchored.
            voice.update(set);
        }
    }

    /** Use exactly the same clock for duplicate suppression and speakers added during a playing record. */
    private static long playbackClockMs() {
        final var server = Minecraft.getInstance().getSingleplayerServer();
        return server instanceof com.kuronami.musicdiscmaker.component.PlaybackClockAccess clock
                ? clock.mdm$playbackTimeMs()
                : com.kuronami.musicdiscmaker.component.PauseAwarePlaybackClock.realTimeMs();
    }

    public static void stopVanilla(BlockPos pos) {
        final Minecraft mc = Minecraft.getInstance();
        //? if >=1.21.2 {
        if (mc.level instanceof ClientLevelAudioBridge bridge) {
            bridge.mdm$jukeboxAudio().mdm$stopVanillaRecord(pos);
        }
        //?} else {
        /*if (mc.levelRenderer instanceof VanillaJukeboxAudioAccess access) access.mdm$stopVanillaRecord(pos);
        *///?}
    }

    public static void stop(BlockPos pos) {
        final Playing old = VOICES.remove(pos);
        try {
            if (old != null) old.voice().stopAndRelease();
        } finally {
            ANCHORS.remove(pos);
        }
    }

    public static void stopAll() {
        for (Playing playing : VOICES.values()) {
            try {
                playing.voice().stopAndRelease();
            } catch (RuntimeException failure) {
                com.kuronami.musicdiscmaker.MusicDiscMaker.LOGGER.warn("Could not release vanilla record voice", failure);
            }
        }
        VOICES.clear();
        ANCHORS.clear();
    }
}
