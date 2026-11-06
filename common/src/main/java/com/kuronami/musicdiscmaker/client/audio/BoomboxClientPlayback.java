package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.event.BoomboxCarry;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.network.BoomboxPlayPayload;
import com.kuronami.musicdiscmaker.network.UrlBlockedException;
import com.kuronami.musicdiscmaker.network.UrlGuard;
import com.kuronami.musicdiscmaker.platform.Services;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * client 側: ブームボックスの再生統括。<b>鍵は機体の識別子</b> ({@code BoomboxContents#id})。
 *
 * <p>{@link ClientPlaybackManager} と分けてあるのは、あちらが金ジュークの都合
 * (先読み・ラジオ再接続・進捗アンカーの打ち直し・{@link GoldenJukeboxFailures} への記録) を
 * 抱えているから。<b>共有するのは容れ物 ({@link PlaybackSessions}) と同時再生の勘定
 * ({@link PlaybackConcurrency})</b> の 2 つで、金ジュークの状態には触らない。
 *
 * <h2>server は 1 秒ごとに撃ってくる — 3 つのガードが要る</h2>
 * <ol>
 *   <li>{@link PlaybackRequestGate} — <b>ロード中の再送でロードを起こし直さない</b>。これが
 *       無いと、確立に 1 秒以上かかる音源は心拍のたびに捨てられて<b>永久に鳴らない</b></li>
 *   <li>{@link PlaybackSessions} の世代トークン — 完走した複数のロードのうち最新だけを採る</li>
 *   <li>{@link LoadFailureBackoff} — リンク切れ URL で 1 Hz の接続試行を続けない</li>
 * </ol>
 *
 * <p>同時再生上限も 1 Hz で効いてくる。<b>報告</b>は {@link PlaybackFailureNotices} で機体ごとに
 * 1 回へ畳み (<b>上限に当たった時に黙って鳴らないのが最悪</b>＝設計上の決定なので出すのはやめない)、
 * <b>再試行</b>は {@link #refusedByConcurrencyLimit} をロードを起こす<b>前</b>にも置いて潰す。
 * 後ろだけで見ると、枠が埋まっている間じゅう毎秒 URL を開いては閉じることになる — チャットは
 * 静かでも回線を叩き続ける。ここで {@link LoadFailureBackoff} を使わないのは、あれが恒久的な
 * 打ち切りだから (枠が空いても鳴らなくなる)。
 */
public final class BoomboxClientPlayback {

    /** {@code vanillaAnchor} はバニラ・他 MOD の盤の音だけが持つ (音量の更新先)。 */
    private record Playing(String url, BoomboxAnchor anchor, PlaybackVoice instance,
            @org.jetbrains.annotations.Nullable BoomboxVanillaAnchor vanillaAnchor) {
        boolean vanilla() {
            return vanillaAnchor != null;
        }
    }

    private static final Map<Long, Playing> ACTIVE = new ConcurrentHashMap<>();
    /**
     * 「Now Playing」は音の届く範囲 (ブームボックスの可聴範囲) にいる時だけ出す (2026-10-09 本人裁定)。
     * 再生の通知は chunk を見ている全員へ届くので、範囲の外の player にも来る。
     */
    private static boolean withinBoomboxRange(BoomboxAnchor anchor) {
        final net.minecraft.world.phys.Vec3 ear = DiscSoundInstance.listenerPos();
        if (ear == null) {
            return false;
        }
        final double limit = Config.boomboxRange() + 1.0D;
        return ear.distanceToSqr(anchor.worldPos(1.0F)) <= limit * limit;
    }

    /**
     * 機体ごとに最後に「Now Playing」を出した曲。同じ曲を鳴らし直す (単曲 repeat) ときは最初の1回だけ出す
     * (2026-10-09 本人裁定)。一時停止から同じ曲を再開したときも出さない。ワールド退出で忘れる。
     */
    private static final Map<Long, String> ANNOUNCED = new ConcurrentHashMap<>();

    /**
     * ロード中の要求 (機体 → URL)。世代トークンとは別の関心なので両方要る
     * ({@link PlaybackRequestGate} の javadoc)。
     */
    private static final Map<Long, String> PENDING = new ConcurrentHashMap<>();

    /** ロード中の移動・音量変更も、完了時には最新の同一音声世代へ反映する。 */
    private static final Map<Long, BoomboxPlayPayload> LATEST_REQUESTS = new ConcurrentHashMap<>();
    /** {@link #LATEST_REQUESTS} の offset が指していた playback clock 。 */
    private static final Map<Long, Long> LATEST_RECEIVED_MS = new ConcurrentHashMap<>();

    private static final PlaybackSessions<Long> SESSIONS =
            new PlaybackSessions<>(System::currentTimeMillis);

    /**
     * 曲の終わりの停止を受けて、前の音が鳴り終わるのを待っている機体 ({@link NaturalEndHandoff})。
     * 待っている間に届いた次の曲は {@link #HELD_START} と {@link #LATEST_REQUESTS} に残し、前の音が止まってから鳴らす。
     */
    private static final Map<Long, NaturalEndHandoff> HANDOFFS = new ConcurrentHashMap<>();

    /**
     * 待っている間に最初に届いた次の曲の要求。次の曲はこの位置 (ふつうは頭) から鳴らす。後から届く送り直しは
     * server の位置へ進んでいるので、それで開くと待った分だけ次の曲の頭が欠ける。
     */
    private static final Map<Long, BoomboxPlayPayload> HELD_START = new ConcurrentHashMap<>();

    /** 最初の実音が鳴った時 (音声世代、再生時計、開いた位置)。曲の終わりの待ちの上限を出すのに使う。 */
    private record FirstAudio(long audioGeneration, long atMs, long startOffsetMs) {
    }

    private static final Map<Long, FirstAudio> FIRST_AUDIO = new ConcurrentHashMap<>();

    /** 前の曲を鳴らし切ってから始めた曲が server の位置より遅れた長さ (音声世代 → ms)。 */
    private record StartedBehind(long audioGeneration, long behindMs) {
    }

    private static final Map<Long, StartedBehind> STARTED_BEHIND = new ConcurrentHashMap<>();

    private static final LoadFailureBackoff<Long> BACKOFF = new LoadFailureBackoff<>();

    /** 同時再生上限の報告の重複抑止 (1 Hz の keep-alive でチャットを埋めない)。 */
    private static final PlaybackFailureNotices LIMIT_NOTICES = new PlaybackFailureNotices();

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-boombox-playback");
        thread.setDaemon(true);
        return thread;
    });

    static {
        // 同時再生の枠は金ジュークと共有する (設計上の決定 2026-09-07)。
        PlaybackConcurrency.client().register(SESSIONS);
        // バニラ・他 MOD の盤の音は PlaybackSessions に入らないので、ここで数えて掃除する。
        PlaybackConcurrency.client().registerCounter(BoomboxClientPlayback::sweepVanilla);
    }

    private BoomboxClientPlayback() {
    }

    /**
     * 再生 / keep-alive の受信 (main thread)。同じ曲なら生存時刻を更新するだけで、ストリームには
     * 触らない。
     *
     * @param payload server が撃った再生要求
     */
    public static void play(BoomboxPlayPayload payload) {
        com.kuronami.musicdiscmaker.network.BoomboxTrace.audio("client-play-received", payload);
        if (heldForNaturalEnd(payload)) {
            return;
        }
        if (payload.isVanilla()) {
            playVanilla(payload);
            return;
        }
        final CustomTrackData track = payload.track();
        if (track == null || track.isEmpty()) {
            return;
        }
        final long id = payload.boomboxId();
        if (id == BoomboxContents.UNASSIGNED) {
            return;
        }
        final BoomboxPlayPayload previousRequest = LATEST_REQUESTS.put(id, payload);
        LATEST_RECEIVED_MS.put(id, playbackClockMs());
        final Long previousGeneration = previousRequest == null ? null : previousRequest.audioGeneration();
        if (previousGeneration != null && previousGeneration.longValue() != payload.audioGeneration()) {
            BACKOFF.reset(id);
            LIMIT_NOTICES.forget(id);
        }
        final Playing current = ACTIVE.get(id);
        final PlaybackRequestGate.Decision decision = PlaybackRequestGate.decide(
                current == null ? null : current.url(),
                current != null && current.instance().isVoiceStopped(),
                PENDING.get(id), track.sourceKey(), previousGeneration, payload.audioGeneration());
        if (decision == PlaybackRequestGate.Decision.REFRESH) {
            refresh(current, payload);
            return;
        }
        if (decision == PlaybackRequestGate.Decision.IGNORE) {
            return; // 同じ曲をロード中。ここで起こし直すと 1 秒ごとに永久リトライになる。
        }
        if (BACKOFF.isExhausted(id, track.sourceKey())) {
            return; // 同じ URL で 2 回落ちている。keep-alive のたびに繋ぎ直すのをここで止める
        }
        teardown(id); // 予算は畳まない (畳むと失敗 → 再送 → 予算リセットの輪が閉じない)
        // 上限はロードを起こす前に見る。後ろだけで見ると、枠が埋まっている間じゅう
        // 1 秒ごとに URL を開いては閉じることになる (チャットは静かでも回線は叩き続ける)。
        // 予算 (BACKOFF) は使わない — 枠が空いた瞬間に次の心拍で通ってほしいので、
        // 恒久的な打ち切りにはしない。
        if (refusedByConcurrencyLimit(payload, track)) {
            return;
        }
        PENDING.put(id, track.sourceKey());
        final long startOffsetMs = payload.startOffsetMs();
        final PlaybackSessions.StartDecision start = SESSIONS.start(id, track, startOffsetMs,
                Config.boomboxRange(), payload.volumePercent(), false);
        final int token = start.token();
        final long requestedAtMs = TrackAudioResolver.playbackClockMs();
        POOL.submit(() -> {
            IAudioSource source = null;
            PlaybackFailure failure = null;
            try {
                // SSRF 遮断: 悪意ある disc データ (内部 IP URL) で他プレイヤーの client を踏み台にさせない
                if (!track.isLocal()) UrlGuard.enforce(track.url());
                final OpenStreamResult result =
                        com.kuronami.musicdiscmaker.client.audio.TrackAudioResolver.openSynced(track, startOffsetMs, requestedAtMs);
                source = result.source();
                failure = result.isOk() ? null
                        : PlaybackFailure.ofReason(result.reason(), result.detail());
            } catch (final UrlBlockedException blocked) {
                failure = PlaybackFailure.blocked(blocked.reason());
            } catch (final Throwable t) {
                failure = PlaybackFailure.thrown(t);
            }
            final IAudioSource resolved = source;
            final PlaybackFailure reported =
                    resolved == null && failure == null ? PlaybackFailure.streamUnavailable() : failure;
            if (resolved != null) {
                resolved.onPlaybackFault(broken -> Minecraft.getInstance().execute(
                        () -> lateFailure(payload, token, track,
                                PlaybackFailure.ofReason(broken.reason(), broken.detail()))));
            }
            Minecraft.getInstance().execute(() -> onLoaded(payload, track, TrackAudioResolver.actualOffset(resolved, startOffsetMs), resolved,
                    reported, token, requestedAtMs));
        });
    }

    private static void onLoaded(BoomboxPlayPayload payload, CustomTrackData track, long startOffsetMs,
            IAudioSource resolved, PlaybackFailure failure, int token, long requestedAtMs) {
        final long id = payload.boomboxId();
        final boolean applied = SESSIONS.completeIfLive(id, token,
                () -> applyCurrentLoad(payload, track, startOffsetMs, resolved, failure, token, requestedAtMs));
        if (!applied && resolved != null) {
            resolved.close(); // ロード中に停止 / 同じ URL を含む後続世代が来た
        }
    }

    /** 現世代と確認できたロード完了だけを状態へ反映する (main thread)。 */
    private static void applyCurrentLoad(BoomboxPlayPayload payload, CustomTrackData track,
            long startOffsetMs, IAudioSource resolved, PlaybackFailure failure, int token, long requestedAtMs) {
        final long id = payload.boomboxId();
        // 後続の要求が別の曲で走り出していたら、その記録を消さない (2 引数版の remove)。
        PENDING.remove(id, track.sourceKey());
        if (resolved == null) {
            // ロード失敗。ガードを外しただけだと次の keep-alive (1 秒後) が必ず繋ぎ直す。
            // 報告は予算を使い切った 1 回だけ (毎回出すと 1 Hz でチャットが埋まる)。
            SESSIONS.abandon(id, token);
            if (BACKOFF.recordFailure(id, track.sourceKey())) {
                final PlaybackFailure shown = failure == null ? PlaybackFailure.streamUnavailable() : failure;
                PlaybackFailureReport.report(track, shown);
                BoomboxFailures.get().record(payload, shown);
            }
            return;
        }
        BACKOFF.reset(id); // 開けたので予算を返す
        final BoomboxPlayPayload latest = LATEST_REQUESTS.get(id);
        final BoomboxPlayPayload liveRequest = latest != null && latest.audioGeneration() == payload.audioGeneration()
                ? latest : payload;
        final BoomboxAnchor live = anchorFor(liveRequest);
        if (live == null) {
            com.kuronami.musicdiscmaker.network.BoomboxTrace.audio("client-anchor-missing", liveRequest);
            resolved.close(); // 持ち主が視界から消えた / world が無い
            SESSIONS.abandon(id, token);
            return;
        }
        // 2 つ目の関門。ロードを起こしてから完了するまでの間に他の音源が枠を埋めうる。
        if (refusedByConcurrencyLimit(payload, track)) {
            resolved.close();
            SESSIONS.abandon(id, token);
            return;
        }
        // 末尾の猶予帯は server が意図的に黙る (BoomboxHeartbeat.decide)。その沈黙を
        // 「server が消えた」と client が誤解しないよう、曲の想定終端を anchor に登録する。
        // 尺を持たないラジオ/ライブには終端が無いので登録しない (従来の途絶判定のまま)。
        final long automaticDuration = track.radio() ? 0L : track.durationMs();
        if (automaticDuration > 0L) {
            final StartedBehind behind = STARTED_BEHIND.remove(id);
            live.armTailSilence(automaticDuration - startOffsetMs, BoomboxPlayback.TAIL_GRACE_MS,
                    behind != null && behind.audioGeneration() == liveRequest.audioGeneration() ? behind.behindMs() : 0L);
        }
        final DiscSoundInstance instance = new DiscSoundInstance(live, resolved,
                Config.boomboxRange(), liveRequest.volumePercent(), null);
        // 携帯は指向性を持たない (設計上の決定「範囲を伸ばせず、指向性も持たない」)。
        instance.setDirectional(false);
        instance.setFailureSink(
                late -> Minecraft.getInstance().execute(() -> lateFailure(payload, token, track, late)));
        if (automaticDuration > 0L) {
            // 実音が鳴り始めた時の server の位置からの遅れを知らせる。server はいちばん遅い client が鳴り終わるまで
            // 次の曲・頭出し・停止を待つ (金のジュークボックスと同じ)。
            final long receivedOffsetMs = payload.startOffsetMs();
            final long audioGeneration = liveRequest.audioGeneration();
            final long audioStartOffsetMs = startOffsetMs;
            instance.setFirstAudioSink(() -> Minecraft.getInstance().execute(() -> {
                FIRST_AUDIO.put(id, new FirstAudio(audioGeneration, playbackClockMs(), audioStartOffsetMs));
                final long lag = receivedOffsetMs + (TrackAudioResolver.playbackClockMs() - requestedAtMs)
                        - audioStartOffsetMs;
                if (lag > 0L) {
                    com.kuronami.musicdiscmaker.platform.Services.NETWORK.sendToServer(
                            new com.kuronami.musicdiscmaker.network.BoomboxAudioLagPayload(id, audioGeneration, lag));
                }
            }));
        }
        if (!SESSIONS.install(id, token, track.sourceKey(), startOffsetMs, instance)) {
            instance.requestStop();
            return;
        }
        final Playing previous = ACTIVE.put(id, new Playing(track.sourceKey(), live, instance, null));
        if (previous != null && previous.instance() != instance) {
            previous.instance().stopAndRelease();
        }
        final Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(instance);
        BoomboxFailures.get().clear(payload);
        com.kuronami.musicdiscmaker.network.BoomboxTrace.audio("client-sound-submitted", liveRequest);
        LIMIT_NOTICES.forget(id); // 鳴り始めた = 次に上限へ当たったらまた出してよい
        final String desc = (track.author() != null && !track.author().isBlank())
                ? track.author() + " - " + track.title()
                : track.title();
        if (desc != null && !desc.isBlank() && withinBoomboxRange(live)
                && !track.sourceKey().equals(ANNOUNCED.put(id, track.sourceKey()))) {
            com.kuronami.musicdiscmaker.client.NowPlaying.show(Component.literal(desc));
        }
    }

    /** 同じ曲の送り直し: 生存を延ばし、音量と付き先 (置いた / 持った) を合わせる。音には触らない。 */
    private static void refresh(Playing current, BoomboxPlayPayload payload) {
        current.anchor().refresh();
        if (current.vanillaAnchor() != null) {
            current.vanillaAnchor().setVolumePercent(payload.volumePercent());
        }
        current.instance().setVolumePercent(payload.volumePercent());
        retargetIfMoved(current.anchor(), payload);
    }

    /**
     * バニラ・他 MOD の盤 (2026-10-09 本人裁定「どの盤でも」)。音は手元の資源にあるので読み込みは無く、
     * 金のジュークボックス付きトロッコと同じ {@link VanillaSpeakerSoundInstance} で持ち主 (置いたなら
     * その位置) に付いて鳴らす。
     */
    private static void playVanilla(BoomboxPlayPayload payload) {
        final long id = payload.boomboxId();
        if (id == BoomboxContents.UNASSIGNED) {
            return;
        }
        final com.kuronami.musicdiscmaker.component.VanillaTrackData vanilla = payload.vanilla();
        final String key = "vanilla:" + vanilla.soundEventId();
        final BoomboxPlayPayload previousRequest = LATEST_REQUESTS.put(id, payload);
        LATEST_RECEIVED_MS.put(id, playbackClockMs());
        final Playing current = ACTIVE.get(id);
        if (current != null && key.equals(current.url()) && !current.instance().isVoiceStopped()
                && previousRequest != null && previousRequest.audioGeneration() == payload.audioGeneration()) {
            refresh(current, payload);
            return;
        }
        teardown(id);
        final BoomboxAnchor live = anchorFor(payload);
        if (live == null) {
            return; // 持ち主が client にまだ居ない。次の送り直しで鳴らす
        }
        if (refusedByConcurrencyLimit(payload, null)) {
            return;
        }
        if (vanilla.durationMs() > 0L) {
            final StartedBehind behind = STARTED_BEHIND.remove(id);
            live.armTailSilence(vanilla.durationMs() - payload.startOffsetMs(), BoomboxPlayback.VANILLA_TAIL_GRACE_MS,
                    behind != null && behind.audioGeneration() == payload.audioGeneration() ? behind.behindMs() : 0L);
        }
        final BoomboxVanillaAnchor vanillaAnchor = new BoomboxVanillaAnchor(live, payload.volumePercent());
        final VanillaSpeakerSoundInstance instance = new VanillaSpeakerSoundInstance(
                vanilla.soundEventId(), payload.startOffsetMs(), vanillaAnchor);
        // 断られた理由 (音量 0 など) は送り直しのたびに起き直すので、機体ごとに 1 回だけ報告する。
        if (!GoldenCartClientPlayback.startVanillaCartVoice(instance, instance::stopAndRelease,
                failure -> {
                    if (LIMIT_NOTICES.shouldReport(id, failure)) {
                        PlaybackFailureReport.report((CustomTrackData) null, failure);
                        BoomboxFailures.get().record(payload, failure);
                    }
                },
                () -> ACTIVE.put(id, new Playing(key, live, instance, vanillaAnchor)))) {
            return;
        }
        LIMIT_NOTICES.forget(id);
        BoomboxFailures.get().clear(payload);
        final Minecraft mc = Minecraft.getInstance();
        final Component desc = mc.level == null ? null
                : com.kuronami.musicdiscmaker.component.PlayableDiscs.descriptionOfSound(vanilla.soundEventId(), mc.level);
        if (desc != null && withinBoomboxRange(live) && !key.equals(ANNOUNCED.put(id, key))) {
            com.kuronami.musicdiscmaker.client.NowPlaying.show(desc);
        }
    }

    /** バニラ・他 MOD の盤の音の数。止まったものはここで畳む。 */
    private static int sweepVanilla() {
        ACTIVE.entrySet().removeIf(entry -> entry.getValue().vanilla()
                && entry.getValue().instance().isVoiceStopped());
        return ACTIVE.values().stream().filter(Playing::vanilla)
                .mapToInt(playing -> playing.instance().voiceCount()).sum();
    }

    /**
     * 同時再生の枠が埋まっているか。埋まっていれば利用者に返す (機体ごとに 1 回だけ)。
     *
     * <p>数えるのは金ジュークと合算 ({@link PlaybackConcurrency})。OpenAL の streaming プールは
     * 1 つしか無いので、経路ごとに数えると実効上限が経路の数だけ増える。
     *
     * @return 枠が無い = 鳴らしてはいけない
     */
    private static boolean refusedByConcurrencyLimit(BoomboxPlayPayload payload,
            @org.jetbrains.annotations.Nullable CustomTrackData track) {
        final long id = payload.boomboxId();
        final int playing = PlaybackConcurrency.client().sweepAll();
        final int limit = Config.maxConcurrent();
        if (playing < limit) {
            return false;
        }
        // 無言で鳴らさないのが最悪 (設計上の決定)。ただし 1 Hz の再送で積まないよう 1 回に畳む。
        final PlaybackFailure overLimit = PlaybackFailure.concurrentLimit(limit);
        if (LIMIT_NOTICES.shouldReport(id, overLimit)) {
            PlaybackFailureReport.report(track, overLimit);
            BoomboxFailures.get().record(payload, overLimit);
        }
        return true;
    }

    /**
     * 鳴らしたまま置いた / 壊して拾い直した、で音源の張り付け先だけが移る場合の追従。
     *
     * <p>曲は変わらないので keep-alive は {@code REFRESH} で来る = ストリームには触らない。
     * ここで張り替えないと、置いた機体の音が持ち主に付いてくる (逆も同じ)。
     */
    private static void retargetIfMoved(BoomboxAnchor anchor, BoomboxPlayPayload payload) {
        if (payload.isCarried()) {
            if (anchor.carrierEntityId() == payload.ownerEntityId()) {
                return;
            }
            final Minecraft mc = Minecraft.getInstance();
            final Entity carrier = mc.level == null ? null : mc.level.getEntity(payload.ownerEntityId());
            if (carrier != null) {
                anchor.retargetCarried(carrier);
            }
            return;
        }
        final Vec3 target = Vec3.atCenterOf(payload.pos());
        if (anchor.carrierEntityId() < 0 && target.equals(anchor.placedTarget())) {
            return;
        }
        anchor.retargetPlaced(target);
    }

    /** 音源の張り付け先。追従先の entity が居なければ {@code null}。 */
    private static BoomboxAnchor anchorFor(BoomboxPlayPayload payload) {
        if (!payload.isCarried()) {
            return BoomboxAnchor.placed(payload.pos());
        }
        final Minecraft mc = Minecraft.getInstance();
        final Entity carrier = mc.level == null ? null : mc.level.getEntity(payload.ownerEntityId());
        return carrier == null ? null : BoomboxAnchor.carried(carrier);
    }

    /** 再生スレッドの中で落ちた失敗を受ける (main thread)。出すかどうかの判断はセッション側。 */
    private static void lateFailure(BoomboxPlayPayload payload, int token, CustomTrackData track, PlaybackFailure late) {
        final long id = payload.boomboxId();
        final PlaybackFailure show = SESSIONS.lateFailure(id, token, track, late);
        if (show != null) {
            // 金ジュークの失敗ラベル (GoldenJukeboxFailures) には載せない。あれは座標鍵で
            // 金ジュークの画面が読む入れ物なので、携帯の失敗を混ぜると別の音源の理由に見える。
            PlaybackFailureReport.report(track, show);
            BoomboxFailures.get().record(payload, show);
        }
    }

    /**
     * server 由来の明示停止。<b>ここが失敗予算のリセット境界</b> — 一度リンク切れで打ち切られた曲も、
     * 止めて掛け直せば試し直せる。
     *
     * @param boomboxId 機体の識別子
     */
    public static void stop(long boomboxId) {
        stop(boomboxId, false);
    }

    /**
     * server 由来の停止。曲を鳴らし切ったことによる停止 ({@code naturalEnd}) で、この client の音がまだ
     * 鳴っている (曲の途中の読み込みの詰まりで server より後ろにいる) 時は、鳴り終わるまで止めない
     * ({@link NaturalEndHandoff})。続けて届く次の曲もその後で鳴らす。それ以外の停止はすぐ止める。
     *
     * @param boomboxId  機体の識別子
     * @param naturalEnd 曲を鳴らし切ったことによる停止か
     */
    public static void stop(long boomboxId, boolean naturalEnd) {
        if (naturalEnd) {
            final NaturalEndHandoff waiting = HANDOFFS.get(boomboxId);
            if (waiting != null) {
                // 預かった次の曲も待っている間に server で終わった (次の曲が待ちより短い)。その曲は飛ばし、
                // 前の音を鳴らし切ってから、続く曲があればそれを鳴らす。
                if (waiting.dropHeldEndedBy(waiting.heldGeneration())) {
                    HELD_START.remove(boomboxId);
                }
                return;
            }
            if (deferNaturalEnd(boomboxId)) {
                return;
            }
        } else if (HANDOFFS.remove(boomboxId) != null) {
            HELD_START.remove(boomboxId);
            STARTED_BEHIND.remove(boomboxId);
            com.kuronami.musicdiscmaker.network.BoomboxTrace.stop("client-natural-end-cancelled", boomboxId);
        }
        stopNow(boomboxId);
    }

    /** 前の音が鳴っていれば待ちを始める。待つなら {@code true}。 */
    private static boolean deferNaturalEnd(long boomboxId) {
        final Playing current = ACTIVE.get(boomboxId);
        final BoomboxPlayPayload request = LATEST_REQUESTS.get(boomboxId);
        if (current == null || request == null || current.instance().isVoiceStopped()) {
            return false;
        }
        // 詰まっていなければ、実音が曲の長さの位置へ来たところで切る。詰まった分だけ後ろまで待つ。
        if (tailMs(boomboxId, current, request) <= 0L) {
            return false;
        }
        final NaturalEndHandoff handoff = new NaturalEndHandoff(request.audioGeneration(), playbackClockMs(),
                Minecraft.getInstance().level);
        HANDOFFS.put(boomboxId, handoff);
        com.kuronami.musicdiscmaker.network.BoomboxTrace.stop("client-natural-end-deferred", boomboxId);
        pollNaturalEnd(boomboxId, handoff, current, request);
        return true;
    }

    /** 前の音の残り ({@link NaturalEndHandoff#tailCapMs})。最初の実音を知らなければ上限まで待つ。 */
    private static long tailMs(long boomboxId, Playing previous, BoomboxPlayPayload request) {
        final long duration = request.isVanilla() ? request.vanilla().durationMs()
                : request.track() == null ? 0L : request.track().durationMs();
        final FirstAudio first = FIRST_AUDIO.get(boomboxId);
        final long audioPosition = first == null || first.audioGeneration() != request.audioGeneration() ? -1L
                : first.startOffsetMs() + Math.max(0L, playbackClockMs() - first.atMs());
        return NaturalEndHandoff.tailCapMs(duration, audioPosition, previous.instance().paddedSilenceMs());
    }

    private static void pollNaturalEnd(long boomboxId, NaturalEndHandoff handoff, Playing previous,
            BoomboxPlayPayload request) {
        java.util.concurrent.CompletableFuture.delayedExecutor(NaturalEndHandoff.POLL_MS,
                java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> Minecraft.getInstance().execute(() -> {
            if (HANDOFFS.get(boomboxId) != handoff) {
                return; // 手動の操作で畳まれた
            }
            if (!handoff.sameScope(Minecraft.getInstance().level)) {
                // 次元を移った (バニラは世界の切り替えで音を全部止める)。預かった次の曲は鳴らさない。持ち歩きなら
                // 次の送り直しが server の位置から鳴らし直す。
                HANDOFFS.remove(boomboxId, handoff);
                HELD_START.remove(boomboxId);
                stopNow(boomboxId);
                return;
            }
            final boolean stillPlaying = ACTIVE.get(boomboxId) == previous && !previous.instance().isVoiceStopped();
            if (handoff.keepWaiting(stillPlaying, playbackClockMs(), tailMs(boomboxId, previous, request))) {
                pollNaturalEnd(boomboxId, handoff, previous, request);
                return;
            }
            HANDOFFS.remove(boomboxId, handoff);
            finishNaturalEnd(boomboxId, handoff);
        }));
    }

    /** 前の音が鳴り終わった (または待ちの上限)。止めて、預かった次の曲があれば鳴らす。 */
    private static void finishNaturalEnd(long boomboxId, NaturalEndHandoff handoff) {
        final BoomboxPlayPayload first = HELD_START.remove(boomboxId);
        final BoomboxPlayPayload latest = handoff.holding() ? LATEST_REQUESTS.get(boomboxId) : null;
        final long behindMs = handoff.heldForMs(playbackClockMs());
        com.kuronami.musicdiscmaker.network.BoomboxTrace.stop("client-natural-end-finished", boomboxId);
        stopNow(boomboxId);
        if (first != null && latest != null && latest.audioGeneration() == handoff.heldGeneration()) {
            // 曲と開始位置は最初の要求、置き場所・音量は最新の送り直しのもの。
            final BoomboxPlayPayload next = new BoomboxPlayPayload(first.boomboxId(), latest.ownerEntityId(),
                    latest.pos(), first.track(), first.startOffsetMs(), first.audioGeneration(),
                    latest.volumePercent(), first.vanilla());
            STARTED_BEHIND.put(boomboxId, new StartedBehind(next.audioGeneration(), behindMs));
            play(next);
        }
    }

    /**
     * 曲の終わりを待っている機体に届いた再生要求。次の曲なら預かり ({@code true})、手動の操作 (別の曲)
     * なら待ちを畳んで今すぐ処理させる ({@code false})。
     */
    private static boolean heldForNaturalEnd(BoomboxPlayPayload payload) {
        final long id = payload.boomboxId();
        final NaturalEndHandoff handoff = HANDOFFS.get(id);
        if (handoff == null) {
            return false;
        }
        switch (handoff.arrive(payload.audioGeneration(), playbackClockMs(), null)) {
            case HOLD -> {
                HELD_START.putIfAbsent(id, payload);
                LATEST_REQUESTS.put(id, payload);
                LATEST_RECEIVED_MS.put(id, playbackClockMs());
                final Playing previous = ACTIVE.get(id);
                if (previous != null) {
                    previous.anchor().refresh(); // 前の音を keep-alive の途絶で止めない
                }
                return true;
            }
            case DROP -> {
                return true; // server では待っている間に終わった曲の送り直し
            }
            case SUPERSEDE -> {
                HANDOFFS.remove(id, handoff);
                HELD_START.remove(id);
                STARTED_BEHIND.remove(id);
                com.kuronami.musicdiscmaker.network.BoomboxTrace.stop("client-natural-end-cancelled", id);
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /** すぐ止める。 */
    private static void stopNow(long boomboxId) {
        com.kuronami.musicdiscmaker.network.BoomboxTrace.stop("client-stop-received", boomboxId);
        LATEST_REQUESTS.remove(boomboxId);
        LATEST_RECEIVED_MS.remove(boomboxId);
        // ANNOUNCED は残す: 止めて同じ曲を掛け直しても Now Playing を出し直さない (2026-10-09 本人裁定)。
        BACKOFF.reset(boomboxId);
        LIMIT_NOTICES.forget(boomboxId);
        teardown(boomboxId);
    }

    /** 鳴っているものを畳むだけ (失敗予算には触らない)。 */
    private static void teardown(long boomboxId) {
        SESSIONS.stop(boomboxId);
        PENDING.remove(boomboxId);
        final Playing playing = ACTIVE.remove(boomboxId);
        if (playing != null) {
            playing.instance().stopAndRelease();
        }
    }

    /**
     * その機体がいま鳴っているか。<b>見た目 (取っ手の姿勢・持ち方) の判定源はここ 1 箇所。</b>
     *
     * <p>server の {@code BoomboxPlayback#isPlaying} と別物であることに意味がある。見せたいのは
     * 「server が鳴らそうとしている」ではなく「実際に音が出ている」で、URL が死んでいる・同時再生の
     * 枠が埋まっている機体は<b>鳴っていない側</b>に居てほしい。
     *
     * @param boomboxId 機体の識別子
     * @return 鳴っていれば true
     */
    public static boolean isPlaying(long boomboxId) {
        return boomboxId != BoomboxContents.UNASSIGNED && ACTIVE.containsKey(boomboxId);
    }

    /**
     * そのスタックのブームボックスがいま鳴っているか。識別子は component (1.21+) / NBT (1.20.1) から読む。
     *
     * @param stack ブームボックスのスタック
     * @return 鳴っていれば true
     */
    public static boolean isPlaying(ItemStack stack) {
        return isPlaying(BoomboxCarry.idOf(stack));
    }

    /**
     * その座標に設置されている機体がいま鳴っているか。
     *
     * <p>BlockEntity は client へ同期していないので識別子で引けない。代わりに<b>音源アンカーの
     * 固定座標</b>で照合する — 設置中の機体は {@link BoomboxAnchor#placed} で作られており、
     * 持ち歩きへ張り替われば {@link BoomboxAnchor#carrierEntityId()} が非負になるので、
     * 「鳴らしたまま拾った」も取りこぼさない。
     *
     * @param pos 設置位置
     * @return その位置の機体が鳴っていれば true
     */
    public static boolean isPlayingAt(BlockPos pos) {
        final Vec3 target = Vec3.atCenterOf(pos);
        for (final Playing playing : ACTIVE.values()) {
            final BoomboxAnchor anchor = playing.anchor();
            if (anchor.carrierEntityId() < 0 && anchor.placedTarget().distanceToSqr(target) < 0.01D) {
                return true;
            }
        }
        return false;
    }

    /** 切断・ワールド退出での一括停止 ({@link ClientPlaybackManager#stopAll()} から呼ばれる)。 */
    public static void stopAll() {
        HANDOFFS.clear();
        HELD_START.clear();
        FIRST_AUDIO.clear();
        STARTED_BEHIND.clear();
        ANNOUNCED.clear();
        SESSIONS.stopAll();
        PENDING.clear();
        LATEST_REQUESTS.clear();
        LATEST_RECEIVED_MS.clear();
        BACKOFF.clear();
        LIMIT_NOTICES.forgetAll();
        BoomboxFailures.get().clearAll();
        ACTIVE.values().forEach(playing -> playing.instance().stopAndRelease());
        ACTIVE.clear();
    }

    /** SoundEngine 再読み込み後の stale channel を無効化し、再開処理を返す。 */
    static Runnable prepareSoundReloadResume() {
        // 鳴らし切りを待っていた前の音は読み直しで消える。預かった次の曲は LATEST_REQUESTS から開き直す。
        HANDOFFS.clear();
        HELD_START.clear();
        FIRST_AUDIO.clear();
        STARTED_BEHIND.clear();
        final Set<Long> live = new HashSet<>(ACTIVE.keySet());
        live.addAll(PENDING.keySet());
        final Map<Long, BoomboxPlayPayload> requests = new HashMap<>();
        final Map<Long, Long> received = new HashMap<>();
        for (long id : live) {
            final BoomboxPlayPayload payload = LATEST_REQUESTS.get(id);
            if (payload != null) {
                requests.put(id, payload);
                received.put(id, LATEST_RECEIVED_MS.getOrDefault(id, playbackClockMs()));
            }
            teardown(id);
        }
        return () -> {
            final long now = playbackClockMs();
            requests.forEach((id, payload) -> {
                final CustomTrackData track = payload.track();
                final long duration = payload.isVanilla() ? payload.vanilla().durationMs() : track.durationMs();
                final long offset = SoundReloadResume.offset(payload.startOffsetMs(), received.get(id), now,
                        duration, !payload.isVanilla() && track.radio());
                if (offset >= 0L) {
                    play(new BoomboxPlayPayload(payload.boomboxId(), payload.ownerEntityId(), payload.pos(), track,
                            offset, payload.audioGeneration(), payload.volumePercent(), payload.vanilla()));
                }
            });
        };
    }

    private static long playbackClockMs() {
        final var server = Minecraft.getInstance().getSingleplayerServer();
        return server instanceof com.kuronami.musicdiscmaker.component.PlaybackClockAccess clock
                ? clock.mdm$playbackTimeMs()
                : com.kuronami.musicdiscmaker.component.PauseAwarePlaybackClock.realTimeMs();
    }
}
