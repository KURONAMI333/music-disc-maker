package com.kuronami.musicdiscmaker.event;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.speaker.SpeakerLink;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;

/**
 * 読み込まれているスピーカーだけを、音源キーから逆引きする server 側の索引。
 *
 * <p>ここは再生機構を持たない。将来の Golden Jukebox 配送や移動構造物は、この索引を通して
 * 音源の状態を渡せる。BlockEntity の NBT が正本なので、chunk unload 後もこの索引に残骸を残さない。
 */
public final class SpeakerNetwork {

    private static final Map<MinecraftServer, Map<SpeakerLink, Set<SpeakerBlockEntity>>> BY_SERVER = new HashMap<>();
    private static final Map<MinecraftServer, Map<IdentityKey, Set<SpeakerBlockEntity>>> BY_ID = new HashMap<>();

    private record IdentityKey(String dimensionId, UUID sourceId) {
    }

    private SpeakerNetwork() {
    }

    /** ワールド退出時はBlockEntityごとのunload通知に依存せずserver参照を解放する。 */
    public static void clear(MinecraftServer server) {
        BY_SERVER.remove(server);
        BY_ID.remove(server);
        GoldenSourceRegistry.clear(server);
    }

    public static void register(SpeakerBlockEntity speaker) {
        if (!(speaker.getLevel() instanceof ServerLevel level) || speaker.isRemoved()) {
            return;
        }
        speaker.getLinkedSource().ifPresent(link -> BY_SERVER
                .computeIfAbsent(level.getServer(), ignored -> new HashMap<>())
                .computeIfAbsent(link.positionKey(), ignored -> new HashSet<>())
                .add(speaker));
        speaker.getLinkedSource().filter(link -> link.sourceId() != null).ifPresent(link -> BY_ID
                .computeIfAbsent(level.getServer(), ignored -> new HashMap<>())
                .computeIfAbsent(new IdentityKey(link.dimensionId(), link.sourceId()), ignored -> new HashSet<>())
                .add(speaker));
    }

    public static void unregister(SpeakerBlockEntity speaker) {
        if (!(speaker.getLevel() instanceof ServerLevel level)) {
            return;
        }
        final Map<IdentityKey, Set<SpeakerBlockEntity>> byId = BY_ID.get(level.getServer());
        if (byId != null) {
            speaker.getLinkedSource().filter(link -> link.sourceId() != null).ifPresent(link -> {
                final IdentityKey key = new IdentityKey(link.dimensionId(), link.sourceId());
                final Set<SpeakerBlockEntity> speakers = byId.get(key);
                if (speakers != null) {
                    speakers.remove(speaker);
                    if (speakers.isEmpty()) byId.remove(key);
                }
            });
            if (byId.isEmpty()) BY_ID.remove(level.getServer());
        }
        final Map<SpeakerLink, Set<SpeakerBlockEntity>> bySource = BY_SERVER.get(level.getServer());
        if (bySource == null) {
            return;
        }
        speaker.getLinkedSource().ifPresent(link -> {
            final Set<SpeakerBlockEntity> speakers = bySource.get(link.positionKey());
            if (speakers == null) {
                return;
            }
            speakers.remove(speaker);
            if (speakers.isEmpty()) {
                bySource.remove(link.positionKey());
            }
        });
        if (bySource.isEmpty()) {
            BY_SERVER.remove(level.getServer());
        }
    }

    /**
     * Resolves receivers for an already identified source without consulting its old position.
     * The caller must establish source ownership; this query does not claim a moving source.
     * Legacy position-only links cannot be inferred from an ID and are deliberately excluded.
     */
    public static Set<SpeakerBlockEntity> findLinkedSpeakers(ServerLevel level, UUID sourceId) {
        if (sourceId == null) return Set.of();
        //? if >=1.21.2 {
        final IdentityKey key = new IdentityKey(level.dimension().identifier().toString(), sourceId);
        //?} else {
        /*final IdentityKey key = new IdentityKey(level.dimension().location().toString(), sourceId);
        *///?}
        final Map<IdentityKey, Set<SpeakerBlockEntity>> byId = BY_ID.get(level.getServer());
        final Set<SpeakerBlockEntity> speakers = byId == null ? null : byId.get(key);
        if (speakers == null) return Set.of();
        speakers.removeIf(speaker -> speaker.isRemoved()
                || speaker.getLevel() != level
                || !level.hasChunkAt(speaker.getBlockPos())
                || level.getBlockEntity(speaker.getBlockPos()) != speaker
                || speaker.getLinkedSource().filter(link -> key.dimensionId().equals(link.dimensionId())
                        && sourceId.equals(link.sourceId())).isEmpty());
        if (speakers.isEmpty()) {
            byId.remove(key);
            if (byId.isEmpty()) BY_ID.remove(level.getServer());
            return Set.of();
        }
        return Collections.unmodifiableSet(new HashSet<>(speakers));
    }

