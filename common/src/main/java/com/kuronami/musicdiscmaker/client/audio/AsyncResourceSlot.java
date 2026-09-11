package com.kuronami.musicdiscmaker.client.audio;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Hands one asynchronously-created resource to an owner that may already have been released.
 * Installation and release are serialized so the resource is closed exactly once in either order.
 */
final class AsyncResourceSlot<T> {
    private final Consumer<T> closer;
    private T current;
    private boolean released;

    AsyncResourceSlot(Consumer<T> closer) {
        this.closer = Objects.requireNonNull(closer, "closer");
    }

    boolean install(T value) {
        Objects.requireNonNull(value, "value");
        synchronized (this) {
            if (!released && current == null) {
                current = value;
                return true;
            }
        }
        closer.accept(value);
        return false;
    }

    void release() {
        final T toClose;
        synchronized (this) {
            if (released) return;
            released = true;
            toClose = current;
            current = null;
        }
        if (toClose != null) closer.accept(toClose);
    }

    synchronized T current() {
        return current;
    }

    synchronized boolean isReleased() {
        return released;
    }
}
