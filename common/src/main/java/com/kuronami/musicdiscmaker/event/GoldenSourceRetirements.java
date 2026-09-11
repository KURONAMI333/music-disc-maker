package com.kuronami.musicdiscmaker.event;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedDataType;
//?} else {
/*import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
*///?}
//? if >=1.20.5 && <1.21.11 {
/*import net.minecraft.core.HolderLookup;
*///?}

/** Confirmed removals only; never populate this from chunk unload or a failed source lookup. */
public final class GoldenSourceRetirements extends SavedData {
    private static final String DATA_NAME = "music_disc_maker_retired_sources";
    public static final Codec<GoldenSourceRetirements> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Codec.LONG).fieldOf("retired")
                    .forGetter((GoldenSourceRetirements data) -> Map.copyOf(data.entries)),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("successors", Map.of())
                    .forGetter((GoldenSourceRetirements data) -> Map.copyOf(data.successors)))
            .apply(instance, GoldenSourceRetirements::new));

    private final Map<String, Long> entries = new HashMap<>();
    private final Map<Long, Set<UUID>> byPosition = new HashMap<>();
    private final Map<String, String> successors = new HashMap<>();
    private final Map<UUID, Set<UUID>> predecessors = new HashMap<>();

    public GoldenSourceRetirements() {
    }

    private GoldenSourceRetirements(Map<String, Long> saved, Map<String, String> savedSuccessors) {
        saved.forEach((key, position) -> {
            try {
                final UUID id = UUID.fromString(key);
                put(id, position);
            } catch (IllegalArgumentException ignored) {
                // An invalid optional identity cannot authorize relinking another source.
            }
        });
        savedSuccessors.forEach((from, to) -> {
            try {
                bind(UUID.fromString(from), UUID.fromString(to));
            } catch (IllegalArgumentException ignored) {
                // Malformed identities cannot authorize an inheritance relationship.
            }
        });
    }

    public void recordRemoval(UUID id, BlockPos position) {
        final long packed = position.asLong();
        if (Long.valueOf(packed).equals(entries.get(id.toString()))) return;
        put(id, packed);
        setDirty();
    }

    public Set<UUID> removedAt(BlockPos position) {
        return Set.copyOf(byPosition.getOrDefault(position.asLong(), Set.of()));
    }

    public Set<UUID> unclaimedAt(BlockPos position) {
        if (!byPosition.containsKey(position.asLong())) return Set.of();
        final Set<UUID> result = new HashSet<>(removedAt(position));
        result.removeIf(id -> successors.containsKey(id.toString()));
        return Set.copyOf(result);
    }

    /** Binds the first replacement even if some or all receivers are currently unloaded. */
    public void claimReplacement(UUID retired, UUID replacement) {
        if (bind(retired, replacement)) setDirty();
    }

    /** Includes earlier generations, so A -> B -> C still recovers receivers saved with A. */
    public Set<UUID> predecessorsOf(UUID successor) {
        if (!predecessors.containsKey(successor)) return Set.of();
        final Set<UUID> result = new HashSet<>();
        final ArrayDeque<UUID> pending = new ArrayDeque<>();
        pending.add(successor);
        while (!pending.isEmpty()) {
            for (UUID previous : predecessors.getOrDefault(pending.removeFirst(), Set.of())) {
                if (!previous.equals(successor) && result.add(previous)) pending.addLast(previous);
            }
        }
        return Set.copyOf(result);
    }

    private boolean bind(UUID retired, UUID replacement) {
        if (retired.equals(replacement) || !entries.containsKey(retired.toString())
                || successors.containsKey(retired.toString()) || predecessorsOf(retired).contains(replacement)) {
            return false;
        }
        successors.put(retired.toString(), replacement.toString());
        predecessors.computeIfAbsent(replacement, ignored -> new HashSet<>()).add(retired);
        return true;
    }

    /** A genuinely restored source with this identity supersedes a previous removal record. */
    public void forget(UUID id) {
        if (remove(id)) setDirty();
    }

    private void put(UUID id, long position) {
        remove(id);
        entries.put(id.toString(), position);
        byPosition.computeIfAbsent(position, ignored -> new HashSet<>()).add(id);
    }

    private boolean remove(UUID id) {
        final Long oldPosition = entries.remove(id.toString());
        if (oldPosition == null) return false;
        final String successor = successors.remove(id.toString());
        if (successor != null) {
            final UUID next = UUID.fromString(successor);
            final Set<UUID> parents = predecessors.get(next);
            parents.remove(id);
            if (parents.isEmpty()) predecessors.remove(next);
        }
        final Set<UUID> previous = byPosition.get(oldPosition);
        previous.remove(id);
        if (previous.isEmpty()) byPosition.remove(oldPosition);
        return true;
    }

    //? if >=26.1 {
    public static GoldenSourceRetirements get(ServerLevel level) {
        return level.getChunkSource().getDataStorage().computeIfAbsent(new SavedDataType<>(
                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "retired_sources"),
                GoldenSourceRetirements::new, CODEC, DataFixTypes.LEVEL));
    }
    //?} elif >=1.21.11 {
    /*public static GoldenSourceRetirements get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedDataType<>(
                DATA_NAME, GoldenSourceRetirements::new, CODEC, DataFixTypes.LEVEL));
    }
    *///?} elif >=1.20.5 {
    /*public static GoldenSourceRetirements get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(
                GoldenSourceRetirements::new, (tag, registries) -> decode(tag), null), DATA_NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return encode();
    }
    *///?} else {
    /*public static GoldenSourceRetirements get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                GoldenSourceRetirements::decode, GoldenSourceRetirements::new, DATA_NAME);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        return encode();
    }
    *///?}

    //? if <1.21.11 {
    /*private static GoldenSourceRetirements decode(CompoundTag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag).result()
                .orElseThrow(() -> new IllegalStateException("Invalid Golden source retirement data"));
    }

    private CompoundTag encode() {
        return (CompoundTag) CODEC.encodeStart(NbtOps.INSTANCE, this).result()
                .orElseThrow(() -> new IllegalStateException("Could not encode Golden source retirement data"));
    }
    *///?}
}
