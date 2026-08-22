package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.minecraft.core.BlockPos;

/**
 * jukebox 位置ごとの再生セッション管理 (MC 非依存)。{@link ClientPlaybackManager} から
 * 「どうするか」の判断と状態を全部こちらへ出し、あちらには MC を触る操作だけを残してある。
 *
 * <h2>これが要る理由 — 「まだ鳴らしていいか」を座標で答えていた</h2>
 * 以前は「この座標の再生はまだ望まれているか」を {@code wanted} という座標の集合で答えていた。
 * 座標には<b>何回目の再生か</b>が入っていないので、同じ座標でディスクを A → B に差し替えると:
 *
 * <ol>
 *   <li>A のロード中に停止 → 座標が集合から消える</li>
 *   <li>B の再生要求で同じ座標が<b>すぐ入り直す</b></li>
 *   <li>A のロードが完了 → 座標を見に行くと「ある」→ A も鳴り始める</li>
 * </ol>
 *
 * 完了順によっては A と B が同時に鳴り、{@code active} は後から書いた方しか覚えていないので
 * <b>片方は二度と止められない</b>。ディスクを差し替えるだけで起きる、実機で必ず踏む形だった。
 *
 * <p>だから鍵は座標ではなく<b>座標ごとの世代番号 (token)</b> にした ({@link PlaybackGenerations})。
 * 再生要求のたびに世代を進め、ロードは開始時点の世代を持ち回り、完了時に一致しなければ捨てる。
 * <b>座標だけで答える口はこのクラスに 1 つも残っていない</b> — 残すと 2 つの正本が食い違う。
 *
 * <p>時計は差し替え可能 ({@link LongSupplier})。ラジオの「安定していたか」判定を実時間の待ちなしで
 * テストするため。
 */
public final class PlaybackSessions {

    /** ラジオ瞬断時の自動再接続の上限回数。 */
    public static final int MAX_RECONNECT = 3;
    /** これ以上再生できていたら「安定していた」とみなし、次の瞬断で試行回数をリセットする (ms)。 */
    public static final long STABLE_MS = 15_000L;

    /**
     * 登録から<b>最初の実 PCM</b> が来るまでに待つ上限 (ms)。超えたら鳴らないものとして畳む
     * ({@link #firstAudioOverdue})。
     *
     * <h2>なぜ 30 秒なのか (帯とその算術)</h2>
     * 下限は<b>健全なソースでも実 PCM が出ない時間</b>の総和で決まる:
     *
     * <ul>
     *   <li>{@code DiscSoundInstance.PREBUFFER_BUDGET_MS} = 10 秒。この間 future は完了せず、
     *       MC は {@code read} を一度も呼ばない ({@code LavaAudioSource.STARVE_DEADLINE_MS} =
     *       10 秒に合わせてある)</li>
     *   <li>{@code RetryingAudioSource} のやり直しが飛行中の 11.5 秒
     *       ({@code DEFAULT_GRACE_MS} = 1.5 秒 + {@code MusicLoaderImpl.REOPEN_TIMEOUT_MS} =
     *       10 秒)。{@code REOPEN_RETRIES} = 1 なので<b>1 回だけ</b>積まれる</li>
     * </ul>
     *
     * 積み上げた最悪の健全経路が 21.5 秒。上限は lavaplayer の
     * {@code AudioPlayerLifecycleManager} が {@code provide} されない player を殺す 60 秒で、
     * ここを超えると理由を出す前に player が消える。
     *
     * <p>21.5 秒に約 1.4 倍の余裕を取って 30 秒に置いた。<b>短く取ることだけが現状より悪化する
     * 経路</b>なので (健全な再生を失敗側へ倒す)、迷ったら長い方へ寄せてある。
     *
     * <p><b>実測はまだ無い。</b> 実際の {@code firstpcm=} は {@link PlaybackTiming} が切り替わり
     * 1 回につき 1 行出すので、実機のログが溜まったら見直す。
     */
    public static final long FIRST_AUDIO_DEADLINE_MS = 30_000L;

    /**
     * 同じ曲の再生要求が「現在の推定再生位置」からこれ以上離れていれば本物のシーク (頭出し) とみなす。
     * chunk 再入の再送は server 側が現在の経過 ms を載せて来るので推定位置とほぼ一致し、dedup される。
     */
    private static final long SEEK_TOLERANCE_MS = 1200L;

