package com.kuronami.musicdiscmaker.event;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.SpeakerBlock;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.component.VanillaTrackData;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.PlayVanillaDiscPayload;
import com.kuronami.musicdiscmaker.network.SpeakerEntry;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;

/** 固定Speakerを持つGoldenのplayer別配送状態。server threadからだけ呼ぶ。 */
public final class SpeakerPlayback {

    private record Snapshot(int range, int sourceVolume, boolean directional, List<SpeakerEntry> speakers, com.kuronami.musicdiscmaker.network.PlaybackSourceStamp identity) {
    }

    /** custom URL と SoundEvent を同じ listener/snapshot 差分へ載せるための内部表現。 */
    private record Playback(CustomTrackData custom, VanillaTrackData vanilla) {
        private static Playback custom(CustomTrackData track) {
            return new Playback(track, null);
        }

        private static Playback vanilla(VanillaTrackData track) {
            return new Playback(null, track);
        }
    }

    private static final class State {
        Snapshot snapshot;
        Playback playback;
        long generation;
        final Set<UUID> listeners = new HashSet<>();
    }

    /** BE unload後にserver/playerをstaticに保持しない。通常の破壊経路ではremoveが先にStopを送る。 */
    private static final Map<GoldenJukeboxBlockEntity, State> STATES = new WeakHashMap<>();

    private SpeakerPlayback() {
    }

    /** Speakerが1台以上ある時はplayer別のSet→Playを完結し、旧chunk broadcastを置き換える。 */
    public static boolean play(GoldenJukeboxBlockEntity source, CustomTrackData track, long offsetMs) {
        return play(source, Playback.custom(track), offsetMs);
    }

    /** nativeの再生eventは維持しつつ、Speakerがある時だけresource-pack音声の配送へ切り替える。 */
    public static boolean play(GoldenJukeboxBlockEntity source, VanillaTrackData track, long offsetMs) {
        final ServerLevel level = serverLevel(source);
        return play(source, Playback.vanilla(track), offsetMs,
                level != null && Services.PLATFORM.isMovingAudioSource(level, source.getBlockPos()));
    }

    /** Moving hosts cannot use a fixed vanilla block-event sound, even without extra speakers. */
    public static boolean playMovingVanilla(GoldenJukeboxBlockEntity source) {
        if (source.playbackCursor().state() != PlaybackCursor.State.PLAYING) return false;
        final VanillaTrackData track = source.currentVanillaTrack();
        return track != null && play(source, Playback.vanilla(track), source.currentElapsedMs(), true);
    }

    private static boolean play(GoldenJukeboxBlockEntity source, Playback playback, long offsetMs) {
        return play(source, playback, offsetMs, false);
    }

    private static boolean play(GoldenJukeboxBlockEntity source, Playback playback, long offsetMs, boolean movingVanilla) {
        final ServerLevel level = serverLevel(source);
        if (level == null) {
            return false;
        }
        final Snapshot snapshot = snapshot(level, source);
        State state = STATES.get(source);
        final boolean continueManagedVanilla = playback.vanilla() != null
                && (movingVanilla || (state != null && state.playback != null && state.playback.vanilla() != null));
        if (snapshot.speakers().isEmpty() && !continueManagedVanilla) {
            clearSpeakerSet(source, level);
            return false;
        }
        // nativeの開始eventは毎回chunkへ届く。Speaker envelope外ではsource本来の64-block音を残さない。
        if (state == null || playback.vanilla() != null) {
            stopLegacyListenersOutside(level, source.getBlockPos(), snapshot, state);
        }
        if (state == null) {
            state = new State();
            STATES.put(source, state);
        }
        state.snapshot = snapshot;
        state.playback = playback;
        state.generation = source.playbackCursor().generation();
        reconcile(level, source.getBlockPos(), state, Math.max(0L, offsetMs), true);
        return true;
    }

    /** Speaker配送中の全listenerへStopを送り、揮発stateを破棄する。 */
    public static boolean stop(GoldenJukeboxBlockEntity source) {
        final ServerLevel level = serverLevel(source);
        final State state = STATES.remove(source);
        final boolean hasSpeakers = level != null && !snapshot(level, source).speakers().isEmpty();
        if (state != null && level != null) {
            for (ServerPlayer player : level.players()) {
                if (state.listeners.contains(player.getUUID())) {
                    Services.NETWORK.sendToPlayer(player, new StopDiscPayload(source.getBlockPos(), source.playbackIdentity()));
                }
            }
        }
        return state != null || hasSpeakers;
    }

