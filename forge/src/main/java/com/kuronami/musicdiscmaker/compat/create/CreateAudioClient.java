package com.kuronami.musicdiscmaker.compat.create;

import com.kuronami.musicdiscmaker.network.ContraptionPlayDiscPayload;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler;
import com.kuronami.musicdiscmaker.client.audio.CompatPlayback;
import com.kuronami.musicdiscmaker.client.audio.DiscSoundInstance;
import com.kuronami.musicdiscmaker.client.audio.PlaybackConcurrency;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailure;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailureReport;
import com.kuronami.musicdiscmaker.client.audio.SoundEngineAcceptance;
import com.kuronami.musicdiscmaker.client.audio.SourcePlaybackOwnership;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.network.UrlBlockedException;
import com.kuronami.musicdiscmaker.network.UrlGuard;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Createの再生を通常Goldenと同じsource所有権へ接続する。mapの操作はclient main thread限定。 */
public final class CreateAudioClient {
    private record ActorKey(int entityId, long packedLocalPos) {}
    private static final class Slot {
        ContraptionPlayDiscPayload latest;
        long lastReceivedMs = audioTimeMs();
        final SourcePlaybackOwnership.Lease<Object> lease;
        com.kuronami.musicdiscmaker.client.audio.PlaybackVoice voice;
        com.kuronami.musicdiscmaker.client.audio.MultiSpeakerAnchor speakerAnchor;
        Slot(ContraptionPlayDiscPayload payload, SourcePlaybackOwnership.Lease<Object> lease) {
            this.latest = payload;
            this.lease = lease;
        }
    }
    private static final Map<ActorKey, Slot> SLOTS = new HashMap<>();
    private record Announcement(com.kuronami.musicdiscmaker.network.PlaybackSourceStamp identity, long receivedMs) {}
    private static final Map<ActorKey, Announcement> ANNOUNCEMENTS = new HashMap<>();
    private static final com.kuronami.musicdiscmaker.client.audio.LivePlaybackRegistry<ActorKey> RETRIES =
            new com.kuronami.musicdiscmaker.client.audio.LivePlaybackRegistry<>(CreateAudioClient::audioTimeMs);
    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-create-playback");
        thread.setDaemon(true);
        return thread;
    });
    static { PlaybackConcurrency.client().registerCounter(CreateAudioClient::countPlaying); }
    private CreateAudioClient() {}

    private static void sweepExpired() {
        final long now = audioTimeMs();
        for (var entry : java.util.List.copyOf(ANNOUNCEMENTS.entrySet())) {
            if (now - entry.getValue().receivedMs() <= 10000L) continue;
            final var identity = entry.getValue().identity();
            if (identity.sourceId() == null || !ClientPlaybackHandler.SOURCE_OWNERS.stop(
                    identity.sourceId(), identity.generation(), entry.getKey())) stop(entry.getKey());
        }
    }
    private static int countPlaying() {
        sweepExpired();
        SLOTS.entrySet().removeIf(entry -> entry.getValue().voice != null && entry.getValue().voice.isVoiceStopped());
        return (int) SLOTS.values().stream().filter(slot -> slot.voice != null && !slot.voice.isVoiceStopped()).count();
    }
    private static void stop(ActorKey key) {
        ANNOUNCEMENTS.remove(key);
        RETRIES.stop(key);
        dropSlot(key);
    }
    private static void dropSlot(ActorKey key) {
        final Slot old = SLOTS.remove(key);
        if (old != null && old.voice != null) old.voice.stopAndRelease();
    }
    public static void stopAll() {
        for (ActorKey key : java.util.List.copyOf(SLOTS.keySet())) stop(key);
        RETRIES.stopAll();
        ANNOUNCEMENTS.clear();
    }
    private static String retryToken(ContraptionPlayDiscPayload payload) {
        return payload.identity().sourceId() + ":" + payload.identity().generation() + ":"
                + (payload.vanilla() == null ? "custom:" + payload.track().url()
                        : "vanilla:" + payload.vanilla().soundEventId());
    }
    private static void failed(ActorKey key, Slot slot, PlaybackFailure failure) {
        if (!SLOTS.remove(key, slot)) return;
        final var payload = slot.latest;
        final String token = retryToken(payload);
        RETRIES.loadFinished(key, token);
        if (RETRIES.loadFailed(key, token, failure)) {
            if (payload.vanilla() == null) PlaybackFailureReport.report(payload.track(), failure);
            else PlaybackFailureReport.report(payload.vanilla().soundEventId(), null, failure);
        }
    }
    private static boolean current(ActorKey key, Slot slot) {
        return SLOTS.get(key) == slot && audioTimeMs() - slot.lastReceivedMs <= 10000L
                && (slot.lease == null || ClientPlaybackHandler.SOURCE_OWNERS.isCurrent(slot.lease));
    }

    private static long audioTimeMs() {
        final var server = Minecraft.getInstance().getSingleplayerServer();
        return server instanceof com.kuronami.musicdiscmaker.component.PlaybackClockAccess clock
                ? clock.mdm$playbackTimeMs()
                : com.kuronami.musicdiscmaker.component.PauseAwarePlaybackClock.realTimeMs();
    }

    private static com.kuronami.musicdiscmaker.network.SpeakerSetPayload speakerSettings(ContraptionPlayDiscPayload payload) {
        return new com.kuronami.musicdiscmaker.network.SpeakerSetPayload(payload.localPos(), payload.rangeBlocks(),
                payload.volumePercent(), payload.directional(), payload.receivers().speakers(), payload.identity());
    }

    public static void play(ContraptionPlayDiscPayload payload) {
        if (ClientPlaybackHandler.isProtocolMismatch() || payload.track() == null) return;
        final var world = Minecraft.getInstance().level;
        if (world == null) return;
        sweepExpired();
        final ActorKey key = new ActorKey(payload.contraptionEntityId(), payload.localPos().asLong());
        final var identity = payload.identity();
        if (payload.isStop()) {
            if (identity.sourceId() == null) stop(key);
            else ClientPlaybackHandler.SOURCE_OWNERS.stop(identity.sourceId(), identity.generation(), key);
            return;
        }
        final SourcePlaybackOwnership.Lease<Object> lease = identity.sourceId() == null ? null
                : ClientPlaybackHandler.SOURCE_OWNERS.claim(identity.sourceId(), identity.generation(), key, () -> stop(key));
        if (identity.sourceId() != null && lease == null) return;
        ANNOUNCEMENTS.put(key, new Announcement(identity, audioTimeMs()));
        final Slot previous = SLOTS.get(key);
        if (previous != null && previous.lease == lease && previous.latest.track().equals(payload.track())
                && java.util.Objects.equals(previous.latest.vanilla(), payload.vanilla())
                && previous.latest.identity().generation() == identity.generation()
                && (previous.voice == null || !previous.voice.isVoiceStopped())) {
            previous.latest = payload;
            previous.lastReceivedMs = audioTimeMs();
            if (previous.voice instanceof DiscSoundInstance customVoice) {
                customVoice.setRangeBlocks(payload.rangeBlocks());
                customVoice.setVolumePercent(payload.volumePercent());
                customVoice.setDirectional(payload.directional());
            }
            if (previous.speakerAnchor != null) previous.speakerAnchor.update(speakerSettings(payload));
            return; // 同じ再生の周期更新は読み込み中も追い越さない。
        }
        final String token = retryToken(payload);
        if (RETRIES.request(key, token, payload.rangeBlocks(), payload.volumePercent(), payload.directional())
                == com.kuronami.musicdiscmaker.client.audio.LivePlaybackRegistry.Decision.SKIP) return;
        dropSlot(key);
        final Slot slot = new Slot(payload, lease);
        SLOTS.put(key, slot);
        slot.speakerAnchor = new com.kuronami.musicdiscmaker.client.audio.MultiSpeakerAnchor(speakerSettings(payload),
                new com.kuronami.musicdiscmaker.client.audio.DiscAnchor() {
                    public boolean isValid() { return Minecraft.getInstance().level == world && current(key, slot); }
                    public net.minecraft.world.phys.Vec3 worldPos(float partialTicks) {
                        final var entity = world.getEntity(slot.latest.contraptionEntityId());
                        if (entity instanceof AbstractContraptionEntity contraption && !contraption.isRemoved()) {
                            return contraption.toGlobalVector(net.minecraft.world.phys.Vec3.atCenterOf(slot.latest.localPos()), partialTicks);
                        }
                        return slot.latest.receivers().sourcePosition();
                    }
                });
        if (payload.vanilla() != null) {
            final int limit = com.kuronami.musicdiscmaker.Config.maxConcurrent();
            if (PlaybackConcurrency.client().sweepAll() >= limit) {
                failed(key, slot, PlaybackFailure.concurrentLimit(limit));
                return;
            }
            final var voice = new com.kuronami.musicdiscmaker.client.audio.VanillaSpeakerSoundInstance(
                    payload.vanilla().soundEventId(), payload.startOffsetMs(), slot.speakerAnchor,
                    failure -> { if (current(key, slot)) failed(key, slot, failure); },
                    () -> { if (current(key, slot)) RETRIES.playing(key); });
            slot.voice = voice;
            if (!SoundEngineAcceptance.start(voice, voice::stopAndRelease,
                    rejected -> failed(key, slot, rejected))) {
                return;
            }
            RETRIES.loadFinished(key, token);
            return;
        }
        POOL.submit(() -> {
            IAudioSource source = null;
            PlaybackFailure failure = null;
            try {
                UrlGuard.enforce(payload.track().url());
                final var result = LoaderHolder.get().openStreamDetailed(payload.track().url(), payload.startOffsetMs());
                source = result.source();
                failure = result.isOk() ? null : PlaybackFailure.ofReason(result.reason(), result.detail());
            } catch (UrlBlockedException blocked) {
                failure = PlaybackFailure.blocked(blocked.reason());
            } catch (Throwable problem) {
                failure = PlaybackFailure.thrown(problem);
            }
            final IAudioSource resolved = source;
            final PlaybackFailure reported = failure;
            Minecraft.getInstance().execute(() -> {
                if (!current(key, slot) || Minecraft.getInstance().level != world) {
                    if (SLOTS.remove(key, slot)) RETRIES.loadFinished(key, token);
                    if (resolved != null) resolved.close();
                    return;
                }
                if (resolved == null) {
                    failed(key, slot, reported == null ? PlaybackFailure.streamUnavailable() : reported);
                    return;
                }
                final int limit = com.kuronami.musicdiscmaker.Config.maxConcurrent();
                if (PlaybackConcurrency.client().sweepAll() >= limit) {
                    resolved.close();
                    failed(key, slot, PlaybackFailure.concurrentLimit(limit));
                    return;
                }
                final var latest = slot.latest;
                final DiscSoundInstance voice = CompatPlayback.wired(slot.speakerAnchor,
                        resolved, latest.rangeBlocks(), latest.volumePercent(), latest.directional(), latest.track());
                slot.voice = voice;
                if (!SoundEngineAcceptance.start(voice, voice::stopAndRelease,
                        rejected -> failed(key, slot, rejected))) {
                    return;
                }
                RETRIES.loadFinished(key, token);
                RETRIES.playing(key);
                final var track = latest.track();
                final String desc = track.author() != null && !track.author().isBlank()
                        ? track.author() + " - " + track.title() : track.title();
                if (desc != null && !desc.isBlank()) Minecraft.getInstance().gui.setNowPlaying(Component.literal(desc));
            });
        });
    }
}
