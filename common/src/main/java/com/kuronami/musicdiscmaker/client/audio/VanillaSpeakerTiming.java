package com.kuronami.musicdiscmaker.client.audio;

/** Minecraft-independent time conversion used by resource-pack speaker playback. */
final class VanillaSpeakerTiming {
    private VanillaSpeakerTiming() {
    }

    static long sourceOffsetMs(long elapsedMs, float effectivePitch) {
        if (elapsedMs <= 0L) return 0L;
        final double scaled = elapsedMs * (double) effectivePitch;
        return !Double.isFinite(scaled) || scaled >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) Math.floor(scaled);
    }
}
