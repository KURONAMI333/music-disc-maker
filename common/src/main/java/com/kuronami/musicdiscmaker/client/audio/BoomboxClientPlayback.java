package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.event.BoomboxCarry;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
import com.kuronami.musicdiscmaker.network.BoomboxPlayPayload;
import com.kuronami.musicdiscmaker.network.UrlBlockedException;
import com.kuronami.musicdiscmaker.network.UrlGuard;

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
 * 1 回へ畳み (<b>上限に当たった時に黙って鳴らないのが最悪</b>＝KURONAMI333 裁定なので出すのはやめない)、
 * <b>再試行</b>は {@link #refusedByConcurrencyLimit} をロードを起こす<b>前</b>にも置いて潰す。
 * 後ろだけで見ると、枠が埋まっている間じゅう毎秒 URL を開いては閉じることになる — チャットは
 * 静かでも回線を叩き続ける。ここで {@link LoadFailureBackoff} を使わないのは、あれが恒久的な
 * 打ち切りだから (枠が空いても鳴らなくなる)。
 */
public final class BoomboxClientPlayback {

    private record Playing(String url, BoomboxAnchor anchor, DiscSoundInstance instance) {
    }

    private static final Map<Long, Playing> ACTIVE = new ConcurrentHashMap<>();

    /**
     * ロード中の要求 (機体 → URL)。世代トークンとは別の関心なので両方要る
     * ({@link PlaybackRequestGate} の javadoc)。
     */
    private static final Map<Long, String> PENDING = new ConcurrentHashMap<>();

    /** ロード中の移動・音量変更も、完了時には最新の同一音声世代へ反映する。 */
    private static final Map<Long, BoomboxPlayPayload> LATEST_REQUESTS = new ConcurrentHashMap<>();

    private static final PlaybackSessions<Long> SESSIONS =
            new PlaybackSessions<>(System::currentTimeMillis);

    private static final LoadFailureBackoff<Long> BACKOFF = new LoadFailureBackoff<>();

    /** 同時再生上限の報告の重複抑止 (1 Hz の keep-alive でチャットを埋めない)。 */
    private static final PlaybackFailureNotices LIMIT_NOTICES = new PlaybackFailureNotices();

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-boombox-playback");
        thread.setDaemon(true);
        return thread;
    });

    static {
        // 同時再生の枠は金ジュークと共有する (KURONAMI333 裁定 2026-09-07)。
        PlaybackConcurrency.client().register(SESSIONS);
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
        final CustomTrackData track = payload.track();
        if (track == null || track.isEmpty()) {
            return;
        }
        final long id = payload.boomboxId();
        if (id == BoomboxContents.UNASSIGNED) {
            return;
        }
        final BoomboxPlayPayload previousRequest = LATEST_REQUESTS.put(id, payload);
        final Long previousGeneration = previousRequest == null ? null : previousRequest.audioGeneration();
        if (previousGeneration != null && previousGeneration.longValue() != payload.audioGeneration()) {
            BACKOFF.reset(id);
            LIMIT_NOTICES.forget(id);
        }
        final Playing current = ACTIVE.get(id);
        final PlaybackRequestGate.Decision decision = PlaybackRequestGate.decide(
                current == null ? null : current.url(),
                current != null && current.instance().isVoiceStopped(),
                PENDING.get(id), track.url(), previousGeneration, payload.audioGeneration());
        if (decision == PlaybackRequestGate.Decision.REFRESH) {
            current.anchor().refresh();
            current.instance().setVolumePercent(payload.volumePercent());
            retargetIfMoved(current.anchor(), payload);
            return;
        }
        if (decision == PlaybackRequestGate.Decision.IGNORE) {
            return; // 同じ曲をロード中。ここで起こし直すと 1 秒ごとに永久リトライになる。
        }
        if (BACKOFF.isExhausted(id, track.url())) {
            return; // 同じ URL で 2 回落ちている。keep-alive のたびに繋ぎ直すのをここで止める
        }
        teardown(id); // 予算は畳まない (畳むと失敗 → 再送 → 予算リセットの輪が閉じない)
        // 上限はロードを起こす前に見る。後ろだけで見ると、枠が埋まっている間じゅう
        // 1 秒ごとに URL を開いては閉じることになる (チャットは静かでも回線は叩き続ける)。
        // 予算 (BACKOFF) は使わない — 枠が空いた瞬間に次の心拍で通ってほしいので、
        // 恒久的な打ち切りにはしない。
        if (refusedByConcurrencyLimit(id, track)) {
            return;
        }
        PENDING.put(id, track.url());
        final long startOffsetMs = payload.startOffsetMs();
        final PlaybackSessions.StartDecision start = SESSIONS.start(id, track, startOffsetMs,
                Config.boomboxRange(), payload.volumePercent(), false);
        final int token = start.token();
        POOL.submit(() -> {
            IAudioSource source = null;
            PlaybackFailure failure = null;
            try {
                // SSRF 遮断: 悪意ある disc データ (内部 IP URL) で他プレイヤーの client を踏み台にさせない
                UrlGuard.enforce(track.url());
                final OpenStreamResult result =
                        LoaderHolder.get().openStreamDetailed(track.url(), startOffsetMs);
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
                        () -> lateFailure(id, token, track,
                                PlaybackFailure.ofReason(broken.reason(), broken.detail()))));
            }
            Minecraft.getInstance().execute(() -> onLoaded(payload, track, startOffsetMs, resolved,
                    reported, token));
        });
    }

    private static void onLoaded(BoomboxPlayPayload payload, CustomTrackData track, long startOffsetMs,
            IAudioSource resolved, PlaybackFailure failure, int token) {
        final long id = payload.boomboxId();
        final boolean applied = SESSIONS.completeIfLive(id, token,
                () -> applyCurrentLoad(payload, track, startOffsetMs, resolved, failure, token));
        if (!applied && resolved != null) {
            resolved.close(); // ロード中に停止 / 同じ URL を含む後続世代が来た
        }
    }

    /** 現世代と確認できたロード完了だけを状態へ反映する (main thread)。 */
    private static void applyCurrentLoad(BoomboxPlayPayload payload, CustomTrackData track,
            long startOffsetMs, IAudioSource resolved, PlaybackFailure failure, int token) {
        final long id = payload.boomboxId();
        // 後続の要求が別の曲で走り出していたら、その記録を消さない (2 引数版の remove)。
        PENDING.remove(id, track.url());
        if (resolved == null) {
            // ロード失敗。ガードを外しただけだと次の keep-alive (1 秒後) が必ず繋ぎ直す。
            // 報告は予算を使い切った 1 回だけ (毎回出すと 1 Hz でチャットが埋まる)。
            SESSIONS.abandon(id, token);
            if (BACKOFF.recordFailure(id, track.url())) {
                PlaybackFailureReport.report(track,
                        failure == null ? PlaybackFailure.streamUnavailable() : failure);
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
        if (refusedByConcurrencyLimit(id, track)) {
            resolved.close();
            SESSIONS.abandon(id, token);
            return;
        }
        final DiscSoundInstance instance = new DiscSoundInstance(live, resolved,
                Config.boomboxRange(), liveRequest.volumePercent(), null);
        // 携帯は指向性を持たない (KURONAMI333 裁定「範囲を伸ばせず、指向性も持たない」)。
        instance.setDirectional(false);
        instance.setFailureSink(
                late -> Minecraft.getInstance().execute(() -> lateFailure(id, token, track, late)));
        if (!SESSIONS.install(id, token, track.url(), startOffsetMs, instance)) {
            instance.requestStop();
            return;
        }
        final Playing previous = ACTIVE.put(id, new Playing(track.url(), live, instance));
        if (previous != null && previous.instance() != instance) {
            previous.instance().stopAndRelease();
        }
        final Minecraft mc = Minecraft.getInstance();
        mc.getSoundManager().play(instance);
        com.kuronami.musicdiscmaker.network.BoomboxTrace.audio("client-sound-submitted", liveRequest);
        LIMIT_NOTICES.forget(id); // 鳴り始めた = 次に上限へ当たったらまた出してよい
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
     *
     * <p>数えるのは金ジュークと合算 ({@link PlaybackConcurrency})。OpenAL の streaming プールは
     * 1 つしか無いので、経路ごとに数えると実効上限が経路の数だけ増える。
     *
     * @return 枠が無い = 鳴らしてはいけない
     */
    private static boolean refusedByConcurrencyLimit(long id, CustomTrackData track) {
        final int playing = PlaybackConcurrency.client().sweepAll();
        final int limit = Config.maxConcurrent();
        if (playing < limit) {
            return false;
        }
        // 無言で鳴らさないのが最悪 (KURONAMI333 裁定)。ただし 1 Hz の再送で積まないよう 1 回に畳む。
        final PlaybackFailure overLimit = PlaybackFailure.concurrentLimit(limit);
        if (LIMIT_NOTICES.shouldReport(id, overLimit)) {
            PlaybackFailureReport.report(track, overLimit);
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
    private static void lateFailure(long id, int token, CustomTrackData track, PlaybackFailure late) {
        final PlaybackFailure show = SESSIONS.lateFailure(id, token, track, late);
        if (show != null) {
            // 金ジュークの失敗ラベル (GoldenJukeboxFailures) には載せない。あれは座標鍵で
            // 金ジュークの画面が読む入れ物なので、携帯の失敗を混ぜると別の音源の理由に見える。
            PlaybackFailureReport.report(track, show);
        }
    }

    /**
     * server 由来の明示停止。<b>ここが失敗予算のリセット境界</b> — 一度リンク切れで打ち切られた曲も、
     * 止めて掛け直せば試し直せる。
     *
     * @param boomboxId 機体の識別子
     */
    public static void stop(long boomboxId) {
        com.kuronami.musicdiscmaker.network.BoomboxTrace.stop("client-stop-received", boomboxId);
        LATEST_REQUESTS.remove(boomboxId);
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
        SESSIONS.stopAll();
        PENDING.clear();
        LATEST_REQUESTS.clear();
        BACKOFF.clear();
        LIMIT_NOTICES.forgetAll();
        ACTIVE.values().forEach(playing -> playing.instance().stopAndRelease());
        ACTIVE.clear();
    }
}