    /**
     * ラジオ再接続で同じ条件を再利用するための再生要求。
     *
     * @param track         曲
     * @param rangeBlocks   可聴範囲 (ブロック)
     * @param volumePercent 音量 (%)
     */
    public record Request(CustomTrackData track, int rangeBlocks, int volumePercent) {
    }

    /**
     * 再生要求への答え。
     *
     * @param load  ロードして鳴らすなら {@code true}。{@code false} = 同じ曲の再送なので値だけ反映した
     * @param token この再生の世代。ロード側が持ち回り、完了時に照合する ({@code load} が偽なら未使用)
     */
    public record StartDecision(boolean load, int token) {
    }

    /** {@link #radioStreamEnded} の答えの種類。 */
    public enum ReconnectKind {
        /** 停止済み / ラジオでない → 何もしない。 */
        NONE,
        /** 間隔を置いて再接続する。 */
        RETRY,
        /** 上限に達した → 諦めて通知する。 */
        GIVE_UP
    }

    /**
     * ラジオ終端への答え。
     *
     * @param kind    次に何をするか
     * @param attempt 何回目の再接続か ({@link ReconnectKind#RETRY} の時だけ意味を持つ)
     * @param request 再接続に使う再生要求 ({@link ReconnectKind#NONE} なら {@code null})
     * @param why     諦めた理由 ({@link ReconnectKind#GIVE_UP} で拾えていれば。無ければ {@code null})
     */
    public record Reconnect(ReconnectKind kind, int attempt, @Nullable Request request,
            @Nullable PlaybackFailure why) {
    }

    private final PlaybackGenerations<BlockPos> generations = new PlaybackGenerations<>();
    private final Map<BlockPos, PlaybackVoice> active = new ConcurrentHashMap<>();
    private final Map<BlockPos, String> playingUrl = new ConcurrentHashMap<>();
    private final Map<BlockPos, Request> requests = new ConcurrentHashMap<>();
    private final Map<BlockPos, Boolean> directionals = new ConcurrentHashMap<>();
    private final Map<BlockPos, Integer> reconnectAttempts = new ConcurrentHashMap<>();
    /**
     * 登録できた時刻 (壁時計)。<b>推定再生位置の起点</b>で、{@link #isSeekRequest} だけが読む。
     *
     * <p>server が送ってくる {@code requestedOffsetMs} は壁時計基準の経過 ms なので、
     * こちらの起点を実 PCM へ寄せると<b>無音の総量ぶん推定が後退</b>し、
     * {@link #SEEK_TOLERANCE_MS} を超えた瞬間に chunk 再入の再送を本物のシークと
     * 誤判定して鳴らし直す。<b>意味を変えないこと。</b>
     */
    private final Map<BlockPos, Long> registeredMillis = new ConcurrentHashMap<>();
    /**
     * <b>最初の実 PCM</b> が音声エンジンへ渡った時刻。まだなら鍵ごと無い。
     *
     * <p>{@code SoundManager#play} はインスタンスを登録するだけで音声ストリームの future は
     * 未完了のまま返るので、{@link #registeredMillis} は「鳴っていた時間」の起点としては早すぎる。
     * ラジオの安定判定 ({@link #STABLE_MS}) と鳴らない再生の期限 ({@link #FIRST_AUDIO_DEADLINE_MS})
     * はこちらを読む。判別点は {@link LavaPlayerAudioStream#emit} の 1 箇所だけ。
     */
    private final Map<BlockPos, Long> firstAudioMillis = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> loadOffsetMs = new ConcurrentHashMap<>();
    /**
     * ラジオで拾った失敗の保留。ラジオにとって音が途切れることは再接続が引き受ける想定内の状態
     * なので、瞬断のたびにチャットへ理由を出すと再接続の表示 (アクションバー) と二重になる。
     * 再接続を諦めた時に「なぜ諦めたか」として 1 度だけ出す。
     */
    private final Map<BlockPos, PlaybackFailure> pendingFailure = new ConcurrentHashMap<>();
    /**
     * 再生スレッドの中で落ちた失敗の重複抑止。ラジオは同じ理由で最大 {@link #MAX_RECONNECT} 回まで
     * 落ち直すので、覚えておかないと同じ行がチャットに積まれる。忘れるのは停止した時だけ。
     */
    private final PlaybackFailureNotices notices = new PlaybackFailureNotices();
    /**
     * sound engine が受理しなかった失敗の重複抑止。{@link #notices} と<b>別に持つ</b>。
     *
     * <p>あちらは停止のたびに忘れる ({@link #stop})。再生要求は必ず {@code stop} を通ってから
     * 新しい世代を起こすので、あちらに乗せると<b>何も抑えられない</b> — 音量 0 のように
     * 「鳴らそうとするたびに同じ理由で弾かれる」失敗は、chunk 再入のたびに同じ行を積む。
     *
     * <p>だからこちらが忘れるのは<b>実際に鳴り始めた時</b>だけにする ({@link #engineAccepted})。
     * 意図してミュートしている人は最初の 1 回だけ見て以降は黙り、誤って 0 にしている人は
     * 気づける。理由が変われば (音量 0 → チャンネル枯渇) 別の情報なので通る。
     */
    private final PlaybackFailureNotices startNotices = new PlaybackFailureNotices();

