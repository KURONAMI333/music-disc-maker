package com.kuronami.musicdiscmaker.client.audio;
import java.util.concurrent.CompletableFuture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.HashMap;
import java.util.Map;
import com.kuronami.musicdiscmaker.MusicDiscMaker;

import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import org.jetbrains.annotations.Nullable;
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
import com.kuronami.musicdiscmaker.network.UrlBlockedException;
import com.kuronami.musicdiscmaker.network.UrlGuard;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;
import com.kuronami.musicdiscmaker.network.PlaybackSourceStamp;

import net.minecraft.client.Minecraft;
//? if >=26.2 {
//?}
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * client 側の再生統括。jukebox 位置ごとに 1 再生を管理する。
 * URL ロードはブロックするので別 thread、SoundManager 操作は main thread。
 *
 * <p>「どうするか」の判断と状態は {@link PlaybackSessions} が持ち、ここには MC を触る操作
 * (スレッド移動・{@code SoundManager}・チャット表示) だけを残してある。判断を分けてあるのは、
 * ここが {@code Minecraft} を掴んでいて headless テストに載らないから。
 *
 * <p><b>ロードは開始時点の世代 (token) を最後まで持ち回る。</b> 途中で世代を落として座標だけで
 * 判定に戻ると、同じ座標でディスクを差し替えたときに 2 曲同時に鳴る (理由は
 * {@link PlaybackSessions} の javadoc)。
 *
 * <p>ラジオ (無限長ストリーム) は瞬断で lavaplayer の track が終了する。終端 (read=-1) を
 * {@link DiscSoundInstance} 経由で検知し、間隔を置いて最大 {@link PlaybackSessions#MAX_RECONNECT}
 * 回まで自動再接続する。安定再生できたら試行回数はリセットする。
 */
public final class ClientPlaybackManager {
    /**
     * 経過時間の物差し。壁時計と違って外から巻き戻らない。
     *
     * <p>{@link PlaybackSessions} の安定判定と初音の期限はここを読む。壁時計で測ると、
     * NTP 同期や手動の時刻変更が入った瞬間に「まだ鳴っていない」「もう期限切れ」が
     * 一斉に誤判定される。
     */
    private static final java.util.function.LongSupplier MONOTONIC_MS =
            () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime());

    private static final ClientPlaybackManager INSTANCE = new ClientPlaybackManager();

    /** 再接続を試みる前に置く間隔 (ms)。icecast の瞬断復帰を待つ。 */
    private static final long RECONNECT_DELAY_MS = 3_000L;

    /** A transient regular finite-track network open gets two short local retries. */
    private static final int FINITE_OPEN_RETRIES = 2;
    private static final long FINITE_OPEN_RETRY_DELAY_MS = 1_500L;
    /** 有限曲が再生途中で途切れた時、現在位置から開き直すまでの間隔 (ms)。位置は開く時に補正される。 */
    private static final long FINITE_RESUME_DELAY_MS = 1_500L;

    /**
     * 音源座標の決定を compat へ開く口。
     *
     * <p>移動構造物 (Valkyrien Skies 2 の物理船) に載った jukebox は、ブロックの座標が shipyard の
     * ままなので音源だけが遠くに残る (見えないスピーカー)。船管理下なら毎 tick 剛体変換で world 座標へ
     * 追従するアンカーへ差し替える必要がある。<b>その判断は帯によって実装が違う</b> ── VS2 の型は
     * その帯にしか無いので、共通ソースから直接は書けない。
     *
     * <p>そこで<b>口だけを全帯共通にし、実装は loader entry が
     * {@link #setShipAnchorResolver} で差し込む</b>。差し込みが無い帯では {@link #shipAnchor} が
     * 常に {@code null} を返し、従来の {@link StaticAnchor} 再生になる。
     * 口は関数型なので<b>実装側の名前をコンパイラが見る</b> ({@code TAB_ITEMS} や
     * {@code INetworkHelper} と同じで、名前を変えれば登録側のコンパイルが落ちる)。
     */
    private static BiFunction<ClientLevel, BlockPos, DiscAnchor> shipAnchorResolver;

    public static ClientPlaybackManager get() {
        return INSTANCE;
    }

    private final PlaybackSessions<BlockPos> sessions =
            new PlaybackSessions<>(System::currentTimeMillis, MONOTONIC_MS, TrackAudioResolver::playbackClockMs);

    /** world 音源ごとの最新 server 要求。SoundEngine 差し替え時に同じ曲を開き直すためだけに保持する。 */
    private record ReloadRequest(CustomTrackData track, long startOffsetMs, int rangeBlocks,
            int volumePercent, boolean directional, long receivedPlaybackMs, PlaybackSourceStamp identity,
            PlaybackSessionId extensionId, PlaybackSourceContext sourceContext, PlaybackSelection selection,
            boolean regularOnly) {}
    private record CloseRoute(BlockPos pos, long worldEpoch) {}
    private record CloseBarrier(PlaybackSessionId closingId, CompletableFuture<Void> acknowledged, long worldEpoch) {}
    private final Map<BlockPos, ReloadRequest> reloadRequests = new HashMap<>();
    private final Map<BlockPos, PlaybackSessionId> extensionIds = new java.util.concurrent.ConcurrentHashMap<>();
    /** Resolver work registered before dispatch, so close can wait for an open not yet in the registry. */
    private final Map<PlaybackSessionId, CompletableFuture<Void>> resolverSettled = new java.util.concurrent.ConcurrentHashMap<>();
    /** A source UUID keeps its first close ACK barrier while the same source moves between positions. */
    private final CloseBarrierIndex<CloseRoute, CloseBarrier> closeBarriers = new CloseBarrierIndex<>();
    private final Map<BlockPos, PendingCoreActions.Action> pendingCoreActions = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<java.util.UUID, PendingCoreActions.Action> pendingSourceCoreActions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong pendingActionOrder = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong worldEpoch = new java.util.concurrent.atomic.AtomicLong();
    private final PlaybackFaultGate<BlockPos> faultGate = new PlaybackFaultGate<>();

    /** Golden音源位置ごとの固定speaker集合。値は進行中の音声インスタンスにも共有される。 */
    private final Map<BlockPos, MultiSpeakerAnchor> speakerAnchors = new HashMap<>();
    /** 位置ごとに最後に「Now Playing」を出した曲と再生の世代。単曲 repeat の判定に使う ({@link #mayAnnounce})。 */
    private final Map<BlockPos, AnnouncedTrack> announced = new HashMap<>();
    private record AnnouncedTrack(String sourceKey, int token) {}

    //? if >=1.21.2 {
    //?} elif >=1.21 {
/*    private final PlaybackPrefetch<BlockPos> prefetch = new PlaybackPrefetch<>(System::currentTimeMillis,
            this::logPrefetchEvent);
    */
    //?} else {
    //?}

    /**
     * 座標ごとの直近の失敗。金ジュークの画面がここを読んで短い文を出す。
     *
     * <p>ストリームは client ごとに開くので、同じ音源でも片方の client だけ失敗しうる。
     * だから BlockEntity の同期には載せず client の中だけで覚える ({@link GoldenJukeboxFailures})。
     */
    private final GoldenJukeboxFailures failures = GoldenJukeboxFailures.get();

    private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-playback");
        thread.setDaemon(true);
        return thread;
    });

    private ClientPlaybackManager() {
        // 同時再生の上限は経路をまたいで 1 箇所で数える (OpenAL の streaming プールは 1 つ)。
        PlaybackConcurrency.client().register(sessions);
    }

    //? if >=1.21 {
    public void startPlayback(BlockPos pos, CustomTrackData track, long startOffsetMs) {
        startPlayback(pos, track, startOffsetMs, 0, 100, true);
    }
    //?}

    public void startPlayback(BlockPos pos, CustomTrackData track, long startOffsetMs,
            int rangeBlocks, int volumePercent, boolean directional) {
        startPlaybackWithIdentity(pos, track, startOffsetMs, rangeBlocks, volumePercent, directional,
                PlaybackSourceStamp.NONE);
    }

    public void startPlaybackWithIdentity(BlockPos pos, CustomTrackData track, long startOffsetMs,
            int rangeBlocks, int volumePercent, boolean directional, PlaybackSourceStamp sourceStamp) {
        startPlaybackInternal(pos, track, startOffsetMs, rangeBlocks, volumePercent, directional, sourceStamp, false);
    }

    private void startPlaybackInternal(BlockPos pos, CustomTrackData track, long startOffsetMs,
            int rangeBlocks, int volumePercent, boolean directional, PlaybackSourceStamp sourceStamp,
            boolean regularOnly) {
        if (track == null || track.isEmpty()) return;
        final BlockPos key = pos.immutable();
        final PlaybackSourceStamp identity = sourceStamp == null ? PlaybackSourceStamp.NONE : sourceStamp;
        if (queueBehindClose(key, identity, () -> startPlaybackInternal(key, track, startOffsetMs,
                rangeBlocks, volumePercent, directional, identity, regularOnly))) return;
        final ReloadRequest previousRequest = reloadRequests.get(key);
        final PlaybackSourceContext sourceContext = PlaybackSourceContext.of(key, identity);
        final PlaybackSelection selection = regularOnly
                ? PlaybackSelection.none(sourceContext, track.sourceKey())
                : PlaybackSessionRegistry.select(sourceContext, track);
        final PlaybackSessionId activeExtension = extensionIds.get(key);
        final boolean sameSource = previousRequest != null
                && previousRequest.identity().sourceId() != null
                && previousRequest.identity().sourceId().equals(identity.sourceId());
        final boolean selectionChanged = sameSource
                && previousRequest.selection().selected() != selection.selected();
        if (activeExtension != null && (previousRequest == null
                || !previousRequest.identity().equals(identity)
                || previousRequest.identity().generation() != identity.generation()
                || !previousRequest.track().sourceKey().equals(track.sourceKey())
                || selectionChanged || previousRequest.regularOnly() != regularOnly
                || sessions.wouldReload(key, track, startOffsetMs))) {
            beginCloseBarrier(key, activeExtension);
            runAfterClose(key, identity, () -> startPlaybackInternal(key, track, startOffsetMs,
                    rangeBlocks, volumePercent, directional, identity, regularOnly));
            return;
        }
        if (selectionChanged && activeExtension == null) {
            // The server resent the same source/track after provider eligibility changed. Invalidate
            // only this key so the regular path can be replaced by one fresh provider selection.
            sessions.stop(key);
            reloadRequests.remove(key);
        }
        if (previousRequest != null && !previousRequest.identity().equals(identity)) {
            sessions.stop(key);
        }
        final PlaybackSessions.StartDecision decision =
                sessions.start(key, track, startOffsetMs, rangeBlocks, volumePercent, directional);
        if (!decision.load()) {
            if (previousRequest != null) {
                reloadRequests.put(key, new ReloadRequest(track, startOffsetMs, rangeBlocks, volumePercent,
                        directional, playbackClockMs(), previousRequest.identity(), previousRequest.extensionId(),
                        previousRequest.sourceContext(), selection, regularOnly));
            }
            return;
        }
        final PlaybackSessionId id = selection.selected() && !regularOnly
                ? new PlaybackSessionId(identity.sourceId() == null
                        ? "pos:" + key.asLong() : identity.sourceId().toString(), identity.generation(), decision.token())
                : null;
        if (id != null) {
            final PlaybackSessionId oldId = extensionIds.put(key, id);
            if (oldId != null && !oldId.equals(id)) throw new IllegalStateException("unclosed playback session replaced");
            faultGate.activate(key, decision.token(), id);
        }
        final ReloadRequest request = new ReloadRequest(track, startOffsetMs, rangeBlocks, volumePercent,
                directional, playbackClockMs(), identity, id, sourceContext, selection, regularOnly);
        reloadRequests.put(key, request);
        if (mayAnnounce(key, decision.token())) announceRequestedTrack(track);
        //? if >=1.21.2 {
        final PlaybackTiming timing = new PlaybackTiming(key.toShortString(), track.sourceKey(), decision.token(), false);
        submitLoad(key, track, startOffsetMs, rangeBlocks, volumePercent, 0L, decision.token(), timing,
                id, sourceContext, selection, regularOnly);
        //?} elif >=1.21 {
/*        final IAudioSource warm = selection.selected() || regularOnly ? null
                : prefetch.claim(key, track.sourceKey(), startOffsetMs);
        final PlaybackTiming timing =
                new PlaybackTiming(key.toShortString(), track.sourceKey(), decision.token(), warm != null);
        if (warm != null) {
            timing.resolved();
            attachFaultSink(warm, key, decision.token(), track);
            Minecraft.getInstance().execute(() -> onLoaded(key, track, startOffsetMs, startOffsetMs, rangeBlocks,
                    volumePercent, warm, null, decision.token(), timing));
            return;
        }
        submitLoad(key, track, startOffsetMs, rangeBlocks, volumePercent, 0L, decision.token(), timing,
                id, sourceContext, selection, regularOnly);
    }

    public void prefetchNext(BlockPos pos, CustomTrackData track, long remainingMs) {
        if (track == null || track.isEmpty() || track.radio() || track.durationMs() <= 0L) {
            return; // ラジオ・尺ゼロは先読みしない (終わらない / 掴む先が無い)
        }
        if (track.isLocal()) return;
        final BlockPos sourcePos = pos.immutable();
        final ReloadRequest activeRequest = reloadRequests.get(sourcePos);
        final PlaybackSourceStamp stamp = activeRequest != null ? activeRequest.identity()
                : Minecraft.getInstance().level != null
                        && Minecraft.getInstance().level.getBlockEntity(sourcePos) instanceof GoldenJukeboxBlockEntity be
                                ? new PlaybackSourceStamp(be.peekSourceIdentity(), 0L) : PlaybackSourceStamp.NONE;
        final PlaybackSourceContext context = PlaybackSourceContext.of(sourcePos, stamp);
        final PlaybackSelection selection = PlaybackSessionRegistry.select(context, track);
        if (!selection.allowsRegularPrefetch()) {
            prefetch.drop(sourcePos, "optional-av-selected");
            return;
        }
        final PlaybackPrefetch.Ticket<BlockPos> ticket = prefetch.begin(sourcePos, track.sourceKey(), remainingMs);
        if (ticket == null) {
            return; // 曲が終了済み / 同じ曲を既に抱えている / 先読み枠の上限
        }
        MusicDiscMaker.LOGGER.debug("Prefetch fired [{}] url={} remaining={}ms ticket={}",
                pos.toShortString(), track.sourceKey(), remainingMs, ticket.id());
        pool.submit(() -> {
            IAudioSource source = null;
            try {
                if (!track.isLocal()) UrlGuard.enforce(track.url());
                final OpenStreamResult result = com.kuronami.musicdiscmaker.client.audio.TrackAudioResolver.open(track, 0L);
                source = result.source();
                if (source == null) {
                    MusicDiscMaker.LOGGER.warn("Prefetch could not open {} [{}] {}",
                            track.sourceKey(), result.reason(), result.detail());
                }
            } catch (final Throwable t) {
                source = null;
                MusicDiscMaker.LOGGER.warn("Prefetch threw while opening {}", track.sourceKey(), t);
            }
            if (!prefetch.deliver(ticket, source) && source != null) {
                MusicDiscMaker.LOGGER.debug("Prefetch discarded (no longer wanted) for {}", track.sourceKey());
                source.close();
            }
        });
    }

    public void cancelPrefetch(BlockPos pos, String reason) {
        prefetch.drop(pos.immutable(), reason);
    }

    public void cancelPrefetchUnlessMatches(BlockPos pos, CustomTrackData current, CustomTrackData next, String reason) {
        prefetch.dropUnlessMatches(pos.immutable(), current == null ? null : current.sourceKey(),
                next == null ? null : next.sourceKey(), reason);
    }

    private void logPrefetchEvent(String action, BlockPos key, String url, long id, String detail) {
        if ("open-failed".equals(action)) {
            MusicDiscMaker.LOGGER.warn("Prefetch {} [{}] url={} ticket={} {}", action, key.toShortString(), url, id, detail);
            return;
        }
        MusicDiscMaker.LOGGER.debug("Prefetch {} [{}] url={} ticket={} {}", action, key.toShortString(), url, id, detail);
    }


    private void attachFaultSink(IAudioSource source, BlockPos key, int token, CustomTrackData track) {
        source.onPlaybackFault(broken -> Minecraft.getInstance().execute(
                () -> lateFailure(key, token, track,
                        PlaybackFailure.ofReason(broken.reason(), broken.detail()))));
        */
//?} else {
/*        submitLoad(key, track, startOffsetMs, rangeBlocks, volumePercent, 0L, decision.token(),
                id, sourceContext, selection, regularOnly);
        */
        //?}
    }

    private boolean queueBehindClose(BlockPos key, PlaybackSourceStamp identity, Runnable action) {
        final long currentEpoch = worldEpoch.get();
        if (closeBarriers.byRoute(new CloseRoute(key, currentEpoch)) != null) {
            pendingCoreActions.compute(key, (ignored, previous) -> {
                if (previous != null && previous.worldEpoch() == worldEpoch.get()
                        && previous.identity() != null && identity != null
                        && java.util.Objects.equals(previous.identity().sourceId(), identity.sourceId())
                        && previous.identity().generation() > identity.generation()) return previous;
                return pendingAction(key, identity, action);
            });
            return true;
        }
        final java.util.UUID sourceId = identity == null ? null : identity.sourceId();
        if (sourceId == null || closeBarriers.bySource(sourceId) == null) return false;
        pendingSourceCoreActions.compute(sourceId, (ignored, previous) -> {
            if (previous != null && previous.worldEpoch() == worldEpoch.get()
                    && previous.identity() != null && identity != null
                    && previous.identity().generation() > identity.generation()) return previous;
            return pendingAction(key, identity, action);
        });
        return true;
    }

    /** Runs now when an immediate no-provider/ACK path already removed the barrier. */
    private void runAfterClose(BlockPos key, PlaybackSourceStamp identity, Runnable action) {
        if (!queueBehindClose(key, identity, action)) action.run();
    }

    private PendingCoreActions.Action pendingAction(BlockPos key, PlaybackSourceStamp identity, Runnable action) {
        return new PendingCoreActions.Action(key, identity, worldEpoch.get(), pendingActionOrder.incrementAndGet(), action);
    }

    /** Starts one source-key close and keeps its first acknowledgement until cleanup succeeds. */
    private CloseBarrier beginCloseBarrier(BlockPos key, PlaybackSessionId closingId) {
        return beginCloseBarrier(key, closingId, worldEpoch.get());
    }

    private CloseBarrier beginCloseBarrier(BlockPos key, PlaybackSessionId closingId, long barrierEpoch) {
        final java.util.UUID sourceId = uuidSourceId(closingId);
        final CloseRoute route = new CloseRoute(key, barrierEpoch);
        CloseBarrier existing = closeBarriers.byRoute(route);
        if (existing != null) return existing;
        if (sourceId != null && (existing = closeBarriers.bySource(sourceId)) != null) return existing;
        CompletableFuture<Void> acknowledged = new CompletableFuture<>();
        CloseBarrier created = new CloseBarrier(closingId, acknowledged, barrierEpoch);
        existing = closeBarriers.register(route, sourceId, created);
        if (existing != null) return existing;
        CompletableFuture<Void> opening = resolverSettled.get(closingId);
        try {
            // If resolution is already settled, capture the registry's first close future before
            // stopping the core voice: its source.close() can otherwise remove a failed entry
            // before we ask for that future, making a second close look like a successful no-op.
            CompletableFuture<Void> close;
            if (opening == null) {
                close = PlaybackSessionRegistry.close(closingId);
                sessions.stop(key);
            } else {
                // A resolver may still publish a provider session. Invalidate its token first,
                // then capture close only after the registered resolver/open work has settled.
                sessions.stop(key);
                close = opening.thenCompose(ignored -> PlaybackSessionRegistry.close(closingId));
            }
            reloadRequests.remove(key);
            close.whenComplete((ignored, failure) -> {
                if (failure == null) acknowledged.complete(null);
                else acknowledged.completeExceptionally(failure);
            });
        } catch (RuntimeException | LinkageError failure) {
            sessions.stop(key);
            reloadRequests.remove(key);
            acknowledged.completeExceptionally(failure);
        }
        if (acknowledged.isDone()) {
            Throwable failure = null;
            try {
                acknowledged.join();
            } catch (java.util.concurrent.CompletionException | java.util.concurrent.CancellationException completedFailure) {
                failure = completedFailure.getCause() == null ? completedFailure : completedFailure.getCause();
            }
            finishCloseBarrier(key, created, failure);
        } else {
            acknowledged.whenComplete((ignored, failure) -> Minecraft.getInstance().execute(
                    () -> finishCloseBarrier(key, created, failure)));
        }
        return created;
    }

    private void finishCloseBarrier(BlockPos key, CloseBarrier barrier, Throwable failure) {
        final java.util.UUID sourceId = uuidSourceId(barrier.closingId());
        final CloseRoute route = new CloseRoute(key, barrier.worldEpoch());
        if (closeBarriers.byRoute(route) != barrier && (sourceId == null || closeBarriers.bySource(sourceId) != barrier)) return;
        if (failure != null) {
            MusicDiscMaker.LOGGER.error("Optional AV close was not acknowledged; source playback remains suppressed", failure);
            return;
        }
        closeBarriers.remove(route, sourceId, barrier);
        ExtensionSessionRetirement.removeIfSame(extensionIds, key, barrier.closingId());
        faultGate.remove(key, barrier.closingId());
        ReloadRequest current = reloadRequests.get(key);
        if (current != null && barrier.closingId().equals(current.extensionId())) reloadRequests.remove(key);
        final long currentWorldEpoch = worldEpoch.get();
        PendingCoreActions.Action routePending = PendingCoreActions.mayDrainAfterClose(
                barrier.worldEpoch(), currentWorldEpoch) ? pendingCoreActions.remove(key) : null;
        PendingCoreActions.Action sourceCandidate = sourceId == null ? null : pendingSourceCoreActions.get(sourceId);
        PendingCoreActions.Action sourcePending = PendingCoreActions.mayDrainSourceAfterClose(
                sourceId, sourceCandidate, currentWorldEpoch)
                ? (pendingSourceCoreActions.remove(sourceId, sourceCandidate) ? sourceCandidate : null)
                : null;
        for (PendingCoreActions.Action pending : PendingCoreActions.select(routePending, sourcePending)) {
            if (pending.worldEpoch() != worldEpoch.get()) continue;
            PlaybackSourceStamp identity = pending.identity();
            if (identity != null && identity.sourceId() != null
                    && !ClientPlaybackHandler.ownsSourceRoute(identity, (BlockPos) pending.route())) continue;
            pending.action().run();
        }
    }

    private static java.util.UUID uuidSourceId(PlaybackSessionId id) {
        try { return java.util.UUID.fromString(id.sourceId()); }
        catch (IllegalArgumentException notUuid) { return null; }
    }

    /** speaker集合を再デコードなしで置き換える。Playより先に届いてもcacheしておく。 */
    public void updateSpeakerSet(SpeakerSetPayload payload) {
        final BlockPos key = payload.sourcePos().immutable();
        final MultiSpeakerAnchor anchor = speakerAnchors.compute(key, (ignored, existing) -> {
            if (existing == null) {
                return new MultiSpeakerAnchor(payload);
            }
            existing.update(payload);
            return existing;
        });
        final PlaybackVoice active = sessions.activeVoice(key);
        if (active instanceof DiscSoundInstance instance) {
            instance.useSpeakerAnchor(anchor);
        } else if (active instanceof SpeakerVoiceGroup group) {
            group.update(payload);
        }
    }

    /**
     * URL を (任意の遅延後) 別 thread でロードし、成功したら main thread で再生を開始する。
     * startPlayback (遅延0) とラジオ再接続 (遅延あり) の共通経路。
     *
     * <p>{@code token} は最後まで持ち回る。ロード中に停止・差し替えが起きると世代が進むので、
     * 完了時の照合で「もう要らないロード」を捨てられる。
     */
    private void submitLoad(BlockPos key, CustomTrackData track, long startOffsetMs, int rangeBlocks,
            //? if >=1.21.2 {
            int volumePercent, long delayMs, int token, PlaybackTiming timing,
            //?} elif >=1.21 {
/*            int volumePercent, long delayMs, int token, PlaybackTiming timing,
            */
            //?} else {
/*            int volumePercent, long delayMs, int token,
            */
            //?}
            PlaybackSessionId extensionId, PlaybackSourceContext sourceContext,
            PlaybackSelection selection, boolean regularOnly) {
        final long requestedAtMs = TrackAudioResolver.playbackClockMs();
        final BlockPos immutableKey = key.immutable();
        final CustomTrackData immutableTrack = track;
        final int immutableToken = token;
        final PlaybackSessionId immutableExtensionId = extensionId;
        final PlaybackSourceContext immutableSourceContext = sourceContext;
        final PlaybackSelection immutableSelection = selection;
        final boolean immutableRegularOnly = regularOnly;
        final CompletableFuture<Void> resolverCompletion = new CompletableFuture<>();
        if (immutableExtensionId != null) {
            resolverSettled.put(immutableExtensionId, resolverCompletion);
            resolverCompletion.whenComplete((ignored, failure) ->
                    resolverSettled.remove(immutableExtensionId, resolverCompletion));
        }
        pool.submit(() -> {
            boolean completionFollowsCleanupAck = false;
            try {
            if (delayMs > 0L) {
                try {
                    Thread.sleep(delayMs);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!sessions.isLive(immutableKey, immutableToken)) {
                return; // 遅延中に停止/撤去/差し替えされた
            }
            IAudioSource source = null;
            PlaybackFailure failure = null;
            //? if >=1.21.2 {
            timing.beginResolve();
            //?} elif >=1.21 {
/*            timing.beginResolve();
            */
            //?} else {
            //?}
            long openOffsetMs = startOffsetMs;
            if (delayMs > 0L && !immutableTrack.radio() && !immutableTrack.isLocal() && immutableTrack.durationMs() > 0L) {
                // 待っている間も server の曲は進む。ネット音源の open は要求時刻で位置を補正しないので、
                // 待った分をここで足す (足さないと、開き直した client だけ待機時間ぶん遅れて鳴る)。
                final long waited = Math.max(0L, TrackAudioResolver.playbackClockMs() - requestedAtMs);
                openOffsetMs = Math.max(0L, startOffsetMs) + Math.min(waited, Long.MAX_VALUE - Math.max(0L, startOffsetMs));
            }
            for (int retry = 0; ; retry++) {
                if (!sessions.isLive(immutableKey, immutableToken)) return;
                try {
                    // SSRF 遮断: 悪意ある disc データ (内部 IP URL) で他プレイヤーの client を踏み台にさせない
                    if (!immutableTrack.isLocal()) UrlGuard.enforce(immutableTrack.url());
                    final Consumer<Throwable> providerFaultSink = fault -> Minecraft.getInstance().execute(
                            () -> fallbackAfterExtensionFailure(immutableKey, immutableToken, immutableTrack,
                                    immutableExtensionId, PlaybackFailure.thrown(fault)));
                    final OpenStreamResult result = immutableRegularOnly
                            ? com.kuronami.musicdiscmaker.client.audio.TrackAudioResolver.openSynced(
                                    immutableTrack, openOffsetMs, requestedAtMs)
                            : com.kuronami.musicdiscmaker.client.audio.TrackAudioResolver.openSyncedForPlayback(
                                    immutableTrack, openOffsetMs, requestedAtMs, immutableExtensionId,
                                    immutableSourceContext, immutableSelection, providerFaultSink,
                                    // ローカル音源・拡張なしでは session id が null (select が none)。
                                    // その時は再生の生存だけを見る (共有音源の取得待ちの打ち切り判定にも使われる)。
                                    () -> sessions.isLive(immutableKey, immutableToken)
                                            && (immutableExtensionId == null
                                                    || immutableExtensionId.equals(extensionIds.get(immutableKey))));
                    source = result.source();
                    failure = result.isOk() ? null
                            : PlaybackFailure.ofReason(result.reason(), result.detail());
                } catch (final UrlBlockedException blocked) {
                    failure = PlaybackFailure.blocked(blocked.reason());
                } catch (final PlaybackSessionRegistry.CleanupIncompleteException cleanup) {
                    // Never turn an unacknowledged AV cleanup into a regular retry or a second voice.
                    failure = PlaybackFailure.thrown(cleanup);
                    completionFollowsCleanupAck = true;
                    cleanup.cleanupAcknowledged().whenComplete((ignored, cleanupFailure) -> {
                        if (cleanupFailure == null) resolverCompletion.complete(null);
                        else resolverCompletion.completeExceptionally(cleanupFailure);
                    });
                } catch (final Throwable t) {
                    failure = PlaybackFailure.thrown(t);
                }
                if (source != null || retry >= FINITE_OPEN_RETRIES || failure == null
                        || !FiniteOpenRetryPolicy.mayRetry(immutableTrack.radio(), immutableTrack.isLocal(),
                                immutableSelection.selected(), immutableRegularOnly,
                                (failure.kind() == PlaybackFailure.Kind.NETWORK
                                        || failure.kind() == PlaybackFailure.Kind.STREAM_UNAVAILABLE))) break;
                try {
                    Thread.sleep(FINITE_OPEN_RETRY_DELAY_MS * (retry + 1));
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!sessions.isLive(immutableKey, immutableToken)) return;
                final long elapsed = Math.max(0L, TrackAudioResolver.playbackClockMs() - requestedAtMs);
                final long baseOffset = Math.max(0L, startOffsetMs);
                openOffsetMs = baseOffset + Math.min(elapsed, Long.MAX_VALUE - baseOffset);
                if (immutableTrack.durationMs() > 0L && openOffsetMs >= immutableTrack.durationMs()) break;
            }
            final IAudioSource resolved = source;
            final long actualStartMs = TrackAudioResolver.actualOffset(resolved, openOffsetMs);
            final long dedupOffsetMs = immutableTrack.isLocal() ? actualStartMs : startOffsetMs;
            final PlaybackFailure reported = failure;
            //? if >=1.21.2 {
            timing.resolved();
            //?} elif >=1.21 {
/*            timing.resolved();
            */
            //?} else {
            //?}
            // 失敗の届け先は「ソースを受け取った直後・main thread へ渡す前」に差す。再生スレッドは
            // ここから数百 ms で落ちうるので、play が始まってから差していると取り落とす窓ができる
            // (差した時点で既に壊れていれば relay がその場で流すので、順番はどちらでもよい)。
            if (resolved != null) {
                final PlaybackSessionId resolvedId = PlaybackSessionRegistry.sessionIdFor(resolved).orElse(null);
                resolved.onPlaybackFault(broken -> Minecraft.getInstance().execute(() ->
                        handlePlaybackFault(immutableKey, immutableToken, immutableTrack, resolvedId,
                                PlaybackFailure.ofReason(broken.reason(), broken.detail()))));
            }
            //? if >=1.21.2 {
            Minecraft.getInstance().execute(() -> onLoaded(immutableKey, immutableTrack, actualStartMs, dedupOffsetMs, rangeBlocks,
                    volumePercent, resolved, reported, immutableToken, timing));
            //?} elif >=1.21 {
/*            Minecraft.getInstance().execute(() -> onLoaded(immutableKey, immutableTrack, actualStartMs, dedupOffsetMs, rangeBlocks,
                    volumePercent, resolved, reported, immutableToken, timing));
            */
            //?} else {
/*            Minecraft.getInstance().execute(() -> onLoaded(
                    immutableKey, immutableTrack, actualStartMs, dedupOffsetMs, rangeBlocks, volumePercent, resolved, reported, immutableToken));
            */
            //?}
            } finally {
                if (!completionFollowsCleanupAck) resolverCompletion.complete(null);
            }
        });
    }

    private static boolean retryFiniteOpen(CustomTrackData track, PlaybackFailure failure) {
        if (track.radio() || track.isLocal() || failure == null) return false;
        return failure.kind() == PlaybackFailure.Kind.NETWORK
                || failure.kind() == PlaybackFailure.Kind.STREAM_UNAVAILABLE;
    }

    /**
     * 再生スレッドの中で落ちた失敗を受ける (main thread から呼ぶこと)。push (ソース直結) と
     * pull (ストリーム終端) の両方がここへ集まる。出すかどうかの判断は {@link PlaybackSessions} 側。
     */
    private void lateFailure(BlockPos key, int token, CustomTrackData track, PlaybackFailure late) {
        lateFailure(key, token, track, late, null);
    }

    private void lateFailure(BlockPos key, int token, CustomTrackData track, PlaybackFailure late,
            @Nullable PlaybackVoice origin) {
        if (resumeFiniteStream(key, token, track, late, origin)) {
            return;
        }
        final PlaybackFailure show = sessions.lateFailure(key, token, track, late);
        if (show != null) {
            failures.record(key, show);
            PlaybackFailureReport.report(track, show);
        }
    }

    /** server の時計で曲末からこの範囲に届いた停止を、曲の終わりによる停止とみなす。 */
    private static final long NATURAL_END_WINDOW_MS = 2_000L;
    /** 曲の終わりの停止を待つ上限。これより遅れている client は、待った後に残りを切る。 */
    private static final long NATURAL_END_MAX_WAIT_MS = 10_000L;

    /**
     * server が曲尺で送ってきた停止を、この client の音が鳴り終わるまで遅らせる。
     *
     * <p>server は曲の再生を要求した時刻から数えて曲尺で止めるが、URL 音源の client は解決と読み込み
     * (実測で初回約2秒・2回目以降0.2〜0.3秒) の後に、要求された位置から鳴り始める。そのまま止めると
     * 曲の最後の数秒が毎回切れる。呼ぶのは server が「曲の終わりによる停止」と印を付けた停止だけで
     * ({@code StopDiscPayload#naturalEnd})、取り出し・一時停止はすぐ止まる。待っている間に同じ位置で
     * 次の再生が始まったら、遅らせた停止は撃たない (次の再生が前の音を畳む)。
     *
     * @return 遅らせたら {@code true} (呼び出し側は今は止めない)
     */
    public boolean deferNaturalEndStop(BlockPos pos, Runnable stop) {
        final BlockPos key = pos.immutable();
        final ReloadRequest request = reloadRequests.get(key);
        if (request == null || request.track() == null || request.track().radio()
                || request.track().durationMs() <= 0L) {
            return false;
        }
        final long duration = request.track().durationMs();
        final long serverPosition = request.startOffsetMs() + (playbackClockMs() - request.receivedPlaybackMs());
        if (serverPosition < duration - NATURAL_END_WINDOW_MS) {
            return false;
        }
        final long audioPosition = sessions.audioPositionMs(key);
        if (audioPosition < 0L) {
            return false;
        }
        final long tail = Math.min(duration - audioPosition, NATURAL_END_MAX_WAIT_MS);
        if (tail <= 0L) {
            return false;
        }
        CompletableFuture.delayedExecutor(tail, TimeUnit.MILLISECONDS).execute(() -> Minecraft.getInstance().execute(() -> {
            if (reloadRequests.get(key) == request) {
                stop.run();
            }
        }));
        return true;
    }

    /**
     * この再生で「Now Playing」を出してよいか (2026-10-09 本人裁定)。
     *
     * <ul>
     *   <li>音が届く範囲の外では出さない。音源 (VS2 の船上なら船の現在位置) か、つながった Speaker の
     *       どれかから可聴範囲内にいる時だけ出す。</li>
     *   <li>同じ位置で同じ曲を鳴らし直す (金ジュークの単曲 repeat。server は停止を挟まずに次の再生を送る)
     *       ときは最初の1回だけ出す。一時停止から同じ曲を再開した時・同じ盤を差し直した時も出さない
     *       (停止では忘れず、ワールド退出の {@link #stopAll} で忘れる)。</li>
     * </ul>
     * 1回の再生で要求時と実音の開始時の2回呼ばれるので、同じ世代 ({@code token}) の2回目は通す。
     */
    private boolean mayAnnounce(BlockPos key, int token) {
        final ReloadRequest request = reloadRequests.get(key);
        if (request == null || request.track() == null || !withinAudibleRange(key, request.rangeBlocks())) {
            return false;
        }
        final String sourceKey = request.track().sourceKey();
        final AnnouncedTrack previous = announced.get(key);
        if (previous != null && previous.sourceKey().equals(sourceKey) && previous.token() != token) {
            return false;
        }
        announced.put(key, new AnnouncedTrack(sourceKey, token));
        return true;
    }

    private static boolean withinAudibleRange(BlockPos key, int rangeBlocks) {
        final net.minecraft.world.phys.Vec3 ear = DiscSoundInstance.listenerPos(); // 音の判定と同じくカメラの位置
        if (ear == null) {
            return false;
        }
        final int range = rangeBlocks > 0 ? Math.min(rangeBlocks, com.kuronami.musicdiscmaker.Config.maxPlaybackRange()) : com.kuronami.musicdiscmaker.Config.playbackRange();
        final double limitSq = (range + 1.0D) * (range + 1.0D);
        final DiscAnchor ship = shipAnchor(key);
        final net.minecraft.world.phys.Vec3 source = ship != null ? ship.worldPos(1.0F) : net.minecraft.world.phys.Vec3.atCenterOf(key);
        if (ear.distanceToSqr(source) <= limitSq) {
            return true;
        }
        final MultiSpeakerAnchor speakers = get().speakerAnchors.get(key);
        if (speakers != null) {
            for (final com.kuronami.musicdiscmaker.network.SpeakerEntry speaker : speakers.payload().speakers()) {
                if (!speaker.muted() && ear.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(speaker.pos())) <= limitSq) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 再生要求を受けた時点で Now Playing を曲名にする (client 設定で有効な時だけ)。
     *
     * <p>実音の開始を待たないのは、読込に失敗した client にも曲名を出すため。custom disc は無音の
     * {@code jukebox_song} で vanilla の再生状態に乗っており、vanilla が出そうとするアイテム名
     * 「Custom Music Disc」は {@code MixinNowPlayingPlaceholder} が止める (CF 報告「一人だけ鳴らず、
     * 曲名が Custom Music Disc になる」)。
     */
    private static void announceRequestedTrack(CustomTrackData track) {
        com.kuronami.musicdiscmaker.client.NowPlaying.show(track);
    }

    /**
     * 有限曲が再生途中で一時的に途切れたら、server と同じ位置から開き直す
     * ({@link PlaybackSessions#finiteStreamFailed})。開き直したら {@code true} で、その時は失敗を出さない。
     */
    private boolean resumeFiniteStream(BlockPos key, int token, CustomTrackData track, PlaybackFailure late,
            @Nullable PlaybackVoice origin) {
        if (track == null || track.radio()) return false;
        final PlaybackSessions.FiniteResume resume = sessions.finiteStreamFailed(key, token, late, origin);
        if (resume.superseded()) return true; // 処理済みの重複 / 置き換え済みの旧 voice からの通知
        if (resume.kind() != PlaybackSessions.ReconnectKind.RETRY || resume.request() == null) return false;
        final PlaybackSessions.Request req = resume.request();
        final ReloadRequest reload = reloadRequests.get(key);
        if (reload == null || !req.track().sourceKey().equals(track.sourceKey())) {
            return false; // 開き直せない。途切れた voice は畳まれており、従来どおり理由を出す
        }
        MusicDiscMaker.LOGGER.warn("Finite stream dropped mid-track at {} ms ({}); resuming {}/{} [{}]",
                resume.offsetMs(), late.kind(), resume.attempt(), PlaybackSessions.MAX_FINITE_RESUME,
                key.toShortString());
        notifyActionBar(Component.translatable("music_disc_maker.playback.resuming",
                resume.attempt(), PlaybackSessions.MAX_FINITE_RESUME));
        submitLoad(key, req.track(), resume.offsetMs(), req.rangeBlocks(), req.volumePercent(),
        //? if >=1.21.2 {
                FINITE_RESUME_DELAY_MS, token,
                new PlaybackTiming(key.toShortString(), req.track().sourceKey(), token, false),
                reload.extensionId(), reload.sourceContext(), reload.selection(), reload.regularOnly());
        //?} elif >=1.21 {
/*                FINITE_RESUME_DELAY_MS, token,
                new PlaybackTiming(key.toShortString(), req.track().sourceKey(), token, false),
                reload.extensionId(), reload.sourceContext(), reload.selection(), reload.regularOnly());
        */
        //?} else {
/*                FINITE_RESUME_DELAY_MS, token, reload.extensionId(), reload.sourceContext(),
                reload.selection(), reload.regularOnly());
        */
        //?}
        return true;
    }


    private void handlePlaybackFault(BlockPos key, int token, CustomTrackData track,
            PlaybackSessionId id, PlaybackFailure failure) {
        handlePlaybackFault(key, token, track, id, failure, null);
    }

    /** {@code origin} は失敗を出した登録済み voice。登録前の通知なら {@code null}。 */
    private void handlePlaybackFault(BlockPos key, int token, CustomTrackData track,
            PlaybackSessionId id, PlaybackFailure failure, @Nullable PlaybackVoice origin) {
        if (id != null) fallbackAfterExtensionFailure(key, token, track, id, failure);
        else lateFailure(key, token, track, failure, origin);
    }

    /** Close the AV generation before reopening the regular, URL-guarded MDM source at its output PTS. */
    private void fallbackAfterExtensionFailure(BlockPos key, int token, CustomTrackData track,
            PlaybackSessionId id, PlaybackFailure failure) {
        if (id == null || !sessions.isLive(key, token) || !id.equals(extensionIds.get(key))
                || !faultGate.claimFallback(key, token, id)) return;
        final ReloadRequest request = reloadRequests.get(key);
        if (request == null || !id.equals(request.extensionId())) return;
        long outputPtsUs = PlaybackSessionRegistry.presentationTimeUs(id,
                fault -> Minecraft.getInstance().execute(() -> fallbackAfterExtensionFailure(
                        key, token, track, id, PlaybackFailure.thrown(fault))));
        // The last audible cursor is the only safe fallback anchor. Wall time advances through pause and
        // decoder underflow, so estimating from elapsed time can skip content after a fault.
        long offsetMs = outputPtsUs >= 0L ? outputPtsUs / 1_000L : request.startOffsetMs();
        beginCloseBarrier(key, id);
        runAfterClose(key, request.identity(), () -> {
            startPlaybackInternal(key, track, Math.max(0L, offsetMs), request.rangeBlocks(),
                    request.volumePercent(), request.directional(), request.identity(), true);
            MusicDiscMaker.LOGGER.warn("Optional AV close acknowledged; reopening regular MDM audio at {} ms", offsetMs);
        });
    }

    /** ロード完了 (main thread)。成功なら再生を開始し、失敗ならラジオは再接続扱い・通常は通知して終わる。 */
    private void onLoaded(BlockPos key, CustomTrackData track, long startOffsetMs, long dedupOffsetMs, int rangeBlocks,
            //? if >=1.21.2 {
            int volumePercent, IAudioSource resolved, PlaybackFailure failure, int token,
            PlaybackTiming timing) {
            //?} elif >=1.21 {
/*            int volumePercent, IAudioSource resolved, PlaybackFailure failure, int token,
            PlaybackTiming timing) {
            */
            //?} else {
/*            int volumePercent, IAudioSource resolved, PlaybackFailure failure, int token) {
            */
            //?}
        if (resolved == null) {
            if (track.radio() && sessions.isLive(key, token)) {
                onRadioStreamEnded(key, token); // ロード失敗も 1 回の再接続試行として数える
            } else if (sessions.isLive(key, token)) {
                sessions.abandon(key, token);
                // 無音で終わらせない。理由の分類つきで画面のラベルとログの両方に残す
                // (ストリームは client ごとに開くので、片方の client だけ失敗しうる)。
                final PlaybackFailure show =
                        failure == null ? PlaybackFailure.streamUnavailable() : failure;
                failures.record(key, show);
                PlaybackFailureReport.report(track, show);
            }
            return;
        }
        if (!sessions.isLive(key, token)) {
            resolved.close(); // ロード中に停止/差し替え済み
            return;
        }
        // 自然終了したインスタンスを掃除してから上限を見る (MAX_CONCURRENT を不当に消費させない)。
        // 数えるのは金ジュークの分だけではない — ブームボックスも同じ OpenAL の枠を消費するので、
        // 経路をまたいだ合計で見る (設計上の決定「金ジュークと枠を共有する」)。
        final int playing = PlaybackConcurrency.client().sweepAll();
        final int limit = com.kuronami.musicdiscmaker.Config.maxConcurrent();
        if (playing >= limit) {
            // 上限は client ごとに効くので片側だけ無音になりうる。無言で捨てない。
            final PlaybackFailure overLimit = PlaybackFailure.concurrentLimit(limit);
            failures.record(key, overLimit);
            PlaybackFailureReport.report(track, overLimit);
            resolved.close();
            sessions.abandon(key, token);
            return;
        }
        // ラジオは終端 (瞬断) で自動再接続を試みる。有限曲は自然終了させる (再起動しない)。
        final Runnable endCb = track.radio()
                ? () -> Minecraft.getInstance().execute(() -> onRadioStreamEnded(key, token))
                : null;
        // VS2 船に載った jukebox は shipyard 座標のままだと音源がズレる (見えないスピーカー)。船管理下なら
        // 毎 tick 剛体変換で world 座標へ追従するアンカーに差し替える。compat 実装を持たない帯 or VS2 非導入
        // or 船外なら null = 従来の StaticAnchor 再生。
        final DiscAnchor shipAnchor = shipAnchor(key);
        final MultiSpeakerAnchor speakers = speakerAnchors.get(key);
        final DiscAnchor rawSourceAnchor = shipAnchor != null ? shipAnchor : new StaticAnchor(key);
        final SpeakerSetPayload speakerSet = speakers == null
                ? new SpeakerSetPayload(key, rangeBlocks, volumePercent, true, java.util.List.of())
                : speakers.payload();
        final GoldenEmitterAnchor goldenAnchor = new GoldenEmitterAnchor(rawSourceAnchor, speakerSet);
        final DiscAnchor sourceAnchor = goldenAnchor;
        final DiscAnchor anchor = sourceAnchor;
        final int initialRange = anchor instanceof LiveAudioConfig audioConfig ? audioConfig.rangeBlocks() : rangeBlocks;
        final int initialVolume = anchor instanceof LiveAudioConfig audioConfig ? audioConfig.volumePercent() : volumePercent;
        final FanoutAudioSource fanout = new FanoutAudioSource(resolved);
        final IAudioSource master = fanout.openMasterBranch();
        final DiscSoundInstance instance = new DiscSoundInstance(anchor,
                master, initialRange, initialVolume, endCb);
        // 失敗通知の宛先が「今も登録中の voice か」を判定するため、登録できた voice をここへ入れる。
        final java.util.concurrent.atomic.AtomicReference<PlaybackVoice> installedVoice =
                new java.util.concurrent.atomic.AtomicReference<>();
        PlaybackSessionRegistry.sessionIdFor(resolved).ifPresent(instance::setPlaybackSessionId);
        // ストリーム終端の pull 経路も同じ届け先へ繋ぐ。push を持つソースでは重複しうるが、
        // lateFailure の重複抑止が畳むので害はない。逆に差さない方が危険 — 差さないと
        // LavaPlayerAudioStream が「届け先を持たない経路」の既定へ落ち、曲名も重複抑止も
        // ラジオの沈黙も通らない裸の報告を出す。届くのは streaming スレッドなので main thread へ移す。
        final PlaybackSessionId activeExtensionId = PlaybackSessionRegistry.sessionIdFor(resolved).orElse(null);
        instance.setFailureSink(late -> Minecraft.getInstance().execute(
                () -> handlePlaybackFault(key, token, track, activeExtensionId, late, installedVoice.get())));
        // push 経路。FanoutAudioSource は生成時に上流の届け先を自分へ差し替えるので、submitLoad で
        // 上流へ差した届け先には以後届かない。主ブランチから同じ宛先へ繋ぎ直す (EOF 確認より後に
        // 遅れて届いた失敗を取り落とさない)。pull と重複しても上の voice 判定と既存の重複抑止が畳む。
        master.onPlaybackFault(broken -> Minecraft.getInstance().execute(
                () -> handlePlaybackFault(key, token, track, activeExtensionId,
                        PlaybackFailure.ofReason(broken.reason(), broken.detail()), installedVoice.get())));
        //? if >=1.21.2 {
        instance.setTiming(timing);
        // 実音が鳴り始めた (最初の実 PCM) 瞬間に、進捗表示のアンカーを実音の開始へ打ち直す。
        // 1 再生につき 1 回は noteFirstAudio の世代ガードが保証する。打ち直しは main thread で行う。
        instance.setFirstAudioSink(() -> {
            fanout.markPcmStarted();
            Minecraft.getInstance().execute(() -> {
            if (sessions.noteFirstAudio(key, token)) {
                reanchorClientElapsed(key, startOffsetMs);
                reportListenerLag(key, startOffsetMs);
                sessions.engineAccepted(key);
                failures.clear(key);
                if (sessions.activeVoice(key) instanceof SpeakerVoiceGroup group) group.onFirstAudio();
            }
            });
        });
        //?} elif >=1.21 {
/*        instance.setTiming(timing);
         final String desc = nowPlayingText(track);
         instance.setFirstAudioSink(() -> {
             fanout.markPcmStarted();
             Minecraft.getInstance().execute(() -> onFirstAudio(key, token, desc, startOffsetMs));
         });
        */
        //?} else {
/*
        // 実音が鳴り始めた (最初の実 PCM) 瞬間に、進捗表示のアンカーを実音の開始へ打ち直す。
        // 1 再生につき 1 回は noteFirstAudio の世代ガードが保証する。打ち直しは main thread で行う。
         instance.setFirstAudioSink(() -> {
             fanout.markPcmStarted();
             Minecraft.getInstance().execute(() -> {
                 if (sessions.noteFirstAudio(key, token)) {
                     reanchorClientElapsed(key, startOffsetMs);
                     reportListenerLag(key, startOffsetMs);
                 }
             });
         });
*/
        //?}
        // install は聴取モデルの適用も行う (鳴り始めの 1 tick を positional で鳴らさない)。
        // 世代が古ければ受け付けず、同じ座標に残っていた音源は必ず止めてから置き換える。
        final PlaybackVoice playback = new SpeakerVoiceGroup(instance, goldenAnchor, fanout, speakerSet);
        if (!sessions.install(key, token, track.sourceKey(), dedupOffsetMs, playback)) {
            playback.stopAndRelease();
            return;
        }
        installedVoice.set(playback);
        // decode中に届いたSetを、SoundManagerへ渡す直前にも採用する。sessions.install がPlay時点の
        // directionalを入れるため、ここで最新集合の値を上書きしないと最初の一瞬だけ旧設定になる。
        //? if >=1.21 {
        if (anchor instanceof LiveAudioConfig audioConfig) {
            instance.setVolumePercent(audioConfig.volumePercent());
            instance.setRangeBlocks(audioConfig.rangeBlocks());
            instance.setDirectional(audioConfig.directional());
        }
        //?} else {
/*        if (anchor instanceof LiveAudioConfig audioConfig) {
            instance.setVolumePercent(audioConfig.volumePercent());
            instance.setDirectional(audioConfig.directional());
        }
*/
        //?}
        //? if >=26.2 {
        // 他MODが一度取り消して同じinstanceを後から再投入する経路もある。
        // 受理されなくても即座には閉じず、実PCMが来ない場合だけ期限で解放する。
        armFirstAudioDeadline(key, token, track);
        if (!SoundEngineAcceptance.start(instance, SoundEngineAcceptance.KEEP_UNTIL_DEADLINE, rejected -> {
            final PlaybackFailure show = sessions.engineRejected(key, token, rejected);
            if (show != null) {
                failures.record(key, show);
                PlaybackFailureReport.report(track, show);
            }
        })) {
            return;
        }
        sessions.engineAccepted(key);
        if (playback instanceof SpeakerVoiceGroup group) {
            // The accepted Golden master must exist before child branches can prefill/read PCM.
            group.update(speakerSet);
        }
        //?} elif >=1.21.2 {
/*        armFirstAudioDeadline(key, token, track);
        if (!SoundEngineAcceptance.start(instance, SoundEngineAcceptance.KEEP_UNTIL_DEADLINE, rejected -> {
            final PlaybackFailure show = sessions.engineRejected(key, token, rejected);
            if (show != null) {
                failures.record(key, show);
                PlaybackFailureReport.report(track, show);
            }
        })) {
            return;
        }
        sessions.engineAccepted(key);
        if (playback instanceof SpeakerVoiceGroup group) {
            // The accepted Golden master must exist before child branches can prefill/read PCM.
            group.update(speakerSet);
        }
*/
        //?} elif >=1.21 {
/*        armFirstAudioDeadline(key, token, track);
        if (!SoundEngineAcceptance.start(instance, SoundEngineAcceptance.KEEP_UNTIL_DEADLINE, rejected -> {
            final PlaybackFailure show = sessions.engineRejected(key, token, rejected);
            if (show != null) {
                failures.record(key, show);
                PlaybackFailureReport.report(track, key, token, show);
            }
        })) {
            return;
         }
         sessions.engineAccepted(key); // engine が受理した。拒否の記憶を捨てる唯一の点
         if (playback instanceof SpeakerVoiceGroup group) {
             // The accepted Golden master must exist before child streams prefill on every supported band.
             group.update(speakerSet);
         }
         */
        //?} else {
/*        Minecraft.getInstance().getSoundManager().play(instance);
         if (playback instanceof SpeakerVoiceGroup group) {
             group.update(speakerSet);
         }
         */
        //?}
        failures.clear(key);
        //? if >=1.21.2 {
        final String desc = (track.author() != null && !track.author().isBlank())
        //?} elif >=1.21 {
/*    }

    private static String nowPlayingText(CustomTrackData track) {
        return (track.author() != null && !track.author().isBlank())
        */
        //?} else {
/*        final String desc = (track.author() != null && !track.author().isBlank())
        */
        //?}
                ? track.author() + " - " + track.title()
                : track.title();
    //? if >=1.21.2 {
    //?} elif >=1.21 {
/*    }

    private void onFirstAudio(BlockPos key, int token, String desc, long startOffsetMs) {
        if (!sessions.noteFirstAudio(key, token)) {
            return; // 停止済み / 世代違い / 2 回目
        }
        // 実音の開始に合わせて進捗表示のアンカーを打ち直す (1 再生につき 1 回 = 上のガードの内側)。
        reanchorClientElapsed(key, startOffsetMs);
        reportListenerLag(key, startOffsetMs);
        sessions.engineAccepted(key);
        failures.clear(key);
    */
    //?} else {
    //?}
        if (desc != null && !desc.isBlank() && mayAnnounce(key, token)) {
            com.kuronami.musicdiscmaker.client.NowPlaying.show(Component.literal(desc));
        }
    //? if >=1.21.2 {
    //?} elif >=1.21 {
/*    }

    private void armFirstAudioDeadline(BlockPos key, int token, CustomTrackData track) {
        CompletableFuture.delayedExecutor(PlaybackSessions.FIRST_AUDIO_DEADLINE_MS, TimeUnit.MILLISECONDS)
                .execute(() -> Minecraft.getInstance()
                        .execute(() -> onFirstAudioDeadline(key, token, track)));
    }

    private void onFirstAudioDeadline(BlockPos key, int token, CustomTrackData track) {
        if (!sessions.firstAudioOverdue(key, token)) {
            return; // 鳴り始めた / 停止済み / 差し替え済み
        }
        MusicDiscMaker.LOGGER.warn("No audio reached the sound engine within {}ms [{}] url={}",
                PlaybackSessions.FIRST_AUDIO_DEADLINE_MS, key.toShortString(), track.sourceKey());
        if (releaseOnFirstAudioDeadline(sessions, prefetch, key, track.radio())) {
            onRadioStreamEnded(key, token);
            return;
        }
        final PlaybackFailure show = PlaybackFailure.streamUnavailable();
        failures.record(key, show);
        PlaybackFailureReport.report(track, show);
    }

    static boolean releaseOnFirstAudioDeadline(PlaybackSessions sessions,
            PlaybackPrefetch<BlockPos> prefetch, BlockPos key, boolean radio) {
        prefetch.drop(key, "first-audio-deadline");
        if (radio) {
            return true;
        }
        sessions.stop(key); // 音源とチャンネルを手放す (畳まないと席を掴んだまま黙り続ける)
        return false;
    */
    //?} else {
    //?}
    }

    //? if >=1.21.2 {
    private void armFirstAudioDeadline(BlockPos key, int token, CustomTrackData track) {
        CompletableFuture.delayedExecutor(PlaybackSessions.FIRST_AUDIO_DEADLINE_MS, TimeUnit.MILLISECONDS)
                .execute(() -> Minecraft.getInstance()
                        .execute(() -> onFirstAudioDeadline(key, token, track)));
    }

    private void onFirstAudioDeadline(BlockPos key, int token, CustomTrackData track) {
        if (!sessions.firstAudioOverdue(key, token)) {
            return; // 鳴り始めた / 停止済み / 差し替え済み
        }
        MusicDiscMaker.LOGGER.warn("No audio reached the sound engine within {}ms [{}] url={}",
                PlaybackSessions.FIRST_AUDIO_DEADLINE_MS, key.toShortString(), track.sourceKey());
        if (track.radio()) {
            onRadioStreamEnded(key, token);
            return;
        }
        final PlaybackFailure show = PlaybackFailure.streamUnavailable();
        failures.record(key, show);
        PlaybackFailureReport.report(track, show);
        sessions.stop(key); // 同じ曲の再送を dedup せず、次の再生要求で開き直せるようにする
    }
    //?} else {
    //?}

    /** The server playback clock is paused with an integrated game; multiplayer uses the matching local clock. */
    private static long playbackClockMs() {
        final var server = Minecraft.getInstance().getSingleplayerServer();
        return server instanceof com.kuronami.musicdiscmaker.component.PlaybackClockAccess clock
                ? clock.mdm$playbackTimeMs()
                : com.kuronami.musicdiscmaker.component.PauseAwarePlaybackClock.realTimeMs();
    }

    /**
     * 実音の開始に合わせて、その jukebox の進捗表示アンカーを打ち直す (main thread から呼ぶこと)。
     * 呼び出し側が {@link PlaybackSessions#noteFirstAudio} を通った直後に限るので、
     * 1 再生につき 1 回になる。BE が金ジュークでなくなっていたら (撤去・差し替え等) 何もしない。
     */
    /**
     * 実音が鳴り始めた時、server の再生位置からどれだけ遅れていたかを金のジュークボックスへ知らせる。
     * server はいちばん遅い client が鳴り終わるまで、アルバムの次の曲・1曲リピートの頭出しを待つ。
     * 印の無い再生 (バニラのジュークボックス) とラジオ・尺の無い曲は送らない。
     */
    private void reportListenerLag(BlockPos key, long audioStartOffsetMs) {
        final ReloadRequest request = reloadRequests.get(key);
        if (request == null || request.identity() == null || request.identity().sourceId() == null
                || request.track() == null || request.track().radio() || request.track().durationMs() <= 0L) {
            return;
        }
        final long serverPosition = request.startOffsetMs() + (playbackClockMs() - request.receivedPlaybackMs());
        final long lag = serverPosition - audioStartOffsetMs;
        if (lag > 0L) {
            com.kuronami.musicdiscmaker.platform.Services.NETWORK.sendToServer(
                    new com.kuronami.musicdiscmaker.network.AudioLagPayload(key, -1, request.identity(), lag));
        }
    }

    private static void reanchorClientElapsed(BlockPos key, long startOffsetMs) {
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        if (level.getBlockEntity(key) instanceof GoldenJukeboxBlockEntity be) {
            be.clientReanchorElapsedToAudioStart(startOffsetMs);
        }
    }

    /**
     * 音源座標の決定を引き受ける実装を差し込む。client 側の loader entry から 1 回だけ呼ぶ。
     * 実装を持つのは VS2 互換を同梱している帯だけで、他帯は差し込まれないまま動く。
     */
    public static void setShipAnchorResolver(BiFunction<ClientLevel, BlockPos, DiscAnchor> resolver) {
        shipAnchorResolver = resolver;
    }

    /**
     * {@code pos} の音源座標を compat に決めさせる。{@code null} = compat が決めない
     * (実装が差し込まれていない帯・対象 MOD 非導入・船外) ので、呼び出し側は従来どおり座標で再生する。
     */
    static DiscAnchor shipAnchor(BlockPos pos) {
        final BiFunction<ClientLevel, BlockPos, DiscAnchor> resolver = shipAnchorResolver;
        if (resolver == null) {
            return null;
        }
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        return resolver.apply(level, pos);
    }

    /**
     * ラジオストリームが終端 (read=-1) に達した時に main thread で呼ばれる。再接続するか諦めるかの
     * 判断は {@link PlaybackSessions#radioStreamEnded} が持つ。
     */
    private void onRadioStreamEnded(BlockPos key, int token) {
        final PlaybackSessions.Reconnect next = sessions.radioStreamEnded(key, token);
        switch (next.kind()) {
            case NONE -> {
                // ディスク撤去/停止済み → 再接続しない
            }
            case RETRY -> {
                notifyActionBar(Component.translatable("music_disc_maker.radio.reconnecting",
                        next.attempt(), PlaybackSessions.MAX_RECONNECT));
                final PlaybackSessions.Request req = next.request();
                final ReloadRequest reload = reloadRequests.get(key);
                if (reload == null) return;
                submitLoad(key, req.track(), 0L, req.rangeBlocks(), req.volumePercent(),
                //? if >=1.21.2 {
                RECONNECT_DELAY_MS, token,
                new PlaybackTiming(key.toShortString(), req.track().sourceKey(), token, false),
                reload.extensionId(), reload.sourceContext(), reload.selection(), reload.regularOnly());
                //?} elif >=1.21 {
/*                        RECONNECT_DELAY_MS, token,
                        new PlaybackTiming(key.toShortString(), req.track().sourceKey(), token, false),
                reload.extensionId(), reload.sourceContext(), reload.selection(), reload.regularOnly());
                        */
                        //?} else {
/*                        RECONNECT_DELAY_MS, token, reload.extensionId(), reload.sourceContext(),
                        reload.selection(), reload.regularOnly());
                        */
                        //?}
            }
            case GIVE_UP -> {
                // 再接続を諦めた = 恒久的にこの音源は鳴らない。数秒で消えるアクションバーではなく
                // チャットへ残す (「いつ止まったか」を後から読めるようにする)。
                // 1.20.1 だけはアクションバー。出荷中の 1.20.1 (mod-048) がそう振る舞っており、
                // 利用者から見える通知先を統合のついでに動かさない。
                //? if >=1.21 {
                notifyChat(Component.translatable("music_disc_maker.radio.stopped"));
                //?} else {
/*                notifyActionBar(Component.translatable("music_disc_maker.radio.stopped"));
                */
                //?}
                // 途中で拾っていた理由があれば、ここで初めて出す (「なぜ諦めたか」)。
                if (next.why() != null) {
                    // ラジオの失敗が入れ物に載るのはここだけ。再接続中は保留されているので
                    // (PlaybackSessions#pendingFailure)、試行中の jukebox はラベルを持たない。
                    failures.record(key, next.why());
                    PlaybackFailureReport.report(next.request().track(), next.why());
                }
            }
        }
    }

    //? if >=1.21 {
    /** チャットに一行残す (main thread から呼ぶこと)。数秒で消えては困る恒久的な結果に使う。 */
    private static void notifyChat(Component message) {
        final var player = Minecraft.getInstance().player;
        if (player != null) {
            //? if >=26.1 {
            player.sendSystemMessage(message);
            //?} else {
/*            player.displayClientMessage(message, false);
            */
            //?}
        }
    }
    //?}

    /** アクションバーに一行表示する (main thread から呼ぶこと)。 */
    private static void notifyActionBar(Component message) {
        //? if >=26.2 {
        Minecraft.getInstance().gui.hud.setOverlayMessage(message, false);
        //?} elif >=1.21.2 {
/*        Minecraft.getInstance().gui.setOverlayMessage(message, false);
        */
        //?} else {
/*        final var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(message, true);
        }
        */
        //?}
    }

    /**
     * この座標の再生を止める ({@code StopDiscPayload})。
     *
     * <p>直近の失敗の記憶もここで捨てる。server が停止を告げてくる経路は、ディスクの取り出し・
     * ブロック撤去のほかに一時停止とアルバムの曲送りも通る。<b>client からは区別できない</b>
     * (payload は座標しか運ばない) ので、まとめて「この jukebox は止まった」として扱う。
     *
     * <p>chunk 再入の再送はここを通らない ({@code startPlayback} が同じ曲の再送として畳む) ので、
     * 出入りのたびにラベルが消えることはない。
     */
    public void stopPlayback(BlockPos pos) {
        final BlockPos key = pos.immutable();
        //? if >=1.21.2 {
        //?} elif >=1.21 {
/*        prefetch.drop(key, "stopPlayback: server-stop-or-disc-change");
        */
        //?} else {
        //?}
        PlaybackSessionId id = extensionIds.get(key);
        if (id != null) beginCloseBarrier(key, id);
        else sessions.stop(key);
        pendingCoreActions.remove(key);
        reloadRequests.remove(key);
        speakerAnchors.remove(key);
        // announced は残す: 一時停止から同じ曲を再開しても Now Playing を出し直さない (2026-10-09 本人裁定)。
        failures.clear(key);
    }

    /** Stops the prior MDM session and preserves the native Play payload until the exact close ACK. */
    public void stopPlaybackBeforeNative(PlaybackSourceStamp identity, BlockPos pos, Runnable nativeAction) {
        final BlockPos key = pos.immutable();
        Runnable startNative = () -> {
            stopPlayback(key);
            nativeAction.run();
        };
        if (queueBehindClose(key, identity, startNative)) return;
        final PlaybackSessionId oldId = extensionIds.get(key);
        if (oldId == null) {
            startNative.run();
            return;
        }
        beginCloseBarrier(key, oldId);
        runAfterClose(key, identity, startNative);
    }

    /** 全ての再生を止める (ワールド離脱)。失敗の記憶も持ち越さない。 */
    public void stopAll() {
        final long departingEpoch = WorldEpochs.captureAndAdvance(worldEpoch);
        pendingCoreActions.clear();
        pendingSourceCoreActions.clear();
        final Map<BlockPos, PlaybackSessionId> active = ExtensionSessionRetirement.captureAndClear(extensionIds);
        for (Map.Entry<BlockPos, PlaybackSessionId> entry : active.entrySet())
            beginCloseBarrier(entry.getKey(), entry.getValue(), departingEpoch);
        faultGate.clear();
        LocalAudioClient.disconnect();
        ClientPlaybackHandler.clearSourceOwnership();
        //? if >=1.21.2 {
        //?} elif >=1.21 {
/*        prefetch.dropAll("stopAll: world-exit");
        recentAlbumPos = null;
        */
        //?} else {
        //?}
        sessions.stopAll();
        reloadRequests.clear();
        speakerAnchors.clear();
        announced.clear();
        // ブームボックスは別の鍵空間で鳴っているので、ここで一緒に畳まないとワールドを出た後も残る。
        BoomboxClientPlayback.stopAll();
        GoldenCartClientPlayback.stopAll();
        VanillaSpeakerPlayback.stopAll();
        failures.clearAll();
    }

    /**
     * resource 再読み込みで OpenAL channel を失った voice を無効化し、再開処理を返す。
     * world の再生自体は止まっていないため、Speaker anchor と音源所有権は保持する。
     */
    Runnable prepareSoundReloadResume() {
        final Map<BlockPos, ReloadRequest> snapshot = Map.copyOf(reloadRequests);
        reloadRequests.clear();
        for (Map.Entry<BlockPos, PlaybackSessionId> active : Map.copyOf(extensionIds).entrySet())
            beginCloseBarrier(active.getKey(), active.getValue());
        faultGate.clear();
        sessions.stopAll();
        return () -> {
            final long now = playbackClockMs();
            snapshot.forEach((pos, request) -> {
                final long offset = SoundReloadResume.offset(request.startOffsetMs(), request.receivedPlaybackMs(),
                        now, request.track().durationMs(), request.track().radio());
                if (offset >= 0L) {
                    Runnable resume = () -> startPlaybackInternal(pos, request.track(), offset,
                            request.rangeBlocks(), request.volumePercent(), request.directional(),
                            request.identity(), request.regularOnly());
                    if (!queueBehindClose(pos, request.identity(), resume)) resume.run();
                }
            });
        };
    }

    // --- B5 (混在アルバムの先読み) ------------------------------------------------------------
    //
    // 先読みの駆動源だった {@code DiscSoundInstance.tick()} は MDM ストリーム再生中しか生きない。
    // アルバム内に vanilla / 他 MOD ディスクが混ざると、その曲の再生中は MDM 再生が無く、
    // 次の custom トラックへの先読み判定が一度も回らない (B5「混在アルバムで先読みが不発」)。
    // loader 側の client tick イベントから {@link #mixedAlbumTick} を毎 tick 呼び、
    // vanilla 曲の再生中も残り時間判定を回し続ける。vanilla 曲の尺は
    // {@code GoldenJukeboxBlockEntity#trackDurationMs()} が {@code JukeboxSong} から出すので、
    // 判定ロジックは custom 曲と共用できる。

    /**
     * MDM 再生非依存の client tick 源。未開始Speakerの補充と、対象帯の混在Album先読みを行う。
     *
     * <p>追跡対象は「直前に先読み判定が回った座標」1 つだけなので、毎 tick の cost は
     * chunk ロード済みのときの {@code getBlockEntity} 1 回分。BE が金ジュークでなくなったら
     * (撤去・ワールド退出・chunk アンロード) 追跡を手放す。
     */
    public static void mixedAlbumTick() {
        // 未開始Speakerはserver Setの変更が無くても、共有枠が空いた次tickで補充する。
        // SoundEngineのtickingSounds走査中やPlaybackConcurrency.sweep中には子を追加しない。
        final ClientPlaybackManager manager = get();
        for (BlockPos pos : java.util.List.copyOf(manager.reloadRequests.keySet())) {
            if (manager.sessions.activeVoice(pos) instanceof SpeakerVoiceGroup group) {
                group.retryDeferredSpeakers();
            }
        }
        VanillaSpeakerPlayback.retryDeferredSpeakers();
        //? if >=1.21 <1.21.2 {
        /*final BlockPos pos = recentAlbumPos;
        if (pos == null) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity be) {
            drivePrefetch(pos, be);
        } else {
            recentAlbumPos = null;
            get().cancelPrefetch(pos, "mixed-album-tick: block-entity-gone");
        }
        */
        //?}
    }

    //? if >=1.21 <1.21.2 {
    /*/^* 直前に先読み判定が回った jukebox 座標。MDM 再生が止まった後も混在アルバムの追跡のために保持する。 ^/
    private static BlockPos recentAlbumPos;

    /^* 先読みの発火を残り時間ベースで判断するリード時間。 ^/
    public static final long PREFETCH_LEAD_MS = 15_000L;

    private static String lastPrefetchSkipReason;

    /^*
     * 次に鳴る custom トラックを残り時間ベースで先読みする。{@code DiscSoundInstance.tick()}
     * (MDM 再生中) と {@link #mixedAlbumTick} (vanilla / 他 MOD 曲の再生中) の共通経路。
     * 判定内容は 1.21.1 の原本 ({@code DiscSoundInstance#drivePrefetch}) から動かしていない。
     ^/
    public static void drivePrefetch(BlockPos pos, GoldenJukeboxBlockEntity be) {
        recentAlbumPos = pos.immutable();
        final ClientPlaybackManager manager = get();
        if (be.isPaused()) {
            notePrefetchSkipped(pos, "paused", "");
            manager.cancelPrefetch(pos, "drivePrefetch: paused");
            return;
        }
        // 列を持つ盤も次の 1 曲が決まっているので先読みの対象に入る (凍結2 の例外)。
        if (be.getAlbumTrack() < 0 && !be.isRepeat()) {
            notePrefetchSkipped(pos, "not-album-and-repeat-off", "");
            manager.cancelPrefetch(pos, "drivePrefetch: not-album-and-repeat-off");
            return;
        }
        final long duration = be.trackDurationMs();
        final CustomTrackData current = be.currentTrack();
        final CustomTrackData next = be.nextPlaybackTrack();
        final long remainingMs = duration - be.currentElapsedMs();
        if (duration <= 0L) {
            notePrefetchSkipped(pos, "duration-zero", "");
            manager.cancelPrefetchUnlessMatches(pos, current, next, "drivePrefetch: duration-zero");
            return;
        }
        if (remainingMs > PREFETCH_LEAD_MS) {
            notePrefetchSkipped(pos, "remaining-over-lead",
                    "remaining=" + remainingMs + "ms lead=" + PREFETCH_LEAD_MS + "ms");
            manager.cancelPrefetchUnlessMatches(pos, current, next, "drivePrefetch: remaining-before-lead");
            return;
        }
        if (next == null) {
            notePrefetchSkipped(pos, "next-track-missing", "");
            manager.cancelPrefetch(pos, "drivePrefetch: next-track-missing");
            return;
        }
        if (next.isEmpty()) {
            notePrefetchSkipped(pos, "next-track-empty", "");
            return;
        }
        if (next.radio()) {
            notePrefetchSkipped(pos, "next-track-radio", "");
            return;
        }
        if (next.durationMs() <= 0L) {
            notePrefetchSkipped(pos, "next-track-duration-zero", "");
            return;
        }
        lastPrefetchSkipReason = null;
        manager.prefetchNext(pos, next, remainingMs);
    }

    private static void notePrefetchSkipped(BlockPos pos, String reason, String detail) {
        if (reason.equals(lastPrefetchSkipReason)) {
            return;
        }
        lastPrefetchSkipReason = reason;
        MusicDiscMaker.LOGGER.debug("Prefetch not-fired [{}] reason={} {}", pos.toShortString(), reason, detail);
    }
    *///?}
}
