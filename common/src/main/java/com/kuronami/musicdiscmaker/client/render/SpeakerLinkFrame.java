package com.kuronami.musicdiscmaker.client.render;

import java.util.ArrayList;
import java.util.List;

/** Raised rails, inset at adjoining blocks so exposed faces retain their border. */
public final class SpeakerLinkFrame {
    public static final float GAP = 0.006F;
    public static final float WIDTH = 0.045F;

    public record Rail(float x0, float y0, float z0, float x1, float y1, float z1) {}

    private SpeakerLinkFrame() {}

    public static List<Rail> rails(boolean west, boolean east, boolean down, boolean up,
            boolean north, boolean south) {
        final float x0 = low(west), x1 = high(east);
        final float y0 = low(down), y1 = high(up);
        final float z0 = low(north), z1 = high(south);
        final List<Rail> rails = new ArrayList<>(12);
        // X rails own the corners; Y/Z rails terminate at their inner faces.
        for (float y : new float[] {y0, y1 - WIDTH}) {
            for (float z : new float[] {z0, z1 - WIDTH}) {
                rails.add(new Rail(x0, y, z, x1, y + WIDTH, z + WIDTH));
            }
        }
        for (float x : new float[] {x0, x1 - WIDTH}) {
            for (float z : new float[] {z0, z1 - WIDTH}) {
                rails.add(new Rail(x, y0 + WIDTH, z, x + WIDTH, y1 - WIDTH, z + WIDTH));
            }
        }
        for (float x : new float[] {x0, x1 - WIDTH}) {
            for (float y : new float[] {y0, y1 - WIDTH}) {
                rails.add(new Rail(x, y, z0 + WIDTH, x + WIDTH, y + WIDTH, z1 - WIDTH));
            }
        }
        return rails;
    }

    private static float low(boolean adjoining) { return adjoining ? GAP : -GAP - WIDTH; }
    private static float high(boolean adjoining) { return adjoining ? 1 - GAP : 1 + GAP + WIDTH; }
}
