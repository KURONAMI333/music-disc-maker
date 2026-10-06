package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.PauseAwarePlaybackClock;
import com.kuronami.musicdiscmaker.component.PlaybackClockAccess;
import com.kuronami.musicdiscmaker.component.VanillaTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
import com.kuronami.musicdiscmaker.network.GoldenCartPlayPayload;
import com.kuronami.musicdiscmaker.network.GoldenCartStopPayload;
import com.kuronami.musicdiscmaker.network.PlaybackSourceStamp;
import com.kuronami.musicdiscmaker.network.UrlBlockedException;
import com.kuronami.musicdiscmaker.network.UrlGuard;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * client 側: 金ジューク付きトロッコの再生統括。<b>鍵は機体の識別子</b>
 * ({@code source_id} = {@link PlaybackSourceStamp#sourceId()})。
 *
 * <p>{@link BoomboxClientPlayback} と同じ構造 (心拍のたびに同じ要求が降る 3 つのガード:
 * {@link PlaybackRequestGate}・{@link PlaybackSessions} の世代トークン・{@link LoadFailureBackoff})。
 * 違いは経路の同一性 — boombox は機体 id 1 本だが、cart は<b>拾い直しで entity id が変わる</b>。
 * そのため「どの音源の再生か」は {@code sourceId}、「いま鳴っている経路か」は
 * {@link SourcePlaybackOwnership} の lease (route = entity id) が担う。拾い直し後の新しい
 * entity id から来た新世代は claim が旧 lease を畳してから新経路を作るので、
 * 旧 entity の残響と新 entity の再生が同居しない。
 *
 * <p>vanilla / 他 MOD のレコードは {@link VanillaSpeakerSoundInstance} にこの cart 用の
 * {@link CartAnchor} を渡して鳴らす。custom track は {@link DiscSoundInstance} + 非同期ロード。
 */
public final class GoldenCartClientPlayback {

    /**
     * @param startOffsetMs 開いた位置 (曲頭からの ms)。不明なら負
     * @param startedAtMs   開いた時点の再生時計 ({@link TrackAudioResolver#playbackClockMs()})。不明なら負
     */
    record Playing(String key, CartAnchor anchor, PlaybackVoice instance, boolean vanilla,
            long startOffsetMs, long startedAtMs) {
        Playing(String key, CartAnchor anchor, PlaybackVoice instance, boolean vanilla) {
            this(key, anchor, instance, vanilla, -1L, -1L);
        }
    }

    private static final Map<UUID, Playing> ACTIVE = new ConcurrentHashMap<>();

    /** ロード中の要求 (機体 → URL)。世代トークンとは別の関心なので両方要る。 */
    private static final Map<UUID, String> PENDING = new ConcurrentHashMap<>();

    /** ロード中の移動・音量変更も、完了時には最新の同一音声世代へ反映する。 */
    private static final Map<UUID, GoldenCartPlayPayload> LATEST_REQUESTS = new ConcurrentHashMap<>();

    private static final PlaybackSessions<UUID> SESSIONS =
            new PlaybackSessions<>(System::currentTimeMillis);

    private static final LoadFailureBackoff<UUID> BACKOFF = new LoadFailureBackoff<>();

    /** 同時再生上限の報告の重複抑止 (1 Hz の keep-alive でチャットを埋めない)。 */
    private static final PlaybackFailureNotices LIMIT_NOTICES = new PlaybackFailureNotices();

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-cart-playback");
        thread.setDaemon(true);
        return thread;
    });

    static {
        // 同時再生の枠は金ジューク・ブームボックスと共有する。
        PlaybackConcurrency.client().register(SESSIONS);
        // vanilla は PlaybackSessions に入らないため、この所有者の counter で数えて掃除する。
        PlaybackConcurrency.client().registerCounter(() -> sweepVanilla(ACTIVE));
    }

    private GoldenCartClientPlayback() {
    }

    /**
     * 再生 / keep-alive の受信 (main thread)。同じ曲なら生存時刻と live 設定を更新するだけで、
     * ストリームには触らない。
     *
     * @param payload server が撃った再生要求
     */
    public static void play(GoldenCartPlayPayload payload) {
        final PlaybackSourceStamp identity = payload.identity();
        if (identity.sourceId() == null) {
            return;
        }
        if (payload.isStop()) {
            stop(payload);
            return;
        }
        final UUID source = identity.sourceId();
        final CustomTrackData track = payload.track() == null ? CustomTrackData.EMPTY : payload.track();
        final VanillaTrackData vanilla = payload.vanilla();
        final String sourceKey = track.isEmpty()
                ? (vanilla == null ? null : "vanilla:" + vanilla.soundEventId())
                : track.sourceKey();
        if (sourceKey == null) {
            return;
        }
        final GoldenCartPlayPayload previousRequest = LATEST_REQUESTS.put(source, payload);
        final Long previousGeneration = previousRequest == null ? null
                : previousRequest.identity().generation();
        if (previousGeneration != null
                && previousGeneration.longValue() != identity.generation()) {
            BACKOFF.reset(source);
            LIMIT_NOTICES.forget(source);
        }
        // 旧経路 (拾い直し前の entity / 別 actor) の再生を畳み、この要求を新しい lease として掴む。
        // 同じ経路の keep-alive は同じ lease を返すので毎回畳み直しにはならない。
        final Integer route = payload.entityId();
        final var lease = ClientPlaybackHandler.SOURCE_OWNERS.claim(
                source, identity.generation(), route,
                () -> teardownOwned(source, route));
        if (lease == null) {
            return; // 古い世代の遅れ packet
        }
        if (!ClientPlaybackHandler.SOURCE_OWNERS.isCurrent(lease)) {
            return;
        }
        final Playing current = ACTIVE.get(source);
        final PlaybackRequestGate.Decision decision = PlaybackRequestGate.decide(
                current == null ? null : current.key(),
                current != null && current.instance().isVoiceStopped(),
                PENDING.get(source), sourceKey, previousGeneration, identity.generation());
        if (decision == PlaybackRequestGate.Decision.REFRESH) {
            current.anchor().refresh(payload.volumePercent(), payload.rangeBlocks(),
                    payload.directional());
            return;
        }
        if (decision == PlaybackRequestGate.Decision.IGNORE) {
            return; // 同じ曲をロード中。ここで起こし直すと 1 秒ごとに永久リトライになる。
        }
        if (finishedAheadOfServer(current, previousGeneration, identity.generation(), sourceKey, track,
                payload.startOffsetMs())) {
            return;
        }
        if (BACKOFF.isExhausted(source, sourceKey)) {
            return; // 同じ曲で 2 回落ちている。keep-alive のたびに繋ぎ直すのをここで止める
        }
        teardown(source); // 予算は畳まない (畳むと失敗 → 再送 → 予算リセットの輪が閉じない)
        if (vanilla != null && track.isEmpty()) {
            playVanilla(source, route, vanilla, payload);
            return;
        }
        playCustom(source, track, payload);
    }

    /** client 側で「開いた位置＋鳴らした時間」が曲末からこの範囲に届いていれば、鳴り切ったとみなす。 */
    private static final long NATURAL_END_WINDOW_MS = 2_000L;

    /**
     * client の音が曲末まで鳴り切ったが、server の tick 基準の位置 (MDM_DECISIONS D4) がまだ終端の
     * 手前にいる状態か。TPS が 20 を割ると server は実時間より遅れるので、そのまま共通の判定
     * ({@link PlaybackRequestGate}: 止まった再生は鳴らし直す) に任せると末尾を2回鳴らす。
     * server が次の曲へ進めるか止めるまで待つ。鳴り切ったかは client 自身の時計で判定するので、
     * server の遅れの大きさに依らない。曲の途中での停止 (channel の喪失) は従来どおり鳴らし直す。
     */
    private static boolean finishedAheadOfServer(Playing current, Long previousGeneration,
            long generation, String sourceKey, CustomTrackData track, long serverOffsetMs) {
        if (current == null || current.vanilla() || !current.instance().isVoiceStopped()
                || previousGeneration == null || previousGeneration.longValue() != generation
                || !sourceKey.equals(current.key()) || track.isEmpty() || track.radio()
                || track.durationMs() <= 0L || current.startOffsetMs() < 0L || current.startedAtMs() < 0L) {
            return false;
        }
        final long played = Math.max(0L, TrackAudioResolver.playbackClockMs() - current.startedAtMs());
        return current.startOffsetMs() + played >= track.durationMs() - NATURAL_END_WINDOW_MS
                && serverOffsetMs < track.durationMs();
    }

    /** vanilla / 他 MOD のレコード。同期で鳴らせるのでロード経路は持たない。 */
    private static void playVanilla(UUID source, Integer route, VanillaTrackData vanilla,
            GoldenCartPlayPayload payload) {
        final CartAnchor anchor = CartAnchor.forEntity(payload.entityId());
        if (anchor == null) {
            return; // 追従先がまだ client に居ない。keep-alive が次の tick で再送する
        }
        anchor.refresh(payload.volumePercent(), payload.rangeBlocks(), payload.directional());
        if (vanilla.durationMs() > 0L) {
            anchor.armTailSilence(vanilla.durationMs() - payload.startOffsetMs(), 0L);
        }
        final int limit = Config.maxConcurrent();
        if (PlaybackConcurrency.client().sweepAll() >= limit) {
            PlaybackFailureReport.report((CustomTrackData) null,
                    PlaybackFailure.concurrentLimit(limit));
            return;
        }
        final VanillaSpeakerSoundInstance instance = new VanillaSpeakerSoundInstance(
                vanilla.soundEventId(), payload.startOffsetMs(), anchor);
        if (!startVanillaCartVoice(instance, instance::stopAndRelease,
                failure -> PlaybackFailureReport.report((CustomTrackData) null, failure),
                () -> ACTIVE.put(source, new Playing("vanilla:" + vanilla.soundEventId(), anchor, instance, true)))) {
            return;
        }
    }

    /**
     * SoundEngineAcceptance の境界で cart voice を一度だけ起動してから所有状態を公開する。
     * SoundManager.play を呼ぶのは Engine.playAndConfirm の一度だけで、受理後に同じ instance を再投入しない。
     */
    static boolean startVanillaCartVoice(SoundEngineAcceptance.Engine engine, Runnable onRejectedCleanup,
            Consumer<PlaybackFailure> onRejected, Runnable onAccepted) {
        if (!SoundEngineAcceptance.start(engine, onRejectedCleanup, onRejected)) {
            return false;
        }
        onAccepted.run();
        return true;
    }

    /** vanilla cart だけを数える。custom cart は SESSIONS 側ですでに数えている。 */
    static int sweepVanilla(Map<UUID, Playing> active) {
        active.entrySet().removeIf(entry -> entry.getValue().vanilla()
                && entry.getValue().instance().isVoiceStopped());
        return active.values().stream().filter(Playing::vanilla)
                .mapToInt(playing -> playing.instance().voiceCount()).sum();
    }

    /** custom track。ストリーム解決は playback スレッドで、完了は main thread へ戻す。 */
    private static void playCustom(UUID source, CustomTrackData track,
            GoldenCartPlayPayload payload) {
        if (refusedByConcurrencyLimit(source, track)) {
            return;
        }
        PENDING.put(source, track.sourceKey());
        final long startOffsetMs = payload.startOffsetMs();
        final PlaybackSessions.StartDecision start = SESSIONS.start(source, track, startOffsetMs,
                payload.rangeBlocks(), payload.volumePercent(), false);
        final int token = start.token();
        final long requestedAtMs = TrackAudioResolver.playbackClockMs();
        POOL.submit(() -> {
            IAudioSource audioSource = null;
            PlaybackFailure failure = null;
            try {
                // SSRF 遮断: 悪意ある disc データ (内部 IP URL) で他プレイヤーの client を踏み台にさせない
                if (!track.isLocal()) UrlGuard.enforce(track.url());
                final OpenStreamResult result =
                        TrackAudioResolver.openSynced(track, startOffsetMs, requestedAtMs);
                audioSource = result.source();
                failure = result.isOk() ? null
                        : PlaybackFailure.ofReason(result.reason(), result.detail());
            } catch (final UrlBlockedException blocked) {
                failure = PlaybackFailure.blocked(blocked.reason());
            } catch (final Throwable t) {
                failure = PlaybackFailure.thrown(t);
            }
            final IAudioSource resolved = audioSource;
            final PlaybackFailure reported =
                    resolved == null && failure == null ? PlaybackFailure.streamUnavailable() : failure;
            if (resolved != null) {
                resolved.onPlaybackFault(broken -> Minecraft.getInstance().execute(
                        () -> lateFailure(source, token, track,
                                PlaybackFailure.ofReason(broken.reason(), broken.detail()))));
            }
            Minecraft.getInstance().execute(() -> onLoaded(payload, source, track,
                    TrackAudioResolver.actualOffset(resolved, startOffsetMs), resolved,
                    reported, token));
        });
    }

    private static void onLoaded(GoldenCartPlayPayload payload, UUID source, CustomTrackData track,
            long startOffsetMs, IAudioSource resolved, PlaybackFailure failure, int token) {
        final boolean applied = SESSIONS.completeIfLive(source, token,
                () -> applyCurrentLoad(payload, source, track, startOffsetMs, resolved, failure, token));
        if (!applied && resolved != null) {
            resolved.close(); // ロード中に停止 / 同じ曲を含む後続世代が来た
        }
    }

    /** 現世代と確認できたロード完了だけを状態へ反映する (main thread)。 */
    private static void applyCurrentLoad(GoldenCartPlayPayload payload, UUID source,
            CustomTrackData track, long startOffsetMs, IAudioSource resolved,
            PlaybackFailure failure, int token) {
        // 後続の要求が別の曲で走り出していたら、その記録を消さない (2 引数版の remove)。
        PENDING.remove(source, track.sourceKey());
        if (resolved == null) {
            SESSIONS.abandon(source, token);
            if (BACKOFF.recordFailure(source, track.sourceKey())) {
                PlaybackFailureReport.report(track,
                        failure == null ? PlaybackFailure.streamUnavailable() : failure);
            }
            return;
        }
        BACKOFF.reset(source);
        final GoldenCartPlayPayload latest = LATEST_REQUESTS.get(source);
        final GoldenCartPlayPayload liveRequest = latest != null
                && latest.identity().generation() == payload.identity().generation()
                ? latest : payload;
        final CartAnchor live = CartAnchor.forEntity(liveRequest.entityId());
        if (live == null) {
            resolved.close(); // 追従先が視界から消えた / world が無い
            SESSIONS.abandon(source, token);
            return;
        }
        live.refresh(liveRequest.volumePercent(), liveRequest.rangeBlocks(),
                liveRequest.directional());
        if (refusedByConcurrencyLimit(source, track)) {
            resolved.close();
            SESSIONS.abandon(source, token);
            return;
        }
        final long automaticDuration = track.radio() ? 0L : track.durationMs();
        if (automaticDuration > 0L) {
            live.armTailSilence(automaticDuration - startOffsetMs, 0L);
        }
        final DiscSoundInstance instance = new DiscSoundInstance(live, resolved,
                liveRequest.rangeBlocks(), liveRequest.volumePercent(), null);
        instance.setDirectional(liveRequest.directional());
        instance.setFailureSink(
                late -> Minecraft.getInstance().execute(() -> lateFailure(source, token, track, late)));
        if (!SESSIONS.install(source, token, track.sourceKey(), startOffsetMs, instance)) {
            instance.requestStop();
            return;
        }
        final Playing previous = ACTIVE.put(source, new Playing(track.sourceKey(), live, instance, false,
                startOffsetMs, TrackAudioResolver.playbackClockMs()));
        if (previous != null && previous.instance() != instance) {
            previous.instance().stopAndRelease();
        }
        final Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(instance);
        LIMIT_NOTICES.forget(source); // 鳴り始めた = 次に上限へ当たったらまた出してよい
        final String desc = (track.author() != null && !track.author().isBlank())
                ? track.author() + " - " + track.title()
                : track.title();
        if (desc != null && !desc.isBlank()) {
            //? if >=26.2 {
            mc.gui.hud.setNowPlaying(Component.literal(desc));
            //?} else {
            /*mc.gui.setNowPlaying(Component.literal(desc));
            *///?}
        }
    }

    /**
     * 同時再生の枠が埋まっているか。埋まっていれば利用者に返す (機体ごとに 1 回だけ)。
     * 数えるのは金ジュークと合算 ({@link PlaybackConcurrency})。
     */
    private static boolean refusedByConcurrencyLimit(UUID source, CustomTrackData track) {
        final int playing = PlaybackConcurrency.client().sweepAll();
        final int limit = Config.maxConcurrent();
        if (playing < limit) {
            return false;
        }
        final PlaybackFailure overLimit = PlaybackFailure.concurrentLimit(limit);
        if (LIMIT_NOTICES.shouldReport(source, overLimit)) {
            PlaybackFailureReport.report(track, overLimit);
        }
        return true;
    }

    /** 再生スレッドの中で落ちた失敗を受ける (main thread)。出すかどうかの判断はセッション側。 */
    private static void lateFailure(UUID source, int token, CustomTrackData track,
            PlaybackFailure late) {
        final PlaybackFailure show = SESSIONS.lateFailure(source, token, track, late);
        if (show != null) {
            PlaybackFailureReport.report(track, show);
        }
    }

    /**
     * server 由来の明示停止 (stop payload 経由)。<b>ここが失敗予算のリセット境界</b>。
     */
    public static void stop(GoldenCartStopPayload payload) {
        stop(payload.entityId(), payload.identity());
    }

    /** 停止形の play payload 経由の明示停止。 */
    private static void stop(GoldenCartPlayPayload payload) {
        stop(payload.entityId(), payload.identity());
    }

    private static void stop(int entityId, PlaybackSourceStamp identity) {
        final UUID source = identity.sourceId();
        if (source == null) {
            return;
        }
        // 世代が合わない / 別 entity の経路を持つ旧い停止は畳まない (拾い直し後の新経路を守る)
        final boolean removed = ClientPlaybackHandler.SOURCE_OWNERS.stop(
                source, identity.generation(), entityId);
        if (!removed) {
            // false は lease 不在だけでなく、現行 lease と generation / route が違う場合も表す。
            // 所有状態を区別できないため、ここでは teardown せず現行経路を保護する。
            return;
        }
        LATEST_REQUESTS.remove(source);
        BACKOFF.reset(source);
        LIMIT_NOTICES.forget(source);
    }

    /** lease 失効時に ownership から呼ばれる畳み込み (別経路への置き換え含む)。 */
    private static void teardownOwned(UUID source, Integer route) {
        teardown(source);
    }

    /** 鳴っているものを畳むだけ (失敗予算には触らない)。 */
    private static void teardown(UUID source) {
        SESSIONS.stop(source);
        PENDING.remove(source);
        final Playing playing = ACTIVE.remove(source);
        if (playing != null) {
            playing.instance().stopAndRelease();
        }
    }

    /** その機体がいま鳴っているか (実際に音が出ている側の判定)。 */
    public static boolean isPlaying(UUID sourceId) {
        return sourceId != null && ACTIVE.containsKey(sourceId);
    }

    /** 切断・ワールド退出での一括停止 ({@link ClientPlaybackManager#stopAll()} から呼ばれる)。 */
    public static void stopAll() {
        SESSIONS.stopAll();
        PENDING.clear();
        LATEST_REQUESTS.clear();
        BACKOFF.clear();
        LIMIT_NOTICES.forgetAll();
        ACTIVE.values().forEach(playing -> playing.instance().stopAndRelease());
        ACTIVE.clear();
    }
}
