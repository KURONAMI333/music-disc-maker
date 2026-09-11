package com.kuronami.musicdiscmaker.speaker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpeakerSelectionTest {

    private static final SpeakerSelection.Listener LISTENER = new SpeakerSelection.Listener(0.0D, 0.0D, 0.0D);

    @Test
    void selectsTheNearestCandidateOnlyAfterFilteringToInRangeActiveSpeakers() {
        final SpeakerSelection.Candidate outside = candidate("outside", 0.1D, 0.05D);
        final SpeakerSelection.Candidate muted = new SpeakerSelection.Candidate("muted", 0.2D, 0.0D, 0.0D,
                2.0D, 100, true);
        final SpeakerSelection.Candidate selected = candidate("selected", 2.0D, 2.0D);

        assertEquals(selected, SpeakerSelection.select(LISTENER, List.of(outside, muted, selected), null).orElseThrow());
    }

    @Test
    void doesNotSelectInvalidCoordinatesOrRangesOrSilentVolume() {
        final SpeakerSelection.Candidate valid = candidate("valid", 1.0D, 2.0D);
        final List<SpeakerSelection.Candidate> candidates = List.of(
                new SpeakerSelection.Candidate("nan-position", Double.NaN, 0.0D, 0.0D, 2.0D, 100, false),
                new SpeakerSelection.Candidate("infinite-position", Double.POSITIVE_INFINITY, 0.0D, 0.0D, 2.0D, 100, false),
                new SpeakerSelection.Candidate("nan-range", 0.0D, 0.0D, 0.0D, Double.NaN, 100, false),
                new SpeakerSelection.Candidate("infinite-range", 0.0D, 0.0D, 0.0D, Double.POSITIVE_INFINITY, 100, false),
                new SpeakerSelection.Candidate("negative-range", 0.0D, 0.0D, 0.0D, -1.0D, 100, false),
                new SpeakerSelection.Candidate("silent", 0.0D, 0.0D, 0.0D, 2.0D, 0, false),
                valid);

        assertEquals(valid, SpeakerSelection.select(LISTENER, candidates, null).orElseThrow());
        assertFalse(SpeakerSelection.select(new SpeakerSelection.Listener(Double.NaN, 0.0D, 0.0D), candidates, null).isPresent());
    }

    @Test
    void keepsThePreviousCandidateUntilAnotherIsMoreThanTheSwitchMarginCloser() {
        final SpeakerSelection.Candidate previous = candidate("previous", 4.0D, 5.0D);
        final SpeakerSelection.Candidate atMargin = candidate("at-margin", 3.5D, 5.0D);
        final SpeakerSelection.Candidate beyondMargin = candidate("beyond-margin", 3.49D, 5.0D);

        assertEquals(previous, SpeakerSelection.select(LISTENER, List.of(previous, atMargin), "previous").orElseThrow());
        assertEquals(beyondMargin,
                SpeakerSelection.select(LISTENER, List.of(previous, beyondMargin), "previous").orElseThrow());
    }

    @Test
    void immediatelyReselectsWhenThePreviousCandidateIsNoLongerEligible() {
        final SpeakerSelection.Candidate previousOutOfRange = candidate("previous", 4.0D, 3.0D);
        final SpeakerSelection.Candidate replacement = candidate("replacement", 2.0D, 3.0D);

        assertEquals(replacement,
                SpeakerSelection.select(LISTENER, List.of(previousOutOfRange, replacement), "previous").orElseThrow());
    }

    @Test
    void returnsEmptyWhenNoActiveCandidateIsInRange() {
        assertFalse(SpeakerSelection.select(LISTENER, List.of(), "previous").isPresent());
        assertFalse(SpeakerSelection.select(LISTENER, List.of(candidate("far", 3.0D, 2.0D)), "far").isPresent());
    }

    private static SpeakerSelection.Candidate candidate(String id, double x, double range) {
        return new SpeakerSelection.Candidate(id, x, 0.0D, 0.0D, range, 100, false);
    }
}