    /** chunk watch等の個別再送。Speaker有効時は範囲判定もserver側で行う。 */
    public static boolean resendTo(GoldenJukeboxBlockEntity source, ServerPlayer player,
            CustomTrackData track, long offsetMs) {
        return resendTo(source, player, Playback.custom(track), offsetMs);
    }

    public static boolean resendTo(GoldenJukeboxBlockEntity source, ServerPlayer player,
            VanillaTrackData track, long offsetMs) {
        return resendTo(source, player, Playback.vanilla(track), offsetMs);
    }

    private static boolean resendTo(GoldenJukeboxBlockEntity source, ServerPlayer player,
            Playback playback, long offsetMs) {
        final ServerLevel level = serverLevel(source);
        if (level == null) {
            return false;
        }
        final Snapshot snapshot = snapshot(level, source);
        State state = STATES.get(source);
        if (snapshot.speakers().isEmpty()
                && (state == null || state.playback == null || state.playback.vanilla() == null)) {
            if (playback.vanilla() != null && Services.PLATFORM.isMovingAudioSource(level, source.getBlockPos())) {
                return play(source, playback, offsetMs, true);
            }
            return false;
        }
        if (state == null) {
            stopLegacyListenersOutside(level, source.getBlockPos(), snapshot, null);
            state = new State();
            state.snapshot = snapshot;
            state.playback = playback;
            state.generation = source.playbackCursor().generation();
            STATES.put(source, state);
            reconcile(level, source.getBlockPos(), state, Math.max(0L, offsetMs), true);
            return true;
        }
        state.playback = playback;
        state.generation = source.playbackCursor().generation();
        if (audible(player, source.getBlockPos(), snapshot)) {
            sendSet(player, source.getBlockPos(), snapshot);
            sendPlay(player, source.getBlockPos(), state.playback, Math.max(0L, offsetMs),
                    state.generation, snapshot);
            state.listeners.add(player.getUUID());
        }
        return true;
    }

    /** Speaker集合・設定とplayerの入退域を差分配送する。 */
    public static void tick(GoldenJukeboxBlockEntity source) {
        final ServerLevel level = serverLevel(source);
        if (level == null) {
            return;
        }
        final Snapshot next = snapshot(level, source);
        State state = STATES.get(source);
        final Playback current = currentPlayback(source);
        final boolean playing = source.playbackCursor().state() == PlaybackCursor.State.PLAYING
                && current != null;
        if (next.speakers().isEmpty()) {
            if (state == null && playing && current.vanilla() != null
                    && Services.PLATFORM.isMovingAudioSource(level, source.getBlockPos())) {
                playMovingVanilla(source);
                return;
            }
            if (state != null && state.playback != null && state.playback.vanilla() != null && playing) {
                final boolean setChanged = !next.equals(state.snapshot);
                state.snapshot = next;
                state.playback = current;
                state.generation = source.playbackCursor().generation();
                reconcile(level, source.getBlockPos(), state, source.currentElapsedMs(), false, setChanged);
            } else if (state != null) {
                clearSpeakerSet(source, level);
            }
            return;
        }
        if (!playing) {
            if (state != null) {
                stop(source);
            }
            return;
        }
        if (state == null) {
            stopLegacyListenersOutside(level, source.getBlockPos(), next, null);
            state = new State();
            state.snapshot = next;
            state.playback = current;
            state.generation = source.playbackCursor().generation();
            STATES.put(source, state);
            reconcile(level, source.getBlockPos(), state, source.currentElapsedMs(), true);
            return;
        }
        final boolean setChanged = !next.equals(state.snapshot);
        state.snapshot = next;
        state.playback = current;
        state.generation = source.playbackCursor().generation();
        reconcile(level, source.getBlockPos(), state, source.currentElapsedMs(), false, setChanged);
    }

    /** 破壊・chunk unload用。 */
    public static void remove(GoldenJukeboxBlockEntity source) {
        stop(source);
    }

    /** server停止時は送信せず、そのserverに属する揮発stateだけ破棄する。 */
    public static void clear(MinecraftServer server) {
        STATES.keySet().removeIf(source -> source.getLevel() instanceof ServerLevel level
                && level.getServer() == server);
    }

