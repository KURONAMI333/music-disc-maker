package com.kuronami.musicdiscmaker.speaker;

/** Pure horn directivity for 12 mounting orientations and three horizontal horn turns. */
public final class SpeakerCone {
    public static final double BACK_GAIN = 0.65D;
    private static final double TILT = Math.sin(Math.toRadians(22.5D));
    private static final double HORIZONTAL = Math.cos(Math.toRadians(22.5D));
    private SpeakerCone() {}

    /** One client tick of smoothing; the final response time still needs listening tests. */
    public static double approach(double current, double target) {
        if (!Double.isFinite(current)) current = 1.0D;
        if (!Double.isFinite(target)) target = 1.0D;
        current = Math.max(BACK_GAIN, Math.min(1.0D, current));
        target = Math.max(BACK_GAIN, Math.min(1.0D, target));
        final double next = current + (target - current) * 0.25D;
        return Math.abs(next - target) < 1.0E-4D ? target : next;
    }

    /** orientation is base 0..11 plus turn band (center=0, left=12, right=24). */
    public static double gain(int orientation, double sx, double sy, double sz, double earX, double earY, double earZ) {
        if (orientation < 0 || orientation >= 36 || !finite(sx, sy, sz, earX, earY, earZ)) return 1.0D;
        final double dx = earX - sx, dy = earY - sy, dz = earZ - sz;
        final double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(length > 0.0D) || !Double.isFinite(length)) return 1.0D;
        final double[] axis = axis(orientation);
        final double cosine = Math.max(-1.0D, Math.min(1.0D, (axis[0] * dx + axis[1] * dy + axis[2] * dz) / length));
        final double t = (cosine + 1.0D) * 0.5D;
        final double smooth = t * t * (3.0D - 2.0D * t);
        return BACK_GAIN + (1.0D - BACK_GAIN) * smooth;
    }

    static double[] axis(int orientation) {
        final int base = orientation % 12;
        final int horizontal = base & 3;
        final double x = horizontal == 1 ? 1 : horizontal == 3 ? -1 : 0;
        final double z = horizontal == 2 ? 1 : horizontal == 0 ? -1 : 0;
        final int face = base / 4;
        final double baseX = face == 1 ? x : x * HORIZONTAL;
        final double baseY = face == 0 ? TILT : face == 2 ? -TILT : 0.0D;
        final double baseZ = face == 1 ? z : z * HORIZONTAL;
        final int turn = orientation / 12;
        if (turn == 0) {
            return new double[] { baseX, baseY, baseZ };
        }
        final double angle = Math.toRadians(turn == 1 ? 45.0D : -45.0D);
        final double sin = Math.sin(angle);
        final double cos = Math.cos(angle);
        return new double[] { baseX * cos + baseZ * sin, baseY, -baseX * sin + baseZ * cos };
    }
    private static boolean finite(double... values) { for (double v : values) if (!Double.isFinite(v)) return false; return true; }
}
