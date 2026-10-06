package com.kuronami.musicdiscmaker.client.audio;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Main-thread fallback gate keyed by an immutable load generation; duplicate and stale faults are no-ops. */
public final class PlaybackFaultGate<K> {
    private record Entry(int token, PlaybackSessionId id, AtomicBoolean claimed) {}
    private final ConcurrentHashMap<K, Entry> current = new ConcurrentHashMap<>();

    public void activate(K source, int token, PlaybackSessionId id) {
        current.put(java.util.Objects.requireNonNull(source),
                new Entry(token, java.util.Objects.requireNonNull(id), new AtomicBoolean()));
    }
    public boolean isCurrent(K source, int token, PlaybackSessionId id) {
        Entry entry = current.get(source);
        return entry != null && entry.token() == token && entry.id().equals(id);
    }
    public boolean claimFallback(K source, int token, PlaybackSessionId id) {
        Entry entry = current.get(source);
        return entry != null && entry.token() == token && entry.id().equals(id)
                && entry.claimed().compareAndSet(false, true);
    }
    public void remove(K source, PlaybackSessionId id) {
        current.computeIfPresent(source, (ignored, entry) -> entry.id().equals(id) ? null : entry);
    }
    public void clear() { current.clear(); }
}
