package com.kuronami.musicdiscmaker.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class VanillaTrackDataTest {
    @Test
    void otherModRecordIdentifiersRemainResourceIdentifiers() {
        final VanillaTrackData track = new VanillaTrackData("another_mod:records/forest_theme", 185000L);
        assertEquals("another_mod:records/forest_theme", track.soundEventId());
        assertEquals(185000L, track.durationMs());
    }

    @Test
    void resourceTrackCannotCarryAWebUrlOrInvalidWireMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> new VanillaTrackData("https://example.com/record.ogg", 185000L));
        assertThrows(IllegalArgumentException.class,
                () -> new VanillaTrackData("minecraft:music_disc.cat", -1L));
        assertThrows(IllegalArgumentException.class,
                () -> new VanillaTrackData("another_mod:" + "a".repeat(256), 1L));
    }
}
