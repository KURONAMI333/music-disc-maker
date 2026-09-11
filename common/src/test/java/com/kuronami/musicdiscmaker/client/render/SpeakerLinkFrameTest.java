package com.kuronami.musicdiscmaker.client.render;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SpeakerLinkFrameTest {
    @Test
    void floorKeepsFrontBottomRailAboveGroundAndOutsideGoldenFace() {
        final var rails = SpeakerLinkFrame.rails(false, false, true, false, false, false);
        final var frontBottom = rails.get(0);
        assertTrue(frontBottom.y0() > 0, "bottom border must clear the floor");
        assertTrue(frontBottom.z1() < 0, "border must remain in front of the Golden face");
        assertEquals(SpeakerLinkFrame.WIDTH, frontBottom.y1() - frontBottom.y0(), 0.000001F);
    }

    @Test
    void everyNeighborCombinationPreservesTwelveSolidNonOverlappingRails() {
        for (int mask = 0; mask < 64; mask++) {
            final boolean w = (mask & 1) != 0, e = (mask & 2) != 0;
            final boolean d = (mask & 4) != 0, u = (mask & 8) != 0;
            final boolean n = (mask & 16) != 0, s = (mask & 32) != 0;
            final var rails = SpeakerLinkFrame.rails(w, e, d, u, n, s);
            assertEquals(12, rails.size());
            for (var r : rails) {
                assertTrue(r.x1() > r.x0() && r.y1() > r.y0() && r.z1() > r.z0());
                if (w) assertTrue(r.x0() >= 0);
                if (e) assertTrue(r.x1() <= 1);
                if (d) assertTrue(r.y0() >= 0);
                if (u) assertTrue(r.y1() <= 1);
                if (n) assertTrue(r.z0() >= 0);
                if (s) assertTrue(r.z1() <= 1);
            }
            for (int i = 0; i < rails.size(); i++) {
                var a = rails.get(i);
                for (int j = i + 1; j < rails.size(); j++) {
                    var b = rails.get(j);
                    float dx = Math.min(a.x1(), b.x1()) - Math.max(a.x0(), b.x0());
                    float dy = Math.min(a.y1(), b.y1()) - Math.max(a.y0(), b.y0());
                    float dz = Math.min(a.z1(), b.z1()) - Math.max(a.z0(), b.z0());
                    assertFalse(dx > 0.000001F && dy > 0.000001F && dz > 0.000001F,
                            "rails must meet without overlapping volume, mask=" + mask);
                }
            }
        }
    }
}