    /**
     * Resolves a loaded, uniquely owned Golden source. Legacy links acquire their first identity;
     * identity links require either the same source or confirmed destruction at this position.
     */
    public static Set<SpeakerBlockEntity> findLinkedSpeakers(ServerLevel level, GoldenJukeboxBlockEntity source) {
        if (source.getLevel() != level || source.isRemoved()
                || !level.hasChunkAt(source.getBlockPos())
                || level.getBlockEntity(source.getBlockPos()) != source) {
            return Set.of();
        }
        final UUID id = source.sourceIdentity();
        final GoldenSourceRegistry.Resolution owner = GoldenSourceRegistry.lookup(level, id);
        if (owner.status() != GoldenSourceRegistry.Status.UNIQUE || owner.source() != source) {
            return Set.of();
        }
        // Both queries return copies: upgrading a link can safely change both backing indices.
        final Set<SpeakerBlockEntity> result = new HashSet<>(findLinkedSpeakers(level, id));
        for (SpeakerBlockEntity candidate : findLinkedSpeakers(level, source.getBlockPos())) {
            if (candidate.getLinkedSource().filter(link -> link.sourceId() == null).isPresent()) {
                result.add(candidate);
            }
        }
        final GoldenSourceRetirements retirements = GoldenSourceRetirements.get(level);
        for (UUID retiredId : retirements.unclaimedAt(source.getBlockPos())) {
            if (GoldenSourceRegistry.lookup(level, retiredId).status() == GoldenSourceRegistry.Status.UNKNOWN) {
                retirements.claimReplacement(retiredId, id);
            }
        }
        // Resolve revivals before gathering receivers: restoring B can break an old A -> B -> C chain.
        final Set<UUID> unavailable = new HashSet<>();
        for (UUID predecessor : retirements.predecessorsOf(id)) {
            if (GoldenSourceRegistry.lookup(level, predecessor).status() != GoldenSourceRegistry.Status.UNKNOWN) {
                unavailable.add(predecessor);
                unavailable.addAll(retirements.predecessorsOf(predecessor));
            }
        }
        for (UUID predecessor : retirements.predecessorsOf(id)) {
            if (!unavailable.contains(predecessor)) {
                result.addAll(findLinkedSpeakers(level, predecessor));
            }
        }
        //? if >=1.21.2 {
        final SpeakerLink current = new SpeakerLink(level.dimension().identifier().toString(),
                source.getBlockPos().asLong(), id);
        //?} else {
        /*final SpeakerLink current = new SpeakerLink(level.dimension().location().toString(),
                source.getBlockPos().asLong(), id);
        *///?}
        result.forEach(speaker -> speaker.replaceLinkedSource(current));
        return Collections.unmodifiableSet(result);
    }

    /** 現在ロード済みで指定音源を聞くスピーカーのスナップショット。 */
    public static Set<SpeakerBlockEntity> findLinkedSpeakers(ServerLevel level, BlockPos sourcePos) {
        //? if >=1.21.2 {
        final SpeakerLink source = new SpeakerLink(level.dimension().identifier().toString(), sourcePos.asLong());
        //?} else {
        /*final SpeakerLink source = new SpeakerLink(level.dimension().location().toString(), sourcePos.asLong());
        *///?}
        final Map<SpeakerLink, Set<SpeakerBlockEntity>> bySource = BY_SERVER.get(level.getServer());
        final Set<SpeakerBlockEntity> speakers = bySource == null ? null : bySource.get(source);
        if (speakers == null) {
            return Set.of();
        }
        speakers.removeIf(speaker -> {
            final BlockPos speakerPos = speaker.getBlockPos();
            // getBlockEntity は未ロード chunk の読み込みを誘発し得る。確認前に必ず chunk を弾く。
            return speaker.isRemoved()
                    || speaker.getLevel() != level
                    || !level.hasChunkAt(speakerPos)
                    || level.getBlockEntity(speakerPos) != speaker
                    || speaker.getLinkedSource().filter(link -> source.equals(link.positionKey())).isEmpty();
        });
        if (speakers.isEmpty()) {
            bySource.remove(source);
            if (bySource.isEmpty()) {
                BY_SERVER.remove(level.getServer());
            }
            return Set.of();
        }
        return Collections.unmodifiableSet(new HashSet<>(speakers));
    }
}
