package com.kuronami.musicdiscmaker.client.audio;

import com.kuronami.musicdiscmaker.component.CustomTrackData;

/** Optional provider registered by a client addon. Implementations must be safe when absent. */
public interface PlaybackSessionProvider {
    /** Cheap, side-effect-free and source-aware choice shared by prefetch and playback. */
    default boolean selects(PlaybackSourceContext source, CustomTrackData track) { return false; }

    /**
     * Called on an MDM playback worker only for a matching selection. Return null to decline.
     * If open acquired resources and cleanup is not yet acknowledged, throw
     * {@link PlaybackSessionRegistry.CleanupIncompleteException} with the cleanup acknowledgement;
     * the registry retains its admission slot and suppresses regular-audio fallback until it completes.
     */
    PlaybackMediaSession open(PlaybackSessionRequest request) throws Exception;
}
