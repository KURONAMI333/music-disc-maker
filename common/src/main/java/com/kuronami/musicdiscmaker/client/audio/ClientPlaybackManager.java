package com.kuronami.musicdiscmaker.client.audio;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
import com.kuronami.musicdiscmaker.network.UrlBlockedException;
import com.kuronami.musicdiscmaker.network.UrlGuard;

import net.minecraft.client.Minecraft;
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

    private static final ClientPlaybackManager INSTANCE = new ClientPlaybackManager();

    /** 再接続を試みる前に置く間隔 (ms)。icecast の瞬断復帰を待つ。 */
    private static final long RECONNECT_DELAY_MS = 3_000L;

    public static ClientPlaybackManager get() {
        return INSTANCE;
    }

    private final PlaybackSessions sessions = new PlaybackSessions(System::currentTimeMillis);

    private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-playback");
        thread.setDaemon(true);
        return thread;
    });

    private ClientPlaybackManager() {
    }

    public void startPlayback(BlockPos pos, CustomTrackData track, long startOffsetMs) {
        startPlayback(pos, track, startOffsetMs, 0, 100, true);
    }

    public void startPlayback(BlockPos pos, CustomTrackData track, long startOffsetMs, int rangeBlocks,
            int volumePercent, boolean directional) {
        if (track == null || track.isEmpty()) {
            return;
        }
        final BlockPos key = pos.immutable();
        final PlaybackSessions.StartDecision decision =
                sessions.start(key, track, startOffsetMs, rangeBlocks, volumePercent, directional);
        if (!decision.load()) {
            return; // 同じ曲の再送 (chunk 再入等)。聴取モデルだけ取り込み済み。
        }
        submitLoad(key, track, startOffsetMs, rangeBlocks, volumePercent, 0L, decision.token());
    }

    /**
     * URL を (任意の遅延後) 別 thread でロードし、成功したら main thread で再生を開始する。
     * startPlayback (遅延0) とラジオ再接続 (遅延あり) の共通経路。
     *
     * <p>{@code token} は最後まで持ち回る。ロード中に停止・差し替えが起きると世代が進むので、
     * 完了時の照合で「もう要らないロード」を捨てられる。
     */
    private void submitLoad(BlockPos key, CustomTrackData track, long startOffsetMs, int rangeBlocks,
            int volumePercent, long delayMs, int token) {
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
            // 失敗の届け先は「ソースを受け取った直後・main thread へ渡す前」に差す。再生スレッドは
            // ここから数百 ms で落ちうるので、play が始まってから差していると取り落とす窓ができる
            // (差した時点で既に壊れていれば relay がその場で流すので、順番はどちらでもよい)。
            if (resolved != null) {
                resolved.onPlaybackFault(broken -> Minecraft.getInstance().execute(
                        () -> lateFailure(key, token, track,
                                PlaybackFailure.ofReason(broken.reason(), broken.detail()))));
            }
            Minecraft.getInstance().execute(() -> onLoaded(
                    key, track, startOffsetMs, rangeBlocks, volumePercent, resolved, reported, token));
        });
    }

    /**
     * 再生スレッドの中で落ちた失敗を受ける (main thread から呼ぶこと)。push (ソース直結) と
     * pull (ストリーム終端) の両方がここへ集まる。出すかどうかの判断は {@link PlaybackSessions} 側。
     */
    private void lateFailure(BlockPos key, int token, CustomTrackData track, PlaybackFailure late) {
        final PlaybackFailure show = sessions.lateFailure(key, token, track, late);
        if (show != null) {
            PlaybackFailureReport.report(track, show);
        }
    }

    /** ロード完了 (main thread)。成功なら再生を開始し、失敗ならラジオは再接続扱い・通常は通知して終わる。 */
    private void onLoaded(BlockPos key, CustomTrackData track, long startOffsetMs, int rangeBlocks,
            int volumePercent, IAudioSource resolved, PlaybackFailure failure, int token) {
        if (resolved == null) {
            if (track.radio() && sessions.isLive(key, token)) {
                onRadioStreamEnded(key, token); // ロード失敗も 1 回の再接続試行として数える
            } else if (sessions.isLive(key, token)) {
                sessions.abandon(key, token);
                // 無音で終わらせない。理由の分類つきでチャットとログの両方に残す
                // (ストリームは client ごとに開くので、片方の client だけ失敗しうる)。
                PlaybackFailureReport.report(track,
                        failure == null ? PlaybackFailure.streamUnavailable() : failure);
            }
            return;
        }
        if (!sessions.isLive(key, token)) {
            resolved.close(); // ロード中に停止/差し替え済み
            return;
        }
        // 自然終了したインスタンスを掃除してから上限を見る (MAX_CONCURRENT を不当に消費させない)。
        final int playing = sessions.sweep();
        final int limit = com.kuronami.musicdiscmaker.Config.maxConcurrent();
        if (playing >= limit) {
            // 上限は client ごとに効くので片側だけ無音になりうる。無言で捨てない。
            PlaybackFailureReport.report(track, PlaybackFailure.concurrentLimit(limit));
            resolved.close();
            sessions.abandon(key, token);
            return;
        }
        // ラジオは終端 (瞬断) で自動再接続を試みる。有限曲は自然終了させる (再起動しない)。
        final Runnable endCb = track.radio()
                ? () -> Minecraft.getInstance().execute(() -> onRadioStreamEnded(key, token))
                : null;
        final DiscSoundInstance instance = new DiscSoundInstance(key, resolved, rangeBlocks, volumePercent, endCb);
        // ストリーム終端の pull 経路も同じ届け先へ繋ぐ。push を持つソースでは重複しうるが、
        // lateFailure の重複抑止が畳むので害はない。逆に<b>差さない方が危険</b> — 差さないと
        // LavaPlayerAudioStream が「届け先を持たない経路」の既定へ落ち、曲名も重複抑止も
        // ラジオの沈黙も通らない裸の報告を出す。届くのは streaming スレッドなので main thread へ移す。
        instance.setFailureSink(
                late -> Minecraft.getInstance().execute(() -> lateFailure(key, token, track, late)));
        // install は聴取モデルの適用も行う (鳴り始めの 1 tick を positional で鳴らさない)。
        // 世代が古ければ受け付けず、同じ座標に残っていた音源は必ず止めてから置き換える。
        if (!sessions.install(key, token, track.url(), startOffsetMs, instance)) {
            instance.requestStop();
            return;
        }
        Minecraft.getInstance().getSoundManager().play(instance);
        // vanilla disc と同じ "Now Playing: ..." overlay を出す
        final String desc = (track.author() != null && !track.author().isBlank())
                ? track.author() + " - " + track.title()
                : track.title();
        if (desc != null && !desc.isBlank()) {
            Minecraft.getInstance().gui.setNowPlaying(Component.literal(desc));
        }
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
                        RECONNECT_DELAY_MS, token);
            }
            case GIVE_UP -> {
                // 再接続を諦めた = 恒久的にこの音源は鳴らない。数秒で消えるアクションバーではなく
                // チャットへ残す (「いつ止まったか」を後から読めるようにする)。
                notifyChat(Component.translatable("music_disc_maker.radio.stopped"));
                // 途中で拾っていた理由があれば、ここで初めて出す (「なぜ諦めたか」)。
                if (next.why() != null) {
                    PlaybackFailureReport.report(next.request().track(), next.why());
                }
            }
        }
    }

    /** チャットに一行残す (main thread から呼ぶこと)。数秒で消えては困る恒久的な結果に使う。 */
    private static void notifyChat(Component message) {
        final var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(message, false);
        }
    }

    /** アクションバーに一行表示する (main thread から呼ぶこと)。 */
    private static void notifyActionBar(Component message) {
        final var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(message, true);
        }
    }

    public void stopPlayback(BlockPos pos) {
        sessions.stop(pos.immutable());
    }

    public void stopAll() {
        sessions.stopAll();
    }
}
