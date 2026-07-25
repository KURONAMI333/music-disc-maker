package com.kuronami.musicdiscmaker.client.audio;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.network.PlaybackStartedPayload;
import com.kuronami.musicdiscmaker.network.SpeakerEntry;
import com.kuronami.musicdiscmaker.network.UrlBlockedException;
import com.kuronami.musicdiscmaker.network.UrlGuard;
import com.kuronami.musicdiscmaker.platform.Services;

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
    // pos ごとの聴取アンカー。SpeakerSetPayload はここへ集合を差し替える (再生は止めない)。
    private final Map<BlockPos, MultiSpeakerAnchor> anchors = new ConcurrentHashMap<>();
    // pos ごとの有効スピーカー集合。再生セッションではなく音源の属性なので、シーク/リピートの
    // 停止→再生では捨てない。停止 packet (StopDiscPayload) と切断でだけ忘れる。
    private final Map<BlockPos, List<SpeakerEntry>> speakerSets = new ConcurrentHashMap<>();
    // pos ごとの指向性。スピーカー集合と同じ「聴取モデル」なので同じ寿命 (停止 packet と切断で忘れる)。
    // 未受信 = 従来どおりの positional (バニラ jukebox 経路はこの payload を送らない)。
    private final Map<BlockPos, Boolean> directionals = new ConcurrentHashMap<>();
    // pos ごとの現在再生中 URL。chunk 再入での無駄な再ロードを避ける判定に使う。
    private final Map<BlockPos, String> playingUrl = new ConcurrentHashMap<>();
    // pos ごとの再生要求 (ラジオ再接続で同じ track/range/volume を再利用する)。
    private final Map<BlockPos, PlaybackRequest> requests = new ConcurrentHashMap<>();
    // pos ごとの連続再接続試行回数 (安定再生でリセット)。
    private final Map<BlockPos, Integer> reconnectAttempts = new ConcurrentHashMap<>();
    // pos ごとの現インスタンスの再生開始時刻 (安定判定用)。
    private final Map<BlockPos, Long> playStartMillis = new ConcurrentHashMap<>();
    // pos ごとの現インスタンスのロード開始オフセット (GUI シークと chunk 再入再送の判別に使う)。
    private final Map<BlockPos, Long> loadOffsetMs = new ConcurrentHashMap<>();
    // pos ごとの「実際に音が鳴り始めた」時刻。再ロードの再送で現在位置を答えるのに使う
    // (ストリームを開いた時刻ではない = MC の 4 秒ぶんのバッファ充填を含まない)。
    private final Map<BlockPos, Long> audioStartMillis = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-playback");
        thread.setDaemon(true);
        return thread;
    });

    private ClientPlaybackManager() {
    }

    public void startPlayback(BlockPos pos, CustomTrackData track, long startOffsetMs) {
        startPlayback(pos, track, startOffsetMs, 0, 100, 0L);
    }

    public void startPlayback(BlockPos pos, CustomTrackData track, long startOffsetMs, int rangeBlocks,
            int volumePercent) {
        startPlayback(pos, track, startOffsetMs, rangeBlocks, volumePercent, 0L);
    }

    public void startPlayback(BlockPos pos, CustomTrackData track, long startOffsetMs, int rangeBlocks,
            int volumePercent, long playbackId) {
        if (track == null || track.isEmpty()) {
            return;
        }
        final BlockPos key = pos.immutable();
        // 同じ曲の再生要求が再来した時: それが chunk 再入等の再送 (現在の推定再生位置とほぼ同じ
        // オフセット) なら再ロードしない (音飛び・無駄な再バッファ防止)。GUI シークは現在位置から
        // 大きく離れたオフセットで来る = 本物の頭出しなので dedup せず再ロードして即反映する。
        final DiscSoundInstance existing = active.get(key);
        // 範囲・音量は client 側 BE から tick で live 反映されるため再ロード判定に含めない (再ブロード
        // キャストも飛ばさない)。同じ曲・非シークの再送 (chunk 再入等) はそのまま dedup する。
        if (existing != null && !existing.isStopped() && track.url().equals(playingUrl.get(key))
                && !isSeekRequest(key, startOffsetMs)) {
            // 鳴らし直しはしないが、server は新しい再生セッションを起こしていて校正を待っている。
            // ここで返さないと、chunk 再ロード (= 一度離れて戻る) のたびに校正が失われ、
            // 以後その曲はバッファ遅延ぶんずれたまま戻らない。
            reportAudioPosition(key, playbackId);
            return;
        }
        stopPlayback(pos); // 既存を止め、試行回数・要求もリセット (新しいサーバ駆動再生 or シーク)
        wanted.add(key);
        requests.put(key, new PlaybackRequest(track, rangeBlocks, volumePercent));
        // ビート校正はこの再生セッションだけのもの。ラジオ再接続 (submitLoad の再入) では
        // 報告しない = requests には載せず、この 1 回のロードにだけ持たせる。
        submitLoad(key, track, startOffsetMs, rangeBlocks, volumePercent, 0L, playbackId);
    }

    /**
     * 同じ曲の再生要求が「現在の推定再生位置」から一定以上離れていれば本物のシーク (頭出し)
     * とみなす。chunk 再入の再送は server 側で現在の経過 ms を載せて来るため推定位置とほぼ
     * 一致し、dedup される。後方シークは常に推定位置 (前進中) と乖離するので確実に通る。
     */
    private static final long SEEK_TOLERANCE_MS = 1200L;

    /**
     * 「いまこの曲の何 ms 地点が鳴っているか」を server へ answer する (ビート連動の校正)。
     *
     * <p>再ロードの再送で新しい再生セッションが立った時に使う。基準は<b>実際に音が鳴り始めた時刻</b>
     * ({@code audioStartMillis}) であって、ストリームを開いた時刻ではない — 後者だと MC の
     * 4 秒ぶんのバッファ充填を含んでしまい、潰したい遅延をそのまま報告することになる。
     */
    private void reportAudioPosition(BlockPos key, long playbackId) {
        if (playbackId == 0L) {
            return;
        }
        final Long startedAt = audioStartMillis.get(key);
        final Long loaded = loadOffsetMs.get(key);
        if (startedAt == null || loaded == null) {
            return; // まだ鳴り始めていない = 元の onAudioStarted がこの後に報告する
        }
        Services.NETWORK.sendToServer(new PlaybackStartedPayload(
                key, playbackId, loaded + (System.currentTimeMillis() - startedAt)));
    }

    private boolean isSeekRequest(BlockPos key, long requestedOffsetMs) {
        final Long started = playStartMillis.get(key);
        final Long loaded = loadOffsetMs.get(key);
        if (started == null || loaded == null) {
            return false; // 位置不明 → 従来通り dedup (再ロードしない)
        }
        final long estimatedNowMs = loaded + (System.currentTimeMillis() - started);
        return Math.abs(requestedOffsetMs - estimatedNowMs) >= SEEK_TOLERANCE_MS;
    }

    /**
     * URL を (任意の遅延後) 別 thread でロードし、成功したら main thread で再生を開始する。
     * startPlayback (遅延0) とラジオ再接続 (遅延あり) の共通経路。
     */
    private void submitLoad(BlockPos key, CustomTrackData track, long startOffsetMs, int rangeBlocks,
            int volumePercent, long delayMs, long playbackId) {
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
            Minecraft.getInstance().execute(
                    () -> onLoaded(key, track, startOffsetMs, rangeBlocks, volumePercent, resolved, playbackId));
        });
    }

    /** ロード完了 (main thread)。成功なら再生を開始し、失敗ならラジオは再接続扱い・通常は通知して終わる。 */
    private void onLoaded(BlockPos key, CustomTrackData track, long startOffsetMs, int rangeBlocks,
            int volumePercent, IAudioSource resolved, long playbackId) {
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
                loadOffsetMs.remove(e.getKey());
                audioStartMillis.remove(e.getKey());
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
        // 聴取モデルは常にマルチアンカー。スピーカー集合が空なら StaticAnchor と同じ挙動へ縮退する。
        final MultiSpeakerAnchor anchor = new MultiSpeakerAnchor(key, volumePercent, rangeBlocks);
        anchor.setSpeakers(speakerSets.getOrDefault(key, List.of()));
        anchor.setDirectional(directionals.getOrDefault(key, Boolean.TRUE));
        anchors.put(key, anchor);
        final DiscSoundInstance instance =
                new DiscSoundInstance(anchor, resolved, rangeBlocks, volumePercent, endCb);
        // ビート連動の校正: 音が実際に鳴り始めた瞬間を記録し、1 回だけ server へ報告する。
        // 鳴り始めた時刻は、この後の再送 (chunk 再ロード等) で現在位置を答えるのにも要るので、
        // playbackId の有無に関わらず記録する。streaming thread から呼ばれるので main thread へ渡す。
        instance.setOnAudioStarted(() -> Minecraft.getInstance().execute(() -> {
            audioStartMillis.put(key, System.currentTimeMillis());
            if (playbackId != 0L) {
                Services.NETWORK.sendToServer(new PlaybackStartedPayload(key, playbackId, startOffsetMs));
            }
        }));
        active.put(key, instance);
        playingUrl.put(key, track.url());
        playStartMillis.put(key, System.currentTimeMillis());
        loadOffsetMs.put(key, startOffsetMs); // シーク判定の基準位置
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
        loadOffsetMs.remove(key);
        audioStartMillis.remove(key);

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
        submitLoad(key, req.track(), 0L, req.rangeBlocks(), req.volumePercent(), RECONNECT_DELAY_MS, 0L);
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

    /**
     * 音源(pos) の有効スピーカー集合を差し替える ({@code SpeakerSetPayload} 受信)。再生中なら
     * 聴取アンカーへ即反映し、まだインスタンスが立っていなければ保持しておいて生成時に適用する
     * (packet 順に依存しない)。
     */
    public void updateSpeakers(BlockPos pos, boolean directional, List<SpeakerEntry> speakers) {
        final BlockPos key = pos.immutable();
        if (speakers == null || speakers.isEmpty()) {
            speakerSets.remove(key);
        } else {
            speakerSets.put(key, List.copyOf(speakers));
        }
        directionals.put(key, directional);
        final MultiSpeakerAnchor anchor = anchors.get(key);
        if (anchor != null) {
            anchor.setSpeakers(speakers);
            anchor.setDirectional(directional);
        }
    }

    /** 音源(pos) の聴取モデル (スピーカー集合・指向性) を忘れる (停止 packet 受信時)。 */
    public void forgetSpeakers(BlockPos pos) {
        final BlockPos key = pos.immutable();
        speakerSets.remove(key);
        directionals.remove(key);
    }

    /**
     * ブロック起点で鳴っているインスタンス数。手持ちブームボックス
     * ({@link BoomboxClientPlayback}) が同時再生上限を共有するために読む。
     */
    public int activeCount() {
        return active.size();
    }

    public void stopPlayback(BlockPos pos) {
        final BlockPos key = pos.immutable();
        wanted.remove(key);
        playingUrl.remove(key);
        requests.remove(key);
        reconnectAttempts.remove(key);
        playStartMillis.remove(key);
        loadOffsetMs.remove(key);
        audioStartMillis.remove(key);
        // speakerSets はここで消さない。startPlayback は seek/repeat のたびにこの経路を通るため。
        anchors.remove(key);
        final DiscSoundInstance instance = active.remove(key);
        if (instance != null) {
            instance.requestStop();
            Minecraft.getInstance().getSoundManager().stop(instance);
        }
    }

    public void stopAll() {
        // 手持ちブームボックス (key が entityId で別 map) もここでまとめて掃除する。
        // 再入時にゾンビの音が残らないよう、切断の入口を 1 本にしておく。
        BoomboxClientPlayback.stopAll();
        wanted.clear();
        playingUrl.clear();
        requests.clear();
        reconnectAttempts.clear();
        playStartMillis.clear();
        loadOffsetMs.clear();
        audioStartMillis.clear();
        anchors.clear();
        speakerSets.clear();
        directionals.clear();
        active.values().forEach(instance -> {
            instance.requestStop();
            Minecraft.getInstance().getSoundManager().stop(instance);
        });
        active.clear();
    }
}
