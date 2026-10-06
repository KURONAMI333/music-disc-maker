package com.kuronami.musicdiscmaker.client.audio;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Indexes one pending close by both its old route and stable source identity. */
final class CloseBarrierIndex<K, V> {
    private final Map<K, V> routes = new HashMap<>();
    private final Map<UUID, V> sources = new HashMap<>();

    synchronized V byRoute(K route) { return routes.get(route); }

    synchronized V bySource(UUID source) { return sources.get(source); }

    synchronized V register(K route, UUID source, V barrier) {
        final V existing = routes.get(route);
        if (existing != null) return existing;
        if (source != null) {
            final V sourceExisting = sources.get(source);
            if (sourceExisting != null) return sourceExisting;
        }
        routes.put(route, barrier);
        if (source != null) sources.put(source, barrier);
        return null;
    }

    synchronized void remove(K route, UUID source, V barrier) {
        routes.remove(route, barrier);
        if (source != null) sources.remove(source, barrier);
    }
}
