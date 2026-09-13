package com.kuronami.musicdiscmaker.client.audio;

/** フラット再生の距離境界。範囲外でもストリームを閉じず、音量だけを変える。 */
final class FlatPlaybackGate {
    private boolean initialized;
    private boolean audible = true;
    private float gain = 1.0F;

    float update(boolean directional, double distance, int range, boolean tick) {
        final boolean next = directional || distance <= range + (audible && initialized ? 1.0D : 0.0D);
        audible = next;
        final float target = next ? 1.0F : 0.0F;
        if (!initialized) {
            gain = target;
            initialized = true;
        } else if (tick) {
            final float step = 1.0F / 15.0F;
            gain = gain < target ? Math.min(target, gain + step) : Math.max(target, gain - step);
        }
        return gain;
    }
}
