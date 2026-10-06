package com.kuronami.musicdiscmaker.client.audio;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import java.util.concurrent.CompletableFuture;

/** One decoded media session whose PCM and video frames share a presentation timeline. */
public interface PlaybackMediaSession extends AutoCloseable {
    IAudioSource audioSource();

    /** Latest frame at or before the session's presentation clock, or {@code null} when unavailable. */
    PlaybackVideoFrame latestVideoFrame();

    /** Current presentation time in microseconds. Must not perform blocking I/O or decode work. */
    long presentationTimeUs();

    /** Actual OpenAL cursor update from MDM. The provider maps frames from source start to media PTS. */
    default void reportAudioOutputPosition(long playedFrames, long sampleRate, long sampledAtNanos) {}

    /** Completes only after native/resource cleanup is positively acknowledged. */
    default CompletableFuture<Void> cleanupAcknowledged() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("session has no cleanup acknowledgement"));
    }

    /** Stop worker activity, release native resources, and discard buffered PCM/video. Idempotent. */
    @Override void close();
}
