package com.kuronami.musicdiscmaker.client.audio;

/** Restricts open retries to transient finite remote playback on the regular MDM route. */
final class FiniteOpenRetryPolicy {
    private FiniteOpenRetryPolicy() {}

    static boolean mayRetry(boolean radio, boolean local, boolean extensionSelected,
            boolean regularOnly, boolean networkFailure) {
        return !radio && !local && (regularOnly || !extensionSelected)
                && networkFailure;
    }
}
