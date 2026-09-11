package com.kuronami.musicdiscmaker.event;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Loaded world sources only. Absence never proves that a source was destroyed. */
public final class GoldenSourceRegistry {
    public enum Status { UNKNOWN, UNIQUE, MOVING, CONFLICT }

    public record Resolution(Status status, GoldenJukeboxBlockEntity source) {
    }

    private record Key(String dimension, UUID id) {
    }

    private static final class Index {
        final Map<Key, Set<GoldenJukeboxBlockEntity>> sources = new HashMap<>();
        final Map<GoldenJukeboxBlockEntity, Key> registrations = new IdentityHashMap<>();

        void remove(GoldenJukeboxBlockEntity source) {
            final Key oldKey = registrations.remove(source);
            if (oldKey == null) return;
            final Set<GoldenJukeboxBlockEntity> owners = sources.get(oldKey);
            if (owners != null) {
                owners.remove(source);
                if (owners.isEmpty()) sources.remove(oldKey);
            }
        }
    }

    private static final Map<MinecraftServer, Index> BY_SERVER = new HashMap<>();
    private record MovingOwner(Key key, java.lang.ref.WeakReference<Object> actor) {}
    private static final Map<MinecraftServer, java.util.List<MovingOwner>> MOVING = new HashMap<>();

    private GoldenSourceRegistry() {
    }

    public static void clear(MinecraftServer server) {
        BY_SERVER.remove(server);
        MOVING.remove(server);
    }

    /** Reserve a captured source without pretending its old world block is still present. */
    public static void registerMoving(ServerLevel level, UUID id, Object actor) {
        if (id == null || actor == null) return;
        final Key key = key(level, id);
        final var owners = MOVING.computeIfAbsent(level.getServer(), ignored -> new java.util.ArrayList<>());
        owners.removeIf(owner -> owner.actor().get() == null
                || (owner.actor().get() == actor && !owner.key().equals(key)));
        if (owners.stream().noneMatch(owner -> owner.actor().get() == actor)) {
            owners.add(new MovingOwner(key, new java.lang.ref.WeakReference<>(actor)));
        }
    }

    public static void unregisterMoving(ServerLevel level, Object actor) {
        final var owners = MOVING.get(level.getServer());
        if (owners == null) return;
        owners.removeIf(owner -> owner.actor().get() == null || owner.actor().get() == actor);
        if (owners.isEmpty()) MOVING.remove(level.getServer());
    }

    public static boolean ownsMovingSource(ServerLevel level, UUID id, Object actor) {
        if (lookup(level, id).status() != Status.MOVING) return false;
        final var owners = MOVING.get(level.getServer());
        return owners != null && owners.stream().anyMatch(owner -> owner.key().equals(key(level, id))
                && owner.actor().get() == actor);
    }

    private static int movingOwners(ServerLevel level, UUID id) {
        final var owners = MOVING.get(level.getServer());
        if (owners == null || id == null) return 0;
        owners.removeIf(owner -> owner.actor().get() == null);
        final Key key = key(level, id);
        final int count = (int) owners.stream().filter(owner -> owner.key().equals(key)).count();
        if (owners.isEmpty()) MOVING.remove(level.getServer());
        return count;
    }

    private static Resolution withoutWorldSource(ServerLevel level, UUID id, int moving) {
        if (moving == 1) GoldenSourceRetirements.get(level).forget(id);
        return new Resolution(moving == 0 ? Status.UNKNOWN : moving == 1 ? Status.MOVING : Status.CONFLICT, null);
    }

    public static void register(GoldenJukeboxBlockEntity source) {
        if (!(source.getLevel() instanceof ServerLevel level)) return;
        final UUID id = source.peekSourceIdentity();
        if (source.isRemoved() || id == null) {
            unregister(source);
            return;
        }
        final Key key = key(level, id);
        final Index index = BY_SERVER.computeIfAbsent(level.getServer(), ignored -> new Index());
        if (key.equals(index.registrations.get(source))) return;
        index.remove(source);
        index.registrations.put(source, key);
        index.sources.computeIfAbsent(key, ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
                .add(source);
    }

    public static void unregister(GoldenJukeboxBlockEntity source) {
        if (!(source.getLevel() instanceof ServerLevel level)) return;
        final Index index = BY_SERVER.get(level.getServer());
        if (index == null) return;
        index.remove(source);
        if (index.registrations.isEmpty()) BY_SERVER.remove(level.getServer());
    }

    public static Resolution lookup(ServerLevel level, UUID id) {
        final int moving = movingOwners(level, id);
        final Index index = BY_SERVER.get(level.getServer());
        if (index == null || id == null) return withoutWorldSource(level, id, moving);
        final Key key = key(level, id);
        final Set<GoldenJukeboxBlockEntity> sources = index.sources.get(key);
        if (sources == null) return withoutWorldSource(level, id, moving);
        for (GoldenJukeboxBlockEntity source : new HashSet<>(sources)) {
            // Never load a source chunk merely to resolve a saved link.
            if (source.isRemoved() || source.getLevel() != level
                    || !id.equals(source.peekSourceIdentity())
                    || !level.hasChunkAt(source.getBlockPos())
                    || level.getBlockEntity(source.getBlockPos()) != source) {
                index.remove(source);
            }
        }
        if (index.registrations.isEmpty()) BY_SERVER.remove(level.getServer());
        if (sources.isEmpty()) return withoutWorldSource(level, id, moving);
        // Register can run before LevelChunk installs the BE. Resolve it here, not during
        // setLevel/load, to avoid re-entering pending block-entity construction.
        if (sources.size() != 1 || moving > 0) return new Resolution(Status.CONFLICT, null);
        GoldenSourceRetirements.get(level).forget(id);
        return new Resolution(Status.UNIQUE, sources.iterator().next());
    }

    private static Key key(ServerLevel level, UUID id) {
        //? if >=1.21.2 {
        return new Key(level.dimension().identifier().toString(), id);
        //?} else {
        /*return new Key(level.dimension().location().toString(), id);
        *///?}
    }
}
