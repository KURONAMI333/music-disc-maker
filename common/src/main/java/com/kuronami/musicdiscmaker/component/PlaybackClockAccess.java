package com.kuronami.musicdiscmaker.component;

/** Local playback time, excluding a paused singleplayer game's time. */
public interface PlaybackClockAccess {
    long mdm$playbackTimeMs();
}
