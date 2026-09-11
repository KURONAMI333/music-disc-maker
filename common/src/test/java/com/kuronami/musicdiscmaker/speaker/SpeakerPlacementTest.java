package com.kuronami.musicdiscmaker.speaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class SpeakerPlacementTest {
    @Test void floorPlacementKeepsCardinalsAndUsesDiagonals() {
        assertEquals(new SpeakerPlacement.Orientation(SpeakerPlacement.Facing.NORTH, SpeakerPlacement.Turn.CENTER), SpeakerPlacement.floorOrCeiling(0));
        assertEquals(new SpeakerPlacement.Orientation(SpeakerPlacement.Facing.NORTH, SpeakerPlacement.Turn.RIGHT), SpeakerPlacement.floorOrCeiling(45));
        assertEquals(new SpeakerPlacement.Orientation(SpeakerPlacement.Facing.SOUTH, SpeakerPlacement.Turn.LEFT), SpeakerPlacement.floorOrCeiling(135));
        assertEquals(new SpeakerPlacement.Orientation(SpeakerPlacement.Facing.SOUTH, SpeakerPlacement.Turn.RIGHT), SpeakerPlacement.floorOrCeiling(225));
        assertEquals(new SpeakerPlacement.Orientation(SpeakerPlacement.Facing.NORTH, SpeakerPlacement.Turn.LEFT), SpeakerPlacement.floorOrCeiling(315));
    }
    @Test void wallPlacementRetainsSupportAndChoosesNearestHornBearing() {
        assertEquals(SpeakerPlacement.Turn.RIGHT, SpeakerPlacement.wallTurn(SpeakerPlacement.Facing.NORTH, 45));
        assertEquals(SpeakerPlacement.Turn.LEFT, SpeakerPlacement.wallTurn(SpeakerPlacement.Facing.NORTH, 315));
        assertEquals(SpeakerPlacement.Turn.CENTER, SpeakerPlacement.wallTurn(SpeakerPlacement.Facing.NORTH, 0));
    }
}