    private final LongSupplier clockMs;

    /**
     * @param clockMs 現在時刻 (ms)。テストは実時間を待たずに進めるために差し替える
     */
    public PlaybackSessions(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

    /**
     * 再生要求を捌く。
     *
     * <p>同じ曲・非シークの再送 (chunk 再入等) は鳴らし直さず、聴取モデルだけその場で取り込む。
     * それ以外は<b>まず今の再生を確実に畳んでから</b>新しい世代を起こす。
     *
     * @param key           jukebox の位置
     * @param track         曲
     * @param startOffsetMs 再生開始位置 (ms)
     * @param rangeBlocks   可聴範囲 (ブロック)
     * @param volumePercent 音量 (%)
     * @param directional   聴取モデル
     * @return ロードすべきか、その世代
     */
    public StartDecision start(BlockPos key, CustomTrackData track, long startOffsetMs, int rangeBlocks,
            int volumePercent, boolean directional) {
        final PlaybackVoice existing = active.get(key);
        // 範囲・音量は client 側 BE から tick で live 反映されるので再ロード判定に含めない。
        if (existing != null && !existing.isStopped() && track.url().equals(playingUrl.get(key))
                && !isSeekRequest(key, startOffsetMs)) {
            existing.setDirectional(directional);
            directionals.put(key, directional);
            return new StartDecision(false, 0);
        }
        stop(key); // 既存を止め、試行回数・要求もリセット (新しいサーバ駆動再生 or シーク)
        final int token = generations.begin(key); // stop が世代を進めた後に、この再生の世代を起こす
        directionals.put(key, directional);
        requests.put(key, new Request(track, rangeBlocks, volumePercent));
        return new StartDecision(true, token);
    }

    /**
     * その世代の再生がまだ望まれているか。<b>座標だけで答えないための唯一の口。</b>
     *
     * @param key   jukebox の位置
     * @param token {@link #start} が返した世代
     * @return まだ現役なら {@code true}
     */
    public boolean isLive(BlockPos key, int token) {
        return generations.isCurrent(key, token);
    }

    /**
     * 自然終了した音源を掃除する (同時再生上限を不当に消費させない)。
     *
     * @return 掃除後に鳴っている音源の数
     */
    public int sweep() {
        active.entrySet().removeIf(entry -> {
            if (entry.getValue().isStopped()) {
                playingUrl.remove(entry.getKey());
                loadOffsetMs.remove(entry.getKey());
                return true;
            }
            return false;
        });
        return active.size();
    }

    /**
     * 鳴り始めた音源を登録する。
     *
     * <p>世代が古ければ受け付けない ({@code false} — 呼び出し側はその音源を捨てること)。
     * 受け付けた場合でも、同じ座標に音源が残っていたら<b>必ず止めてから</b>置き換える。
     * ここに来るのは上の世代判定をすり抜けた同時完了なので、放置すると 2 音源が重なり、
     * 覚えていない方は二度と止められなくなる。
     *
     * @param key           jukebox の位置
     * @param token         この再生の世代
     * @param url           鳴らす曲の URL
     * @param startOffsetMs ロード開始オフセット (シーク判定の基準)
     * @param voice         鳴り始めた音源
     * @return 受け付けたなら {@code true}
     */
    public boolean install(BlockPos key, int token, String url, long startOffsetMs, PlaybackVoice voice) {
        if (!isLive(key, token)) {
            return false;
        }
        voice.setDirectional(directionals.getOrDefault(key, Boolean.TRUE));
        final PlaybackVoice previous = active.put(key, voice);
        if (previous != null && previous != voice && !previous.isStopped()) {
            previous.stopAndRelease();
        }
        playingUrl.put(key, url);
        registeredMillis.put(key, clockMs.getAsLong());
        // 前の世代 (ラジオの再接続は同じ世代を持ち回る) の「鳴り始めた時刻」を持ち越さない。
        firstAudioMillis.remove(key);
        loadOffsetMs.put(key, startOffsetMs);
        return true;
    }

    /**
     * この再生を諦める (同時再生上限で捨てた等)。世代を進めるので、後から届く報告も黙る。
     *
     * @param key   jukebox の位置
     * @param token この再生の世代
     */
    public void abandon(BlockPos key, int token) {
        if (isLive(key, token)) {
            generations.invalidate(key);
            requests.remove(key);
        }
    }

    /**
     * 再生スレッドの中で落ちた失敗を、利用者に出すべきかどうか判定する。
     *
     * <p>止めた再生の後始末では黙る (ロード中に停止された・上限で捨てられたソースも後から理由を
     * 上げてくるので、弾かないと「鳴らしていない再生の失敗」がチャットに出る)。ラジオはまだ
     * 再接続する気がある間は抱えておく (瞬断は想定内)。
     *
     * @param key   jukebox の位置
     * @param token この再生の世代
     * @param track 失敗した曲
     * @param late  分類済みの失敗
     * @return 出すべき失敗。黙るなら {@code null}
     */
    @Nullable
    public PlaybackFailure lateFailure(BlockPos key, int token, CustomTrackData track, PlaybackFailure late) {
        if (!isLive(key, token)) {
            return null;
        }
        if (track.radio()) {
            pendingFailure.put(key, late);
            return null;
        }
        return notices.shouldReport(key, late) ? late : null;
    }

    /**
     * sound engine が再生を受理しなかった ({@link SoundEngineAcceptance})。この再生を諦めた上で、
     * 利用者に出すべきかどうかを答える。
     *
     * <p>ロードは成功しているので原因は MC 側にあり、直るまで<b>何度やっても同じ理由で弾かれる</b>。
     * 出すこと自体は正しい (誤って音量を 0 にしている人には必要な情報) が、毎回出すとノイズになる。
     * だから頻度の問題として扱う — 同じ理由は実際に鳴り始めるまで 1 回だけ。
     *
     * @param key     jukebox の位置
     * @param token   この再生の世代
     * @param failure 受理されなかった理由
     * @return 出すべき失敗。黙るなら {@code null}
     */
    @Nullable
    public PlaybackFailure engineRejected(BlockPos key, int token, PlaybackFailure failure) {
        abandon(key, token);
        return startNotices.shouldReport(key, failure) ? failure : null;
    }

    /**
     * sound engine が再生を受理した = 実際に鳴り始めた。受理されなかった理由の記憶を捨てる。
     *
     * <p>ここが唯一の忘れる点。{@link #stop} で忘れないのは、停止と再生を挟んで繰り返される
     * 失敗こそが抑えたい相手だから ({@link #startNotices})。
     *
     * @param key jukebox の位置
     */
    public void engineAccepted(BlockPos key) {
        startNotices.forget(key);
    }

    /**
     * <b>最初の実 PCM が音声エンジンへ渡った</b> = 本当に鳴り始めた。
     *
     * <p>{@code SoundManager#play} が受理したことと音が出始めたことは別の事実で、前者は
     * {@link #engineAccepted}、後者がここ。判別点は {@link LavaPlayerAudioStream#emit} の 1 箇所
     * だけで、埋めた無音は数えない。
     *
     * <p>1 世代につき 1 回しか通さない。世代が進んだ後に届いた通知を通すと、差し替えられた
     * 前の曲が新しい曲の "Now Playing" を上書きする ({@link PlaybackGenerations} が防いでいる
     * 事故の表示版)。
     *
     * @param key   jukebox の位置
     * @param token この再生の世代
     * @return この世代で初めての実 PCM なら {@code true} (呼び出し側は "Now Playing" を出してよい)
     */
    public boolean noteFirstAudio(BlockPos key, int token) {
        if (!isLive(key, token)) {
            return false;
        }
        return firstAudioMillis.putIfAbsent(key, clockMs.getAsLong()) == null;
    }

    /**
     * 登録から {@link #FIRST_AUDIO_DEADLINE_MS} 経っても実 PCM が一度も渡っていないか。
     *
     * <p>これが無いと、ストリームの future が完了しないまま黙って座り続ける再生を
     * <b>永久に待つ</b>ことになる (画面には何も出ず、音も出ない)。
     *
     * @param key   jukebox の位置
     * @param token この再生の世代
     * @return 期限切れなら {@code true}。停止済み・世代違い・既に鳴っているなら {@code false}
     */
    public boolean firstAudioOverdue(BlockPos key, int token) {
        if (!isLive(key, token) || firstAudioMillis.containsKey(key)) {
            return false;
        }
        final Long registered = registeredMillis.get(key);
        return registered != null && clockMs.getAsLong() - registered >= FIRST_AUDIO_DEADLINE_MS;
    }

    /**
     * ラジオストリームが終端に達した。再接続するか、諦めるかを決める。
     *
     * <p>直前の再生が {@link #STABLE_MS} 以上続いていたら (瞬断が久しぶりなら) 試行回数をリセットする。
     *
     * @param key   jukebox の位置
     * @param token この再生の世代
     * @return 次に何をするか
     */
    public Reconnect radioStreamEnded(BlockPos key, int token) {
        if (!isLive(key, token)) {
            return new Reconnect(ReconnectKind.NONE, 0, null, null); // 撤去/停止済み
        }
        final Request request = requests.get(key);
        if (request == null || !request.track().radio()) {
            return new Reconnect(ReconnectKind.NONE, 0, null, null);
        }
        final long started = registeredMillis.getOrDefault(key, 0L);
        if (started > 0L && clockMs.getAsLong() - started >= STABLE_MS) {
            reconnectAttempts.remove(key);
        }
        final PlaybackVoice ended = active.remove(key);
        if (ended != null) {
            ended.stopAndRelease();
        }
        playingUrl.remove(key);
        registeredMillis.remove(key);
        firstAudioMillis.remove(key);
        loadOffsetMs.remove(key);

        final int attempt = reconnectAttempts.getOrDefault(key, 0) + 1;
        if (attempt > MAX_RECONNECT) {
            final PlaybackFailure why = pendingFailure.remove(key);
            stop(key); // 恒久失敗 → 世代を進めて後続の報告も黙らせる
            return new Reconnect(ReconnectKind.GIVE_UP, attempt, request, why);
        }
        reconnectAttempts.put(key, attempt);
        return new Reconnect(ReconnectKind.RETRY, attempt, request, null);
    }

    /**
     * この座標の再生を止める。世代を進めるので、ロード中の要求も完了時点で捨てられる。
     *
     * @param key jukebox の位置
     */
    public void stop(BlockPos key) {
        generations.invalidate(key);
        playingUrl.remove(key);
        requests.remove(key);
        directionals.remove(key);
        reconnectAttempts.remove(key);
        registeredMillis.remove(key);
        firstAudioMillis.remove(key);
        loadOffsetMs.remove(key);
        pendingFailure.remove(key);
        notices.forget(key);
        final PlaybackVoice voice = active.remove(key);
        if (voice != null) {
            voice.stopAndRelease();
        }
    }

    /** 全ての再生を止める (ワールド退出等)。 */
    public void stopAll() {
        // 鍵の集合は generations が持っている。「鳴っているものの一覧」から数え上げると、
        // まだ一度も鳴っていないロードを取り落とす (退出後に完了して畳んだ client を触りに行く)。
        generations.invalidateAll();
        playingUrl.clear();
        requests.clear();
        directionals.clear();
        reconnectAttempts.clear();
        registeredMillis.clear();
        firstAudioMillis.clear();
        loadOffsetMs.clear();
        pendingFailure.clear();
        notices.forgetAll();
        startNotices.forgetAll();
        active.values().forEach(PlaybackVoice::stopAndRelease);
        active.clear();
    }

    /**
     * chunk 再入の再送か、本物のシーク (頭出し) か。後方シークは常に推定位置 (前進中) と乖離するので
     * 確実に通る。
     */
    private boolean isSeekRequest(BlockPos key, long requestedOffsetMs) {
        final Long started = registeredMillis.get(key);
        final Long loaded = loadOffsetMs.get(key);
        if (started == null || loaded == null) {
            return false; // 位置不明 → 従来通り dedup (再ロードしない)
        }
        final long estimatedNowMs = loaded + (clockMs.getAsLong() - started);
        return Math.abs(requestedOffsetMs - estimatedNowMs) >= SEEK_TOLERANCE_MS;
    }
}
