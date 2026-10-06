package com.kuronami.musicdiscmaker.client.audio;

import java.nio.ByteBuffer;
import java.util.Objects;

/** Renderer-neutral, immutable RGBA frame snapshot from an optional playback session. */
public record PlaybackVideoFrame(int width, int height, long presentationTimeUs, ByteBuffer rgba) {
    public PlaybackVideoFrame {
        if (width <= 0 || height <= 0 || presentationTimeUs < 0)
            throw new IllegalArgumentException("invalid video frame geometry or timestamp");
        Objects.requireNonNull(rgba, "rgba");
        if (rgba.remaining() != Math.multiplyExact(Math.multiplyExact(width, height), 4))
            throw new IllegalArgumentException("RGBA buffer size does not match frame geometry");
        ByteBuffer copy = ByteBuffer.allocate(rgba.remaining());
        copy.put(rgba.duplicate()).flip();
        rgba = copy.asReadOnlyBuffer();
    }
    @Override public ByteBuffer rgba() { return rgba.asReadOnlyBuffer(); }
}