    private static void reconcile(ServerLevel level, BlockPos sourcePos, State state,
            long offsetMs, boolean replayEveryone) {
        reconcile(level, sourcePos, state, offsetMs, replayEveryone, true);
    }

    private static void reconcile(ServerLevel level, BlockPos sourcePos, State state,
            long offsetMs, boolean replayEveryone, boolean setChanged) {
        final Set<UUID> present = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            final UUID id = player.getUUID();
            final boolean existing = state.listeners.contains(id);
            if (existing) {
                if (setChanged || replayEveryone) {
                    sendSet(player, sourcePos, state.snapshot);
                }
                if (insideEnvelope(player, sourcePos, state.snapshot)) {
                    present.add(id);
                    if (replayEveryone && state.playback != null) {
                        sendPlay(player, sourcePos, state.playback, Math.max(0L, offsetMs),
                                state.generation, state.snapshot);
                    }
                } else {
                    Services.NETWORK.sendToPlayer(player, new StopDiscPayload(sourcePos, state.snapshot.identity()));
                }
                continue;
            }
            if (!audible(player, sourcePos, state.snapshot)) {
                if (state.listeners.remove(id)) {
                    Services.NETWORK.sendToPlayer(player, new StopDiscPayload(sourcePos, state.snapshot.identity()));
                }
                continue;
            }
            present.add(id);
            final boolean entered = state.listeners.add(id);
            if (entered || setChanged || replayEveryone) {
                sendSet(player, sourcePos, state.snapshot);
            }
            if ((entered || replayEveryone) && state.playback != null) {
                sendPlay(player, sourcePos, state.playback, Math.max(0L, offsetMs),
                        state.generation, state.snapshot);
            }
        }
        state.listeners.retainAll(present);
    }

    private static void sendSet(ServerPlayer player, BlockPos sourcePos, Snapshot snapshot) {
        Services.NETWORK.sendToPlayer(player, new SpeakerSetPayload(sourcePos, snapshot.range(),
                snapshot.sourceVolume(), snapshot.directional(), snapshot.speakers(), snapshot.identity()));
    }

    private static void sendPlay(ServerPlayer player, BlockPos sourcePos, Playback playback,
            long offsetMs, long generation, Snapshot snapshot) {
        if (playback.custom() != null) {
            Services.NETWORK.sendToPlayer(player,
                    new PlayDiscPayload(sourcePos, playback.custom(), offsetMs,
                            snapshot.range(), snapshot.sourceVolume(), snapshot.directional(), snapshot.identity()));
        } else if (playback.vanilla() != null) {
            Services.NETWORK.sendToPlayer(player,
                    new PlayVanillaDiscPayload(sourcePos, playback.vanilla(), offsetMs,
                            snapshot.range(), snapshot.sourceVolume(), snapshot.directional(), snapshot.identity()));
        }
    }

    private static Playback currentPlayback(GoldenJukeboxBlockEntity source) {
        final CustomTrackData custom = source.currentTrack();
        if (custom != null) {
            return Playback.custom(custom);
        }
        final VanillaTrackData vanilla = source.currentVanillaTrack();
        return vanilla != null ? Playback.vanilla(vanilla) : null;
    }

    private static boolean audible(ServerPlayer player, BlockPos sourcePos, Snapshot snapshot) {
        if (insideSource(player, sourcePos, snapshot.range())) {
            return true;
        }
        for (SpeakerEntry speaker : snapshot.speakers()) {
            if (!speaker.muted() && speaker.volumePercent() > 0
                    && inside(player, speaker.pos(), snapshot.range())) {
                return true;
            }
        }
        return false;
    }

    private static boolean insideEnvelope(ServerPlayer player, BlockPos sourcePos, Snapshot snapshot) {
        if (insideSource(player, sourcePos, snapshot.range())) {
            return true;
        }
        for (SpeakerEntry speaker : snapshot.speakers()) {
            if (inside(player, speaker.pos(), snapshot.range())) {
                return true;
            }
        }
        return false;
    }

    /** Speaker集合消滅をSet(empty)でStaticへ戻し、元音源にも届かないlistenerだけ解放する。 */
    private static void clearSpeakerSet(GoldenJukeboxBlockEntity source, ServerLevel level) {
        final State state = STATES.remove(source);
        if (state == null) {
            return;
        }
        final Snapshot empty = new Snapshot(source.getRangeBlocks(), source.getVolumePercent(),
                source.isDirectional(), List.of(), source.playbackIdentity());
        for (ServerPlayer player : level.players()) {
            if (state.listeners.contains(player.getUUID())) {
                sendSet(player, source.getBlockPos(), empty);
                if (!insideSource(player, source.getBlockPos(), empty.range())) {
                    Services.NETWORK.sendToPlayer(player, new StopDiscPayload(source.getBlockPos(), source.playbackIdentity()));
                }
            }
        }
    }

    private static boolean inside(ServerPlayer player, BlockPos pos, int range) {
        final double dx = player.getX() - (pos.getX() + 0.5D);
        final double dy = player.getY() - (pos.getY() + 0.5D);
        final double dz = player.getZ() - (pos.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz <= (double) range * range;
    }

    private static boolean insideSource(ServerPlayer player, BlockPos pos, int range) {
        final net.minecraft.world.phys.Vec3 world = Services.PLATFORM.audioSourcePosition(
                (ServerLevel) player.level(), pos);
        final double dx = player.getX() - world.x;
        final double dy = player.getY() - world.y;
        final double dz = player.getZ() - world.z;
        return dx * dx + dy * dy + dz * dz <= (double) range * range;
    }

    private static Snapshot snapshot(ServerLevel level, GoldenJukeboxBlockEntity source) {
        final List<SpeakerEntry> speakers = new ArrayList<>();
        for (SpeakerBlockEntity speaker : SpeakerNetwork.findLinkedSpeakers(level, source)) {
            speakers.add(new SpeakerEntry(speaker.getBlockPos(), speaker.getVolumePercent(),
                    speaker.isMuted(), orientation(speaker.getBlockState())));
        }
        speakers.sort((left, right) -> Long.compare(left.pos().asLong(), right.pos().asLong()));
        return new Snapshot(source.getRangeBlocks(), source.getVolumePercent(), source.isDirectional(),
                List.copyOf(speakers), source.playbackIdentity());
    }

    public static List<SpeakerEntry> movingSpeakers(ServerLevel level, UUID id, Object actor) {
        if (!GoldenSourceRegistry.ownsMovingSource(level, id, actor)) return List.of();
        final List<SpeakerEntry> speakers = new ArrayList<>();
        for (SpeakerBlockEntity speaker : SpeakerNetwork.findLinkedSpeakers(level, id)) {
            speakers.add(new SpeakerEntry(speaker.getBlockPos(), speaker.getVolumePercent(),
                    speaker.isMuted(), orientation(speaker.getBlockState())));
        }
        speakers.sort((a, b) -> Long.compare(a.pos().asLong(), b.pos().asLong()));
        return List.copyOf(speakers);
    }

    private static int orientation(BlockState state) {
        final AttachFace face = state.getValue(SpeakerBlock.FACE);
        final Direction facing = state.getValue(SpeakerBlock.FACING);
        final int horizontal = switch (facing) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> throw new IllegalStateException("Speaker facing is not horizontal: " + facing);
        };
        final int base = face.ordinal() * 4 + horizontal;
        return base + state.getValue(SpeakerBlock.HORN_TURN).orientationBand() * 12;
    }

    private static ServerLevel serverLevel(GoldenJukeboxBlockEntity source) {
        return source.getLevel() instanceof ServerLevel level && !source.isRemoved() ? level : null;
    }

    /** 単体配送から切り替える瞬間、どの新しい聴取点にも届かない旧decoderだけ解放する。 */
    private static void stopLegacyListenersOutside(ServerLevel level, BlockPos sourcePos, Snapshot snapshot,
            State previousState) {
        for (ServerPlayer player : level.players()) {
            if (!audible(player, sourcePos, snapshot)) {
                Services.NETWORK.sendToPlayer(player, new StopDiscPayload(sourcePos, snapshot.identity()));
                // 新しいnative開始では、旧managed listenerを残すとreconcile(replay=true)が
                // muted Speakerへ直後にPlayを送り直す。unmute時に現在offsetから入り直させる。
                if (previousState != null) {
                    previousState.listeners.remove(player.getUUID());
                }
            }
        }
    }
}
