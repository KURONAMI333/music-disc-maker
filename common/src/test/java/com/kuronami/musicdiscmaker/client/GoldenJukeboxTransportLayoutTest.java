package com.kuronami.musicdiscmaker.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GoldenJukeboxTransportLayoutTest {

    @Test
    void fiveButtonsAreCenteredAtEqualIntervals() {
        assertEquals(30, GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.SHUFFLE));
        assertEquals(54, GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.PREVIOUS));
        assertEquals(78, GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.PLAY_PAUSE));
        assertEquals(102, GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.NEXT));
        assertEquals(126, GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.REPEAT));

        final int firstCenter = GoldenJukeboxTransportLayout.buttonX(0)
                + GoldenJukeboxTransportLayout.BUTTON_SIZE / 2;
        final int lastCenter = GoldenJukeboxTransportLayout.buttonX(4)
                + GoldenJukeboxTransportLayout.BUTTON_SIZE / 2;
        assertEquals(GoldenJukeboxTransportLayout.PANEL_WIDTH / 2,
                (firstCenter + lastCenter) / 2);
        for (int i = 1; i < GoldenJukeboxTransportLayout.BUTTON_COUNT; i++) {
            assertEquals(GoldenJukeboxTransportLayout.BUTTON_CENTER_STEP,
                    GoldenJukeboxTransportLayout.buttonX(i) - GoldenJukeboxTransportLayout.buttonX(i - 1));
        }
    }

    @Test
    void progressPrecedesButtonsInB2Order() {
        assertEquals(48, GoldenJukeboxTransportLayout.TIME_Y);
        assertEquals(60, GoldenJukeboxTransportLayout.BUTTON_Y);
        assertEquals(80, GoldenJukeboxTransportLayout.BUTTON_Y + GoldenJukeboxTransportLayout.BUTTON_SIZE);
    }

    @Test
    void rejectsUnknownButtonIndex() {
        assertThrows(IllegalArgumentException.class, () -> GoldenJukeboxTransportLayout.buttonX(-1));
        assertThrows(IllegalArgumentException.class,
                () -> GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.BUTTON_COUNT));
    }
    @Test
    void progressLeavesSpaceForActualTimeWidths() {
        final var bounds = GoldenJukeboxTransportLayout.seekBounds(36, 42);
        assertEquals(48, bounds.x());
        assertEquals(74, bounds.width());
        assertEquals(GoldenJukeboxTransportLayout.TIME_RIGHT - 42 - 4,
                bounds.x() + bounds.width());
        assertEquals(0, GoldenJukeboxTransportLayout.seekBounds(90, 90).width());
        assertThrows(IllegalArgumentException.class,
                () -> GoldenJukeboxTransportLayout.seekBounds(-1, 30));
    }
}

