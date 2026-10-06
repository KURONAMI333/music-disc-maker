package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;

/** Retires world-local active-session lookups without losing IDs needed by close acknowledgements. */
final class ExtensionSessionRetirement {
    private ExtensionSessionRetirement() {}

    static <K, V> Map<K, V> captureAndClear(Map<K, V> active) {
        Map<K, V> previous = Map.copyOf(active);
        active.clear();
        return previous;
    }

    static <K, V> boolean removeIfSame(Map<K, V> active, K key, V expected) {
        return active.remove(key, expected);
    }
}
