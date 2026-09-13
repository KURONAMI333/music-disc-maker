package com.kuronami.musicdiscmaker.client.audio;
//? if >=26.2 {
import java.util.concurrent.CompletableFuture;
//?} elif >=1.21.2 {
/*import java.util.concurrent.CompletableFuture;
*/
//?} elif >=1.21 {
/*import java.util.concurrent.CompletableFuture;
*/
//?} else {
//?}

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.HashMap;
import java.util.Map;
//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.MusicDiscMaker;
//?} elif >=1.21 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
*/
//?} else {
//?}

import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
import com.kuronami.musicdiscmaker.network.UrlBlockedException;
import com.kuronami.musicdiscmaker.network.UrlGuard;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;

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
            new PlaybackSessions<>(System::currentTimeMillis, MONOTONIC_MS);

    /** Golden音源位置ごとの固定speaker集合。値は進行中の音声インスタンスにも共有される。 */
    private final Map<BlockPos, MultiSpeakerAnchor> speakerAnchors = new HashMap<>();

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

    //? if >=26.2 {
    public void startPlayback(BlockPos pos, CustomTrackData track, long startOffsetMs,
            int rangeBlocks, int volumePercent, boolean directional) {
    //?} elif >=26.1 {
/*    public void startPlayback(BlockPos pos, CustomTrackData track, long startOffsetMs, int rangeBlocks,
            int volumePercent, boolean directional) {
    */
    //?} elif >=1.21.2 {
/*    public void startPlayback(BlockPos pos, CustomTrackData track, long startOffsetMs,
            int rangeBlocks, int volumePercent, boolean directional) {
    */
    //?} elif >=1.21 {
/*    public void startPlayback(BlockPos pos, CustomTrackData track, long startOffsetMs, int rangeBlocks,
            int volumePercent, boolean directional) {
    */
    //?} else {
/*    public void startPlayback(BlockPos pos, CustomTrackData track, long startOffsetMs,
            int rangeBlocks, int volumePercent, boolean directional) {
    */
    //?}
        if (track == null || track.isEmpty()) {
            return;
        }
        final BlockPos key = pos.immutable();
        final PlaybackSessions.StartDecision decision =
                sessions.start(key, track, startOffsetMs, rangeBlocks, volumePercent, directional);
        if (!decision.load()) {
            return; // 同じ曲の再送 (chunk 再入等)。聴取モデルだけ取り込み済み。
        }
        //? if >=1.21.2 {
        final PlaybackTiming timing =
                new PlaybackTiming(key.toShortString(), track.url(), decision.token(), false);
        submitLoad(key, track, startOffsetMs, rangeBlocks, volumePercent, 0L, decision.token(), timing);
        //?} elif >=1.21 {
/*        final IAudioSource warm = prefetch.claim(key, track.url(), startOffsetMs);
        final PlaybackTiming timing =
                new PlaybackTiming(key.toShortString(), track.url(), decision.token(), warm != null);
        if (warm != null) {
            timing.resolved();
            attachFaultSink(warm, key, decision.token(), track);
            Minecraft.getInstance().execute(() -> onLoaded(key, track, startOffsetMs, rangeBlocks,
                    volumePercent, warm, null, decision.token(), timing));
            return;
        }
        submitLoad(key, track, startOffsetMs, rangeBlocks, volumePercent, 0L, decision.token(), timing);
    }

    public void prefetchNext(BlockPos pos, CustomTrackData track, long remainingMs) {
        if (track == null || track.isEmpty() || track.radio() || track.durationMs() <= 0L) {
            return; // ラジオ・尺ゼロは先読みしない (終わらない / 掴む先が無い)
        }
        final PlaybackPrefetch.Ticket<BlockPos> ticket = prefetch.begin(pos.immutable(), track.url(), remainingMs);
        if (ticket == null) {
            return; // 曲が終了済み / 同じ曲を既に抱えている / 先読み枠の上限
        }
        MusicDiscMaker.LOGGER.debug("Prefetch fired [{}] url={} remaining={}ms ticket={}",
                pos.toShortString(), track.url(), remainingMs, ticket.id());
        pool.submit(() -> {
            IAudioSource source = null;
            try {
                UrlGuard.enforce(track.url());
                final OpenStreamResult result = LoaderHolder.get().openStreamDetailed(track.url(), 0L);
                source = result.source();
                if (source == null) {
                    MusicDiscMaker.LOGGER.warn("Prefetch could not open {} [{}] {}",
                            track.url(), result.reason(), result.detail());
                }
            } catch (final Throwable t) {
                source = null;
                MusicDiscMaker.LOGGER.warn("Prefetch threw while opening {}", track.url(), t);
            }
            if (!prefetch.deliver(ticket, source) && source != null) {
                MusicDiscMaker.LOGGER.debug("Prefetch discarded (no longer wanted) for {}", track.url());
                source.close();
            }
        });
    }

    public void cancelPrefetch(BlockPos pos, String reason) {
        prefetch.drop(pos.immutable(), reason);
    }

    public void cancelPrefetchUnlessMatches(BlockPos pos, CustomTrackData current, CustomTrackData next, String reason) {
        prefetch.dropUnlessMatches(pos.immutable(), current == null ? null : current.url(),
                next == null ? null : next.url(), reason);
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
/*        submitLoad(key, track, startOffsetMs, rangeBlocks, volumePercent, 0L, decision.token());
        */
        //?}
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
            int volumePercent, long delayMs, int token, PlaybackTiming timing) {
            //?} elif >=1.21 {
/*            int volumePercent, long delayMs, int token, PlaybackTiming timing) {
            */
            //?} else {
/*            int volumePercent, long delayMs, int token) {
            */
            //?}
        pool.submit(() -> {
            if (delayMs > 0L) {
                try {
                    Thread.sleep(delayMs);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!sessions.isLive(key, token)) {
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
            try {
                // SSRF 遮断: 悪意ある disc データ (内部 IP URL) で他プレイヤーの client を踏み台にさせない
                UrlGuard.enforce(track.url());
                // 理由つきで開く。null 判定 1 つに潰すと、DNS 失敗も年齢制限も bot 判定も同じ文面になる。
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
                //? if >=1.21.2 {
                resolved.onPlaybackFault(broken -> Minecraft.getInstance().execute(
                        () -> lateFailure(key, token, track,
                                PlaybackFailure.ofReason(broken.reason(), broken.detail()))));
                //?} elif >=1.21 {
/*                attachFaultSink(resolved, key, token, track);
                */
                //?} else {
/*                resolved.onPlaybackFault(broken -> Minecraft.getInstance().execute(
                        () -> lateFailure(key, token, track,
                                PlaybackFailure.ofReason(broken.reason(), broken.detail()))));
                */
                //?}
            }
            //? if >=1.21.2 {
            Minecraft.getInstance().execute(() -> onLoaded(key, track, startOffsetMs, rangeBlocks,
                    volumePercent, resolved, reported, token, timing));
            //?} elif >=1.21 {
/*            Minecraft.getInstance().execute(() -> onLoaded(key, track, startOffsetMs, rangeBlocks,
                    volumePercent, resolved, reported, token, timing));
            */
            //?} else {
/*            Minecraft.getInstance().execute(() -> onLoaded(
                    key, track, startOffsetMs, rangeBlocks, volumePercent, resolved, reported, token));
            */
            //?}
        });
    }

    /**
     * 再生スレッドの中で落ちた失敗を受ける (main thread から呼ぶこと)。push (ソース直結) と
     * pull (ストリーム終端) の両方がここへ集まる。出すかどうかの判断は {@link PlaybackSessions} 側。
     */
    private void lateFailure(BlockPos key, int token, CustomTrackData track, PlaybackFailure late) {
        final PlaybackFailure show = sessions.lateFailure(key, token, track, late);
        if (show != null) {
            failures.record(key, show);
            PlaybackFailureReport.report(track, show);
        }
    }

    /** ロード完了 (main thread)。成功なら再生を開始し、失敗ならラジオは再接続扱い・通常は通知して終わる。 */
    private void onLoaded(BlockPos key, CustomTrackData track, long startOffsetMs, int rangeBlocks,
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
        // 経路をまたいだ合計で見る (KURONAMI333 裁定「金ジュークと枠を共有する」)。
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
        final FanoutAudioSource fanout = new FanoutAudioSource(resolved, ClientPlaybackManager::playbackClockMs);
        final DiscSoundInstance instance = new DiscSoundInstance(anchor,
                fanout.openMasterBranch(), initialRange, initialVolume, endCb);
        // ストリーム終端の pull 経路も同じ届け先へ繋ぐ。push を持つソースでは重複しうるが、
        // lateFailure の重複抑止が畳むので害はない。逆に差さない方が危険 — 差さないと
        // LavaPlayerAudioStream が「届け先を持たない経路」の既定へ落ち、曲名も重複抑止も
        // ラジオの沈黙も通らない裸の報告を出す。届くのは streaming スレッドなので main thread へ移す。
        instance.setFailureSink(
                late -> Minecraft.getInstance().execute(() -> lateFailure(key, token, track, late)));
        //? if >=1.21.2 {
        instance.setTiming(timing);
        // 実音が鳴り始めた (最初の実 PCM) 瞬間に、進捗表示のアンカーを実音の開始へ打ち直す。
        // 1 再生につき 1 回は noteFirstAudio の世代ガードが保証する。打ち直しは main thread で行う。
        instance.setFirstAudioSink(() -> {
            fanout.markPlaybackStarted(playbackClockMs());
            Minecraft.getInstance().execute(() -> {
            if (sessions.noteFirstAudio(key, token)) {
                reanchorClientElapsed(key, startOffsetMs);
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
             fanout.markPlaybackStarted(playbackClockMs());
             Minecraft.getInstance().execute(() -> onFirstAudio(key, token, desc, startOffsetMs));
         });
        */
        //?} else {
/*
        // 実音が鳴り始めた (最初の実 PCM) 瞬間に、進捗表示のアンカーを実音の開始へ打ち直す。
        // 1 再生につき 1 回は noteFirstAudio の世代ガードが保証する。打ち直しは main thread で行う。
         instance.setFirstAudioSink(() -> {
             fanout.markPlaybackStarted(playbackClockMs());
             Minecraft.getInstance().execute(() -> {
                 if (sessions.noteFirstAudio(key, token)) {
                     reanchorClientElapsed(key, startOffsetMs);
                 }
             });
         });
*/
        //?}
        // install は聴取モデルの適用も行う (鳴り始めの 1 tick を positional で鳴らさない)。
        // 世代が古ければ受け付けず、同じ座標に残っていた音源は必ず止めてから置き換える。
        final PlaybackVoice playback = new SpeakerVoiceGroup(instance, goldenAnchor, fanout, speakerSet);
        if (!sessions.install(key, token, track.url(), startOffsetMs, playback)) {
            playback.stopAndRelease();
            return;
        }
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
        sessions.engineAccepted(key);
        failures.clear(key);
    */
    //?} else {
    //?}
        if (desc != null && !desc.isBlank()) {
            //? if >=26.2 {
            Minecraft.getInstance().gui.hud.setNowPlaying(Component.literal(desc));
            //?} else {
/*            Minecraft.getInstance().gui.setNowPlaying(Component.literal(desc));
            */
            //?}
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
                PlaybackSessions.FIRST_AUDIO_DEADLINE_MS, key.toShortString(), track.url());
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
                PlaybackSessions.FIRST_AUDIO_DEADLINE_MS, key.toShortString(), track.url());
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
                submitLoad(key, req.track(), 0L, req.rangeBlocks(), req.volumePercent(),
                //? if >=1.21.2 {
                RECONNECT_DELAY_MS, token,
                new PlaybackTiming(key.toShortString(), req.track().url(), token, false));
                //?} elif >=1.21 {
/*                        RECONNECT_DELAY_MS, token,
                        new PlaybackTiming(key.toShortString(), req.track().url(), token, false));
                        */
                        //?} else {
/*                        RECONNECT_DELAY_MS, token);
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
        sessions.stop(key);
        speakerAnchors.remove(key);
        failures.clear(key);
    }

    /** 全ての再生を止める (ワールド離脱)。失敗の記憶も持ち越さない。 */
    public void stopAll() {
        ClientPlaybackHandler.SOURCE_OWNERS.clear();
        //? if >=1.21.2 {
        //?} elif >=1.21 {
/*        prefetch.dropAll("stopAll: world-exit");
        recentAlbumPos = null;
        */
        //?} else {
        //?}
        sessions.stopAll();
        speakerAnchors.clear();
        // ブームボックスは別の鍵空間で鳴っているので、ここで一緒に畳まないとワールドを出た後も残る。
        BoomboxClientPlayback.stopAll();
        VanillaSpeakerPlayback.stopAll();
        failures.clearAll();
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
     * MDM 再生非依存の client tick 源から毎 tick 呼ぶ口 (B5)。先読み機構を持つ帯以外では何もしない。
     *
     * <p>追跡対象は「直前に先読み判定が回った座標」1 つだけなので、毎 tick の cost は
     * chunk ロード済みのときの {@code getBlockEntity} 1 回分。BE が金ジュークでなくなったら
     * (撤去・ワールド退出・chunk アンロード) 追跡を手放す。
     */
    public static void mixedAlbumTick() {
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
