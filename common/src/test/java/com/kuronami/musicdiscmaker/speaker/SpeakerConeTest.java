package com.kuronami.musicdiscmaker.speaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import java.util.List;

class SpeakerConeTest {
    @Test void axesMatchTheNativeModelsRatherThanTheOldVerticalAngleDescription() {
        final double horizontal = 0.9238795325D;
        final double vertical = 0.3826834324D;
        for (int facing = 0; facing < 4; facing++) {
            final double x = new double[] {0, 1, 0, -1}[facing];
            final double z = new double[] {-1, 0, 1, 0}[facing];
            for (int face = 0; face < 3; face++) {
                final double[] axis = SpeakerCone.axis(face * 4 + facing);
                assertEquals(x * (face == 1 ? 1 : horizontal), axis[0], 1e-9);
                assertEquals(face == 0 ? vertical : face == 2 ? -vertical : 0, axis[1], 1e-9);
                assertEquals(z * (face == 1 ? 1 : horizontal), axis[2], 1e-9);
            }
        }
    }

    @Test void sideIsIntermediateAndChangesApproachWithoutOvershoot() {
        assertEquals(0.825D, SpeakerCone.gain(4, 0, 0, 0, 1, 0, 0), 1e-9);
        double current = 1.0D;
        for (int tick = 0; tick < 40; tick++) {
            final double next = SpeakerCone.approach(current, SpeakerCone.BACK_GAIN);
            assertTrue(next <= current && next >= SpeakerCone.BACK_GAIN);
            current = next;
        }
        assertEquals(SpeakerCone.BACK_GAIN, current, 1e-9);
        assertTrue(SpeakerCone.approach(current, 1.0D) > current);
        assertTrue(SpeakerCone.approach(current, 1.0D) < 1.0D);
    }

    @Test void transitionInterpolatesTheConeOnceAndKeepsTheConfiguredVolume() {
        final SpeakerTransition transition = new SpeakerTransition();
        final var left = new SpeakerSelection.Candidate("left", 0, 0, 0, 64, 100, false, 0.65D);
        final var right = new SpeakerSelection.Candidate("right", 10, 0, 0, 64, 100, false, 1.0D);
        final var candidates = List.of(left, right);
        var frame = transition.advance(new SpeakerSelection.Listener(0, 0, 0), candidates, false);
        assertEquals(100, frame.volumePercent());
        assertEquals(0.65D, frame.gainMultiplier(), 1e-9);
        for (int tick = 0; tick < SpeakerTransition.TRANSITION_TICKS / 2; tick++) {
            frame = transition.advance(new SpeakerSelection.Listener(10, 0, 0), candidates, false);
        }
        assertEquals(0.825D, frame.gainMultiplier(), 1e-9);
        for (int tick = 0; tick < SpeakerTransition.TRANSITION_TICKS / 2; tick++) {
            frame = transition.advance(new SpeakerSelection.Listener(10, 0, 0), candidates, false);
        }
        assertEquals(100, frame.volumePercent());
        assertEquals(1.0D, frame.gainMultiplier(), 1e-9);
    }

    @Test void allOrientationsKeepAudibilityAndHaveFrontBackOrder() {
        for (int orientation = 0; orientation < 36; orientation++) {
            double[] axis = SpeakerCone.axis(orientation);
            double front = SpeakerCone.gain(orientation, 0, 0, 0, axis[0], axis[1], axis[2]);
            double back = SpeakerCone.gain(orientation, 0, 0, 0, -axis[0], -axis[1], -axis[2]);
            assertEquals(1.0D, front, 1.0E-9);
            assertEquals(SpeakerCone.BACK_GAIN, back, 1.0E-9);
            assertTrue(back > 0.0D);
        }
    }

    @Test void leftAndRightTurnsRotateTheAcousticAxisByFortyFiveDegrees() {
        final double diagonal = Math.sqrt(0.5D);
        final double[] centerNorthWall = SpeakerCone.axis(4);
        final double[] leftNorthWall = SpeakerCone.axis(16);
        final double[] rightNorthWall = SpeakerCone.axis(28);
        assertEquals(0.0D, centerNorthWall[0], 1e-9);
        assertEquals(-1.0D, centerNorthWall[2], 1e-9);
        assertEquals(-diagonal, leftNorthWall[0], 1e-9);
        assertEquals(-diagonal, leftNorthWall[2], 1e-9);
        assertEquals(diagonal, rightNorthWall[0], 1e-9);
        assertEquals(-diagonal, rightNorthWall[2], 1e-9);
    }

    @Test void samePointAndInvalidInputAreSafe() {
        assertEquals(1.0D, SpeakerCone.gain(0, 1, 2, 3, 1, 2, 3));
        assertEquals(1.0D, SpeakerCone.gain(0, Double.NaN, 0, 0, 0, 0, 1));
    }
}
