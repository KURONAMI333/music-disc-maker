package com.kuronami.musicdiscmaker.client.audio;

import java.util.Objects;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

/** Immutable request passed to one previously selected optional provider. */
public record PlaybackSessionRequest(
        PlaybackSessionId id,
        PlaybackSourceContext source,
        CustomTrackData track,
        long startOffsetMs,
        long requestedAtMs) {
    public PlaybackSessionRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(track, "track");
        if (startOffsetMs < 0) throw new IllegalArgumentException("startOffsetMs must be non-negative");
    }
}
