package com.kuronami.musicdiscmaker.compat.create;

//? if >=1.21.2 {
//?} else {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;

import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.network.PacketDistributor;

/^*
 * MDM 音源ブロック (強化版ジュークボックス) が Create 捕獲式 contraption に組み込まれた時、凍結された
 * BlockEntity データから再生中の custom disc を読み取り、contraption を追跡中の client へ
 * {@link ContraptionPlayDiscPayload} を送る。client は {@link ContraptionAnchor} で音源を追従再生する。
 *
 * <p>捕獲式では組立時に原ブロックが world から抜かれ AIR 化されるため、固定 BlockPos に張り付いた
 * 従来の {@code StaticAnchor} 再生は自己消音する。この behaviour が contraption 追従の再生へ引き継ぐ。
 * server 主導 (client では tick を要求しない)。
 *
 * <p><b>ライフサイクル注意 (実機クラッシュから学んだ)</b>: Create の {@code startMoving} は
 * contraption entity が spawn される<b>前</b> (assemble 中: {@code Contraption.startMoving} ←
 * {@code BearingContraption.assemble}) に呼ばれるため、{@code context.contraption.entity} は
 * <b>null</b>。よって entity 参照・payload 送出は {@link #tick} へ遅延し、entity が非 null になった最初の
 * server tick から周期送信する (per-actor の次回送信時刻を {@code context.temporaryData} に置く。
 * この behaviour は全 actor 共有のシングルトンなので instance フィールドに状態を持てない)。
 * {@code tick} は {@code AbstractContraptionEntity.tickActors} 経由で毎 tick 呼ばれ、その時点では
 * entity は spawn 済み ({@code onEntityInitialize} で {@code contraption.entity} 設定済み)。
 ^/
public final class CreateAudioMovementBehaviour implements MovementBehaviour {

    /^* {@code context.temporaryData} に置く per-actor の送信間隔。途中から entity を追跡した client にも現在位置を渡す。 ^/
    private static final long RESEND_INTERVAL_TICKS = 20L;

    private static final class SendState {
        final java.util.Set<java.util.UUID> listeners = new java.util.HashSet<>();
        long nextSend;
        long startedAt;
        long offsetAtStart;
        com.kuronami.musicdiscmaker.network.PlaybackSourceStamp identity;
    }


    private static void stopPlayback(MovementContext context, SendState state) {
        if (state.identity == null) return;
        final var payload = ContraptionPlayDiscPayload.stop(context.contraption.entity.getId(),
                context.localPos, state.identity);
        for (final var player : ((net.minecraft.server.level.ServerLevel) context.world).players()) {
            if (state.listeners.contains(player.getUUID())) PacketDistributor.sendToPlayer(player, payload);
        }
        state.listeners.clear();
        state.identity = null;
    }

    @Override
    public void stopMoving(MovementContext context) {
        if (!(context.world instanceof net.minecraft.server.level.ServerLevel level)) return;
        com.kuronami.musicdiscmaker.event.GoldenSourceRegistry.unregisterMoving(level, context);
        if (!(context.temporaryData instanceof SendState state)) return;
        // Create calls this before restoring blocks. Persist the last partial resend interval first.
        final CompoundTag data = context.blockEntityData;
        if (state.identity != null && data != null && !data.isEmpty()) {
            final var captured = com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback
                    .read(level, context.localPos, data);
            if (captured.isPresent()) {
                final var playback = captured.get();
                final var identity = new com.kuronami.musicdiscmaker.network.PlaybackSourceStamp(
                        playback.sourceId(), playback.cursor().generation());
                if (identity.equals(state.identity)) {
                    final long audioTime = level.getServer() instanceof com.kuronami.musicdiscmaker.component.PlaybackClockAccess clock
                            ? clock.mdm$playbackTimeMs()
                            : com.kuronami.musicdiscmaker.component.PauseAwarePlaybackClock.realTimeMs();
                    com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback.advance(level,
                            context.localPos, data, state.offsetAtStart + Math.max(0L, audioTime - state.startedAt));
                }
            }
        }
        if (context.contraption != null && context.contraption.entity != null) stopPlayback(context, state);
        state.identity = null;
        state.nextSend = 0L;
    }

    @Override
    public void startMoving(MovementContext context) {
        registerSource(context);
    }

    private static void registerSource(MovementContext context) {
        if (!(context.world instanceof net.minecraft.server.level.ServerLevel level)) return;
        if (context.blockEntityData == null || context.blockEntityData.isEmpty()) {
            com.kuronami.musicdiscmaker.event.GoldenSourceRegistry.unregisterMoving(level, context);
            return;
        }
        final var id = com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback.sourceIdentity(
                level, context.localPos, context.blockEntityData);
        if (id == null) {
            com.kuronami.musicdiscmaker.event.GoldenSourceRegistry.unregisterMoving(level, context);
            return;
        }
        com.kuronami.musicdiscmaker.event.GoldenSourceRegistry.registerMoving(level, id, context);
    }

    @Override
    public void tick(MovementContext context) {
        // entity が揃ってから周期送信する (startMoving は spawn 前で entity=null)。
        if (context.world == null || context.world.isClientSide()) {
            return;
        }
        if (context.contraption == null || context.contraption.entity == null) {
            return; // entity 未 spawn。次 tick を待つ (assemble 直後の 1 tick 窓)。
        }
        final SendState state;
        if (context.temporaryData instanceof SendState previous) state = previous;
        else {
            state = new SendState();
            context.temporaryData = state;
        }
        final long now = context.world.getGameTime();
        if (now < state.nextSend) return;
        state.nextSend = now + RESEND_INTERVAL_TICKS;
        // Rehydrate source ownership after loading an already assembled contraption.
        registerSource(context);

        final CompoundTag beData = context.blockEntityData;
        if (beData == null || beData.isEmpty()) {
            stopPlayback(context, state);
            return;
        }
        if (!(context.world instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;
        final var captured = com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback
                .read(serverLevel, context.localPos, beData);
        if (captured.isEmpty()) {
            stopPlayback(context, state);
            return;
        }
        var playback = captured.get();
        var identity = new com.kuronami.musicdiscmaker.network.PlaybackSourceStamp(
                playback.sourceId(), playback.cursor().generation());
        final long audioTime = serverLevel.getServer() instanceof com.kuronami.musicdiscmaker.component.PlaybackClockAccess clock
                ? clock.mdm$playbackTimeMs()
                : com.kuronami.musicdiscmaker.component.PauseAwarePlaybackClock.realTimeMs();
        if (!identity.equals(state.identity)) {
            state.identity = identity;
            state.startedAt = audioTime;
            state.offsetAtStart = playback.offsetMs();
        }
        long offsetMs = state.offsetAtStart + Math.max(0L, audioTime - state.startedAt);
        final var advanced = com.kuronami.musicdiscmaker.component.CapturedGoldenPlayback
                .advance(serverLevel, context.localPos, beData, offsetMs);
        if (advanced.isEmpty()) {
            stopPlayback(context, state);
            return;
        }
        playback = advanced.get();
        identity = new com.kuronami.musicdiscmaker.network.PlaybackSourceStamp(
                playback.sourceId(), playback.cursor().generation());
        if (!identity.equals(state.identity)) {
            state.identity = identity;
            state.startedAt = audioTime;
            state.offsetAtStart = playback.offsetMs();
            offsetMs = playback.offsetMs();
        }
        final CustomTrackData track = playback.custom() != null ? playback.custom() : CustomTrackData.EMPTY;
        final int range = playback.range();
        final int volume = playback.volume();
        final boolean directional = playback.directional();

        final int entityId = context.contraption.entity.getId();
        final var receivers = new com.kuronami.musicdiscmaker.network.MovingSpeakerState(
                context.position != null ? context.position : context.contraption.entity.position(),
                com.kuronami.musicdiscmaker.event.SpeakerPlayback.movingSpeakers(serverLevel, playback.sourceId(), context));
        final ContraptionPlayDiscPayload payload = new ContraptionPlayDiscPayload(
                entityId, context.localPos, track, offsetMs, range, volume, directional,
                identity, playback.vanilla(), receivers);
        final java.util.Set<java.util.UUID> present = new java.util.HashSet<>();
        for (final var player : serverLevel.players()) {
            final boolean listening = state.listeners.contains(player.getUUID());
            if (receivers.reaches(player.position(), range, volume, listening)) {
                PacketDistributor.sendToPlayer(player, payload);
                present.add(player.getUUID());
            } else if (listening) {
                PacketDistributor.sendToPlayer(player, ContraptionPlayDiscPayload.stop(entityId, context.localPos, identity));
            }
        }
        state.listeners.clear();
        state.listeners.addAll(present);
        // 診断 (debug 既定 off): 実機で「追従しない」時、この行が出て CreateAudioClient.play の
        // 受信ログが出なければ tracker 未確立/配送を疑う (この時点なら entity は tracker を持つはず)。
        MusicDiscMaker.LOGGER.debug("Sent Create contraption playback: entityId={} localPos={} offset={}ms",
                entityId, context.localPos, offsetMs);
    }

}

*///?}
