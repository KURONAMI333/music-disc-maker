package com.kuronami.musicdiscmaker.client.audio;

/** Duck interface added to the native Channel mixin for sound-engine-thread cursor sampling. */
public interface NativeCursorAccess {
    void mdm$publishPlaybackPosition();
}
