package com.kuronami.musicdiscmaker.client.audio;

import java.util.Objects;

/** Client-local identity for one playback source generation. */
public record PlaybackSessionId(String sourceId, long generation, int localGeneration) {
    public PlaybackSessionId {
        Objects.requireNonNull(sourceId, "sourceId");
        if (sourceId.isBlank()) throw new IllegalArgumentException("sourceId must not be blank");
        if (generation < 0L) throw new IllegalArgumentException("generation must be non-negative");
    }
}
