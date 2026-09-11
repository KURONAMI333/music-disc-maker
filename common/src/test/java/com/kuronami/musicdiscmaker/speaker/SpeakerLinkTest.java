package com.kuronami.musicdiscmaker.speaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import java.util.UUID;

class SpeakerLinkTest {

    @Test
    void identityRoundTripsWithoutChangingTheLegacyPositionKey() {
        final UUID identity = UUID.fromString("841af1d1-b0e2-4d8e-b325-99989e03ff93");
        final SpeakerLink restored = SpeakerLink.restore("minecraft:overworld", 42L, identity.toString()).orElseThrow();
        assertEquals(identity, restored.sourceId());
        assertEquals(new SpeakerLink("minecraft:overworld", 42L), restored.positionKey());
    }

    @Test
    void invalidOrMissingIdentityPreservesTheLegacyLink() {
        final SpeakerLink legacy = new SpeakerLink("minecraft:overworld", 42L);
        for (String identity : new String[] {null, "", "invalid-uuid"}) {
            assertEquals(legacy, SpeakerLink.restore("minecraft:overworld", 42L, identity).orElseThrow());
        }
        assertFalse(SpeakerLink.restore("", 42L, UUID.randomUUID().toString()).isPresent());
    }

    @Test
    void keepsDimensionAndPackedPositionAsTheStableSourceKey() {
        final SpeakerLink link = new SpeakerLink("minecraft:overworld", 42L);
        assertEquals("minecraft:overworld", link.dimensionId());
        assertEquals(42L, link.packedPos());
    }

    @Test
    void restoresOnlyACompleteLink() {
        assertFalse(SpeakerLink.restore("", 9L).isPresent());
        assertEquals(new SpeakerLink("minecraft:the_nether", 9L),
                SpeakerLink.restore("minecraft:the_nether", 9L).orElseThrow());
    }

    @Test
    void rejectsBlankDimensionAtTheBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new SpeakerLink(" ", 0L));
    }
}
