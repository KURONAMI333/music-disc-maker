package com.kuronami.musicdiscmaker.client.audio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 音源UUIDごとに一つの再生経路を所有する。client main threadから使用する。 */
public final class SourcePlaybackOwnership<R> {
    public static final class Lease<R> {
        private final UUID source;
        private final long generation;
        private final R route;
        private final Runnable stop;

        private Lease(UUID source, long generation, R route, Runnable stop) {
            this.source = source;
            this.generation = generation;
            this.route = route;
            this.stop = stop;
        }
    }

    private final Map<UUID, Lease<R>> owners = new HashMap<>();
    private final Map<R, Lease<R>> routes = new HashMap<>();
    private final Map<UUID, Long> generations = new HashMap<>();

    /** 古い世代は拒否。同じ経路のkeep-aliveは同じleaseを返し、ロードを失効させない。 */
    public Lease<R> claim(UUID source, long generation, R route, Runnable stop) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(route);
        Objects.requireNonNull(stop);
        if (generation < 0) throw new IllegalArgumentException("Negative playback generation");
        if (generation < generations.getOrDefault(source, -1L)) return null;
        final Lease<R> old = owners.get(source);
        if (old != null && old.generation == generation && old.route.equals(route)) return old;
        generations.put(source, generation);
        owners.remove(source);
        if (old != null) {
            routes.remove(old.route, old);
            old.stop.run();
        }
        final Lease<R> displaced = routes.remove(route);
        if (displaced != null) {
            owners.remove(displaced.source, displaced);
            displaced.stop.run();
        }
        final Lease<R> next = new Lease<>(source, generation, route, stop);
        owners.put(source, next);
        routes.put(route, next);
        return next;
    }

    /** 非同期ロードの完了時に照合する。世代が同じでも経路を移った古いleaseは無効。 */
    public boolean isCurrent(Lease<R> lease) {
        return lease != null && owners.get(lease.source) == lease;
    }

    /** 旧座標/旧actorからの停止は、移動後の別経路を止めない。 */
    public boolean stop(UUID source, long generation, R route) {
        final Lease<R> owner = owners.get(source);
        if (owner == null || generation < owner.generation || !owner.route.equals(route)) return false;
        generations.put(source, Math.max(generation, generations.getOrDefault(source, -1L)));
        owners.remove(source);
        routes.remove(owner.route, owner);
        owner.stop.run();
        return true;
    }

    /** world退出前に全leaseを失効させる。再入場で同じUUID/経路でも旧ロードは復活しない。 */
    public void clear() {
        final var previous = new ArrayList<>(owners.values());
        owners.clear();
        routes.clear();
        generations.clear();
        RuntimeException failure = null;
        for (Lease<R> owner : previous) {
            try { owner.stop.run(); }
            catch (RuntimeException problem) {
                if (failure == null) failure = problem;
                else failure.addSuppressed(problem);
            }
        }
        if (failure != null) throw failure;
    }
}
