package com.kuronami.musicdiscmaker.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** 曲から自動アクセントを選ぶhash契約を固定する。 */
class TrackKeyTest {

    @Test
    void oldTwelveVariantResultsStayCompatible() {
        assertEquals(5, TrackKey.variantIndex("Alpha", "Artist"));
        assertEquals(7, TrackKey.variantIndex("Beta", "Artist"));
    }

    @Test
    void automaticDyeUsesSixteenStableChoices() {
        final int alpha = TrackKey.automaticDyeIndex("Alpha", "Artist");
        assertEquals(alpha, TrackKey.automaticDyeIndex("Alpha", "Artist"));
        assertEquals(alpha, TrackKey.automaticDyeIndex("Alpha (Official Video)", "Artist"));
        assertNotEquals(alpha, TrackKey.automaticDyeIndex("Beta", "Artist"));
        assertEquals(5, alpha);
        assertEquals(3, TrackKey.automaticDyeIndex("Beta", "Artist"));
    }
}
