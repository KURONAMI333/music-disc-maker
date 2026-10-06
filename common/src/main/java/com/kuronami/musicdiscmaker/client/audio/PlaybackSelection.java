package com.kuronami.musicdiscmaker.client.audio;

import java.util.Objects;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

/** Immutable, source-aware provider choice captured before a loader is submitted. */
public final class PlaybackSelection {
    private final PlaybackSessionProvider provider;
    private final PlaybackSourceContext source;
    private final String trackKey;

    PlaybackSelection(PlaybackSessionProvider provider, PlaybackSourceContext source, String trackKey) {
        this.provider = provider;
        this.source = Objects.requireNonNull(source, "source");
        this.trackKey = trackKey;
    }
    static PlaybackSelection none(PlaybackSourceContext source, String trackKey) {
        return new PlaybackSelection(null, source, trackKey);
    }
    PlaybackSessionProvider provider() { return provider; }
    public boolean selected() { return provider != null; }
    public boolean allowsRegularPrefetch() { return provider == null; }
    public boolean matches(PlaybackSourceContext candidate, CustomTrackData track) {
        return source.equals(candidate) && track != null && Objects.equals(trackKey, track.sourceKey());
    }
}
