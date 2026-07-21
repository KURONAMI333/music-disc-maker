package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.network.UrlBlockedException;
import com.kuronami.musicdiscmaker.network.UrlGuard;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * client 側の再生統括。jukebox 位置ごとに 1 再生を管理する。
 * URL ロードはブロックするので別 thread、SoundManager 操作は main thread。
 *
 * <p>ラジオ (無限長ストリーム) は瞬断で lavaplayer の track が終了する。終端 (read=-1) を
 * {@link DiscSoundInstance} 経由で検知し、間隔を置いて最大 {@link #MAX_RECONNECT} 回まで
 * 自動再接続する。安定再生 ({@link #STABLE_MS} 以上) できたら試行回数はリセットする。
 */
public final class ClientPlaybackManager {

    private static final ClientPlaybackManager INSTANCE = new ClientPlaybackManager();

    /** ラジオ瞬断時の自動再接続の上限回数。 */
    private static final int MAX_RECONNECT = 3;
    /** 再接続を試みる前に置く間隔 (ms)。icecast の瞬断復帰を待つ。 */
    private static final long RECONNECT_DELAY_MS = 3_000L;
    /** これ以上再生できていたら「安定していた」とみなし、次の瞬断で試行回数をリセットする (ms)。 */
    private static final long STABLE_MS = 15_000L;

    public static ClientPlaybackManager get() {
        return INSTANCE;
    }

    private record PlaybackRequest(CustomTrackData track, int rangeBlocks, int volumePercent) {
    }

    private final Map<BlockPos, DiscSoundInstance> active = new ConcurrentHashMap<>();
    private final Set<BlockPos> wanted = ConcurrentHashMap.newKeySet();
    // pos ごとの現在再生中 URL。chunk 再入での無駄な再ロードを避ける判定に使う。
    private final Map<BlockPos, String> playingUrl = new ConcurrentHashMap<>();
    // pos ごとの再生要求 (ラジオ再接続で同じ track/range/volume を再利用する)。
    private final Map<BlockPos, PlaybackRequest> requests = new ConcurrentHashMap<>();
    // pos ごとの連続再接続試行回数 (安定再生でリセット)。
    private final Map<BlockPos, Integer> reconnectAttempts = new ConcurrentHashMap<>();
    // pos ごとの現インスタンスの再生開始時刻 (安定判定用)。
    private final Map<BlockPos, Long> playStartMillis = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-playback");
        thread.setDaemon(true);
        return thread;
    });

    private ClientPlaybackManager() {
    }

    public void startPlayback(BlockPos pos, CustomTrackData track, long startOffsetMs) {
        startPlayback(pos, track, startOffsetMs, 0, 100);
    }

    public void startPlayback(BlockPos pos, CustomTrackData track, long startOffsetMs, int rangeBlocks, int volumePercent) {
        if (track == null || track.isEmpty()) {
            return;
        }
        final BlockPos key = pos.immutable();
        // chunk 再入などで同じ曲の再生要求が再来した時は再ロードしない (音飛び・無駄な再バッファ防止)。
        final DiscSoundInstance existing = active.get(key);
        if (existing != null && !existing.isStopped() && track.url().equals(playingUrl.get(key))) {
            return;
        }
        stopPlayback(pos); // 既存を止め、試行回数・要求もリセット (新しいサーバ駆動再生)
        wanted.add(key);
        requests.put(key, new PlaybackRequest(track, rangeBlocks, volumePercent));
        submitLoad(key, track, startOffsetMs, rangeBlocks, volumePercent, 0L);
    }

    /**
     * URL を (任意の遅延後) 別 thread でロードし、成功したら main thread で再生を開始する。
     * startPlayback (遅延0) とラジオ再接続 (遅延あり) の共通経路。
     */
    private void submitLoad(BlockPos key, CustomTrackData track, long startOffsetMs, int rangeBlocks,
            int volumePercent, long delayMs) {
        pool.submit(() -> {
            if (delayMs > 0L) {
                try {
                    Thread.sleep(delayMs);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!wanted.contains(key)) {
                return; // 遅延中に停止/撤去された
            }
            IAudioSource source;
            try {
                // SSRF 遮断: 悪意ある disc データ (内部 IP URL) で他プレイヤーの client を踏み台にさせない
                UrlGuard.enforce(track.url());
                source = LoaderHolder.get().openStream(track.url(), startOffsetMs);
            } catch (final UrlBlockedException blocked) {
                MusicDiscMaker.LOGGER.warn("再生 URL を拒否 ({}): {}", blocked.reason(), track.url());
                source = null;
            } catch (final Throwable t) {
                MusicDiscMaker.LOGGER.warn("再生用ストリーム生成に失敗 ({}): {}", track.url(), t.toString());
                source = null;
            }
            final IAudioSource resolved = source;
            Minecraft.getInstance().execute(() -> onLoaded(key, track, rangeBlocks, volumePercent, resolved));
        });
    }

    /** ロード完了 (main thread)。成功なら再生を開始し、失敗ならラジオは再接続扱い・通常は通知して終わる。 */
    private void onLoaded(BlockPos key, CustomTrackData track, int rangeBlocks, int volumePercent,
            IAudioSource resolved) {
        if (resolved == null) {
            if (track.radio() && wanted.contains(key)) {
                onRadioStreamEnded(key); // ロード失敗も 1 回の再接続試行として数える
            } else {
                wanted.remove(key);
                requests.remove(key);
                notifyPlaybackFailed(); // 無音で終わらせず、再生できなかったことをプレイヤーに伝える
            }
            return;
        }
        if (!wanted.contains(key)) {
            resolved.close(); // ロード中に停止要求済み
            return;
        }
        // 自然終了したインスタンスを掃除 (MAX_CONCURRENT を不当に消費させない)
        active.entrySet().removeIf(e -> {
            if (e.getValue().isStopped()) {
                playingUrl.remove(e.getKey());
                return true;
            }
            return false;
        });
        if (active.size() >= com.kuronami.musicdiscmaker.Config.maxConcurrent()) {
            MusicDiscMaker.LOGGER.info("同時再生上限に達したため再生をスキップ: {}", key);
            resolved.close();
            wanted.remove(key);
            requests.remove(key);
            return;
        }
        // ラジオは終端 (瞬断) で自動再接続を試みる。有限曲は自然終了させる (再起動しない)。
        final Runnable endCb = track.radio()
                ? () -> Minecraft.getInstance().execute(() -> onRadioStreamEnded(key))
                : null;
        final DiscSoundInstance instance = new DiscSoundInstance(key, resolved, rangeBlocks, volumePercent, endCb);
        active.put(key, instance);
        playingUrl.put(key, track.url());
        playStartMillis.put(key, System.currentTimeMillis());
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
     * ラジオストリームが終端 (read=-1) に達した時に main thread で呼ばれる。まだ再生継続が望まれて
     * いれば、間隔を置いて再接続を試みる。安定再生後の瞬断なら試行回数をリセットし、上限超過なら停止する。
     */
    private void onRadioStreamEnded(BlockPos key) {
        if (!wanted.contains(key)) {
            return; // ディスク撤去/停止済み → 再接続しない
        }
        final PlaybackRequest req = requests.get(key);
        if (req == null || !req.track().radio()) {
            return;
        }
        // 直前の再生が安定していたら (瞬断が久しぶりなら) 試行回数をリセットする。
        final long started = playStartMillis.getOrDefault(key, 0L);
        if (started > 0L && System.currentTimeMillis() - started >= STABLE_MS) {
            reconnectAttempts.remove(key);
        }
        // 終了したインスタンスを片付ける (active から外す)。
        final DiscSoundInstance ended = active.remove(key);
        if (ended != null) {
            ended.requestStop();
            Minecraft.getInstance().getSoundManager().stop(ended);
        }
        playingUrl.remove(key);
        playStartMillis.remove(key);

        final int attempt = reconnectAttempts.getOrDefault(key, 0) + 1;
        if (attempt > MAX_RECONNECT) {
            // 恒久失敗 → クリーン停止して通知。
            reconnectAttempts.remove(key);
            wanted.remove(key);
            requests.remove(key);
            notifyActionBar(Component.translatable("music_disc_maker.radio.stopped"));
            return;
        }
        reconnectAttempts.put(key, attempt);
        notifyActionBar(Component.translatable("music_disc_maker.radio.reconnecting", attempt, MAX_RECONNECT));
        submitLoad(key, req.track(), 0L, req.rangeBlocks(), req.volumePercent(), RECONNECT_DELAY_MS);
    }

    /** 再生失敗をアクションバーに表示する (main thread から呼ぶこと)。 */
    private static void notifyPlaybackFailed() {
        notifyActionBar(Component.translatable("music_disc_maker.playback_failed"));
    }

    /** アクションバーに一行表示する (main thread から呼ぶこと)。 */
    private static void notifyActionBar(Component message) {
        final var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(message, true);
        }
    }

    public void stopPlayback(BlockPos pos) {
        final BlockPos key = pos.immutable();
        wanted.remove(key);
        playingUrl.remove(key);
        requests.remove(key);
        reconnectAttempts.remove(key);
        playStartMillis.remove(key);
        final DiscSoundInstance instance = active.remove(key);
        if (instance != null) {
            instance.requestStop();
            Minecraft.getInstance().getSoundManager().stop(instance);
        }
    }

    public void stopAll() {
        wanted.clear();
        playingUrl.clear();
        requests.clear();
        reconnectAttempts.clear();
        playStartMillis.clear();
        active.values().forEach(instance -> {
            instance.requestStop();
            Minecraft.getInstance().getSoundManager().stop(instance);
        });
        active.clear();
    }
}
