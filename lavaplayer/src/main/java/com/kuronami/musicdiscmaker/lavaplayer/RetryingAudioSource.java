package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFaultRelay;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;

/**
 * 再生終了を即時に返し、後から push される失敗理由を relay するソース。
 *
 * <h2>同期側で拾えない失敗が残る理由</h2>
 * client を 1 本だけにすると大半の失敗は解決の時点 ({@code loadTrackSync}) に出る
 * ので、そちらは {@link SessionRetry} が普通のループで包める。それでも「解決には成功し、
 * 再生スレッドの中で落ちる」経路は残る — この経路には戻り値が無く、呼び出し側はとっくに返っている。
 *
 * <p>そこで<b>やり直しの起点を {@link #read} に置く</b>。MC が PCM を引きに来る唯一の口がここで、
 * かつ「もう鳴らない」が確定するのもここ ({@code -1} を返した瞬間) だから、返す前に開き直せば
 * 上の層は何も知らずに済む。
 *
 * <h2>ストリームの終わりは失敗より先に来る</h2>
 * lavaplayer は再生が例外で落ちたとき「ストリームは終わった」を先に公開し、例外イベントを後から
 * 配る (根拠は {@code PlaybackFaultRelay} の javadoc に実バイトコードの順で書いてある)。つまり
 * {@code -1} を見た時点では理由がまだ存在しない。だから終端を見たら<b>少しだけ理由の到着を待つ</b>
 * ({@link #graceMs})。待っても来なければ、それは正常に鳴り終わっただけ。
 *
 * <p><b>この待ちは 1 本のストリームにつき 1 回だけ</b> ({@link #terminated})。MC は終端を見た後も
 * チャンネルを手放すまで引き続けるので、毎回払うと曲の終わりごとに Sound engine スレッドが
 * 数秒止まる。
 *
 * <h2>やり直さない場合</h2>
 * <ul>
 *   <li>既に PCM を 1 バイトでも渡した後 — 途中まで聴こえていた曲を頭から鳴らし直す方が害が大きい</li>
 *   <li>上限 ({@code maxRetries}) を使い切った</li>
 *   <li>確定的な失敗 (非公開・年齢制限・地域制限) — 何度開き直しても同じ</li>
 *   <li>セッションの入れ替えに余力が無い — 入れ替え無しの再試行は効果ゼロ (対照 0/10)</li>
 * </ul>
 * どの場合も理由を握り潰さず、届け先へそのまま流す (利用者の画面に分類つきで出る)。
 */
final class RetryingAudioSource implements IAudioSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetryingAudioSource.class);

    /** 終端を見てから理由の到着を待つ既定の上限。MC の streaming スレッドを塞ぐので短く。 */
    static final long DEFAULT_GRACE_MS = 1_500L;
    /** 理由の到着を見に行く間隔。 */
    private static final long POLL_MS = 20L;
    /**
     * 経過時間 (ms) の既定の出どころ。<b>単調時計であること</b>が要点で、絶対時刻としては意味を
     * 持たない (基準点は JVM ごとに任意)。
     *
     * <p>壁時計で測ると、OS の時刻が後方修正された瞬間に {@link #awaitFault} の上限が遠のき、
     * 理由待ちが {@link #graceMs} を大きく超えて居座る。その間 {@link #resolversInFlight} が残ったまま
     * なので {@link #read} は {@code 0} を返し続け、<b>「1.5 秒 + 開き直し 1 回で必ず閉じる」という
     * この窓の前提が成立しなくなる</b>。
     */
    private static final LongSupplier MONOTONIC_MS =
            () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    private static final ThreadFactory FAULT_AWAITER_THREADS = runnable -> {
        final Thread thread = new Thread(runnable, "music-disc-maker-fault-await");
        thread.setDaemon(true);
        return thread;
    };
    private static final Executor DEFAULT_FAULT_AWAITER =
            command -> FAULT_AWAITER_THREADS.newThread(command).start();

    /** 開き直し 1 回分。中で解決からやり直す (失敗は {@link ResolveException})。 */
    @FunctionalInterface
    interface Opener {
        IAudioSource open();
    }

    private final Opener opener;
    private final YoutubeSession session;
    private final Predicate<FailureReason> retryable;
    private final long graceMs;
    /** 経過時間 (ms)。<b>単調時計</b>から取る (壁時計を渡さないこと。理由は {@link #MONOTONIC_MS})。 */
    private final LongSupplier monotonicMs;
    private final Executor faultAwaiter;
    /**
     * {@link #inner} を入れ替えた<b>直後</b>に呼ばれる。<b>本番では何もしない</b>
     * ({@code () -> { }})。
     *
     * <h2>なぜ製品コードに口を開けるか</h2>
     * 入れ替えから {@link #resolveSilentEnd} の {@code finally} までの間、この class は
     * <b>外の code を一切呼ばない</b> — {@code session} も {@code opener} も
     * {@code closeQuietly} も入れ替えより前に済んでおり、後ろに残るのは {@code closed} の
     * 読み出しと log だけ。つまり「世代 N が入れ替えを済ませ、まだ {@code finally} に
     * 入っていない」という状態を<b>外から作る手がかりが存在しない</b>。
     *
     * <p>この窓こそが世代をまたいだ取り違えの起きる場所なので、そこを踏めない test は
     * 実時間頼みになる。{@code monotonicMs} / {@code faultAwaiter} と同じく、
     * <b>差せる口を 1 つ開けて並びを掛け金で固定する</b>。
     * 固定している test は {@code RetryingAudioSourceOverlappingResolverTest}。
     */
    private final Runnable afterReopen;

    /** 届け先。<b>やり直しても駄目だった時だけ</b>ここへ流す。 */
    private final PlaybackFaultRelay relay = new PlaybackFaultRelay();

    /**
     * 既にやり直しを投げたソース。<b>1 つのソースにつき、やり直しは一生に 1 本だけ</b>。
     *
     * <p>{@link #read} は「投げるか」を先に決め、「{@link #inner} が入れ替わっていないか」を
     * その後で見る。この順番のままだと、<b>read が古いソースを掴んでいる間に先行のやり直しが
     * 入れ替え ({@link #reopen}) と後始末 (飛行中の印を下ろす) を済ませた</b>時に、
     * <b>古いソース宛ての 2 本目</b>が飛ぶ。2 本目は {@code retriesLeft} を使い切った状態で
     * 古い失敗を拾うので「やり直せない」と判定し {@link #terminated} を立てる —
     * 健全なソースが {@link #inner} に入っているのに、次の {@link #read} は {@code -1} を返す。
     * <b>やり直しは成功したのに無音のまま終わる</b>ように見える。
     *
     * <p>確認の順番を入れ替えるだけでは<b>窓は縮むだけで閉じない</b> — 「入れ替わっていない」と
     * 見てから投げるまでの間にも入れ替えは起こりうる。そこで<b>投入そのものを原子的な 1 回性の
     * 判定にする</b>。{@code add} は同期化された集合への追加で、<b>取り除くことは一度も無い</b>
     * ので、あるソースに対して真を返せるのは (何本のスレッドがどう割り込んでも) 生涯 1 回だけ。
     * 「読む → 判定する → 投げる」が 1 つの原子操作に畳まれており、間に別スレッドが
     * {@link #inner} を入れ替える隙が<b>存在しない</b>。
     *
     * <p><b>ソース単位であってストリーム単位ではない</b>のが要点。開き直した先が改めて
     * 落ちた時は、その新しいソースに対するやり直しが (上限が残っていれば) 普通に飛ぶ。
     *
     * <p>同一性 ({@code ==}) で持つ。中身の等価性ではなく<b>そのインスタンスに投げたか</b>が
     * 問いなので、{@code equals} を上書きしたソースが来ても判定がぶれない。抱える数は
     * 最初の 1 本 + やり直しの上限 (本番は {@code REOPEN_RETRIES} = 1) で頭打ちになる。
     */
    private final Set<IAudioSource> resolverDispatchedFor =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private volatile IAudioSource inner;
    private volatile boolean emitted;
    private volatile boolean closed;
    /**
     * 終端を確定させた印。<b>一度 {@code -1} を返したら、以後は即座に {@code -1} を返す。</b>
     *
     * <p>これが無いと、終端を見た後も MC が引きに来るたびに {@link #awaitFault} を通り、
     * <b>1 回につき {@link #graceMs} をまるごと払う</b>。正常に鳴り終わった曲には理由が
     * 一生来ないので、この待ちは毎回上限まで走る。MC は終端後もチャンネルを手放すまで
     * {@code updateStream} → {@code pumpBuffers} を回し続けるため、<b>曲の終わりごとに
     * Sound engine スレッドが数秒止まる</b> = 切り替わりのフリーズ
     * (MDM_DECISIONS D28 の「1 曲につき 4 回・間隔 1.505s」は 1500ms + poll 20ms のこと)。
     */
    private volatile boolean terminated;
    /**
     * 飛行中の理由待ちの<b>本数</b>。<b>1 本でも残っている間は終端を返さない</b>
     * ({@code 0} を返す)。
     *
     * <h2>なぜ boolean では足りないか</h2>
     * <b>やり直しは世代をまたいで重なる</b>。世代 N のやり直しが {@link #inner} を N+1 へ
     * 入れ替えると、{@link #read} はその N+1 を見て<b>もう 1 本</b>投げる
     * ({@link #resolverDispatchedFor} はソース単位の 1 回性なので、これは正しく通る)。
     * この瞬間、N はまだ {@code finally} に入っていない。
     *
     * <p>印が boolean だと、<b>N の後始末が N+1 の立てた印を下ろす</b> — 自分が下ろすべき
     * でない印を下ろす。{@link #read} は「飛行中ではない」と読んで {@code 0} ではなく終端へ
     * 倒れ、N+1 の理由が {@link #relay} へ届く前に {@code -1} が呼び出し側へ出る。
     * <b>分類のついた理由を出せたはずの場面で {@code UNKNOWN / no audio} になる</b> =
     * この窓が塞ごうとしている症状そのもの。
     *
     * <p>本数なら、下ろす主体と下ろされる印が 1 対 1 で結び付く。<b>どのやり直しも自分が
     * 増やしたぶんしか減らさない</b> — 増やすのは投入 1 回につき 1 だけ ({@link #read}) で、
     * その 1 を握った {@link InFlight} を持つのはそのやり直しだけ、しかも
     * {@link InFlight#lower()} は CAS で<b>生涯 1 回しか通らない</b>。「飛行中か」は
     * {@code > 0} で見る。
     *
     * <p><b>投げるかどうかを決めるのはこの数ではない</b> ({@link #resolverDispatchedFor})。
     * これは「今この {@link #inner} の決着が付いていない」を {@link #read} の戻り値
     * ({@code 0} か {@code -1} か) へ翻訳するためだけに持つ。<b>2 つは役割が違うので両方要る</b> —
     * 片方は投入の 1 回性 (ソース単位・取り除かない)、片方は決着の有無 (世代をまたいで増減する)。
     *
     * <p>{@link #resolveSilentEnd} は Sound engine の外で走るので、投げた時点では結果が無い。
     * そこで {@code -1} を返すと、呼び出し元は<b>やり直しの結果を見る前に終端を確定させる</b> —
     * {@code LavaPlayerAudioStream#prefill} は {@code prebufferEnded} を立て、開き直しが成功して
     * {@link #inner} が健全なソースに入れ替わっても二度と読まれない。届いた理由も
     * {@code reportEnd} には間に合わず、分類の消えた {@code UNKNOWN / no audio} だけが残る。
     *
     * <p>{@code 0} は「今この瞬間に出せる分が無い」であって終端ではない
     * ({@code LavaAudioSource} と同じ約束)。上の層はこれを待たずに扱える —
     * {@code prefill} は刻んで粘り、{@code LavaPlayerAudioStream#read} は残りを無音で埋めて返す。
     * <b>音声スレッドを掴まないまま、決着だけを待てる。</b>
     *
     * <p>この窓は必ず閉じる。{@link #resolveSilentEnd} を抜けた時点で「{@link #terminated} が
     * 立っている」「{@link #inner} が入れ替わっている」「{@link #closed} である」のいずれかが
     * 成立しており、長さは {@link #graceMs} + 開き直し 1 回 ({@code MusicLoaderImpl} の
     * {@code REOPEN_TIMEOUT_MS} = 10 秒) で頭打ちになる。
     *
     * <p><b>「必ず」はコード上の保証として書いてある</b> — 印を下ろすのは {@code finally}、
     * 投げる先が受け取らなかった場合は {@link #read} がその場で終端に倒す。正常系だけで
     * 下ろしていた頃は、途中で例外が抜けると立ったまま残った。上限が実時間で頭打ちになるのも
     * 経過時間を単調時計から取るからで、壁時計だと時刻調整で遠のく ({@link #MONOTONIC_MS})。
     */
    private final AtomicInteger resolversInFlight = new AtomicInteger();
    private int retriesLeft;

    /**
     * 飛行中 1 本ぶんの持ち分。<b>握った者だけが、1 回だけ下ろせる</b>。
     *
     * <p>投入のたびに新しく作られ、そのやり直しと一緒に飛ぶ。{@link #lower()} は CAS で
     * 通れるのが生涯 1 回だけなので、<b>二重に下ろしても数は 1 しか減らない</b> —
     * {@code faultAwaiter.execute} が「やり直しを走らせてから」例外を投げるような
     * 相手 (同期 executor 等) でも、下がるのは自分の 1 本ぶんだけで、他の世代の飛行が
     * 巻き添えで消えることがない。
     */
    private final class InFlight {

        private final AtomicBoolean lowered = new AtomicBoolean();

        /** 自分の 1 本ぶんを下ろす。2 回目以降は何もしない。 */
        void lower() {
            if (lowered.compareAndSet(false, true)) {
                resolversInFlight.decrementAndGet();
            }
        }
    }

    /**
     * 本番の入口。<b>時計を受け取らない</b> — 経過時間は必ず {@link #MONOTONIC_MS} から取る。
     * 壁時計を渡せる口を残すと、OS の時刻調整で理由待ちが閉じなくなる経路が復活する。
     */
    RetryingAudioSource(IAudioSource inner, Opener opener, YoutubeSession session,
            Predicate<FailureReason> retryable, int maxRetries, long graceMs) {
        this(inner, opener, session, retryable, maxRetries, graceMs, MONOTONIC_MS,
                DEFAULT_FAULT_AWAITER);
    }

    /**
     * テスト用。実時間を待たずに理由待ちの上限を作れるように、経過時間の出どころを差せる。
     *
     * @param monotonicMs 経過時間 (ms)。<b>単調に進むものを渡すこと</b>
     */
    RetryingAudioSource(IAudioSource inner, Opener opener, YoutubeSession session,
            Predicate<FailureReason> retryable, int maxRetries, long graceMs,
            LongSupplier monotonicMs, Executor faultAwaiter) {
        this(inner, opener, session, retryable, maxRetries, graceMs, monotonicMs, faultAwaiter,
                () -> { });
    }

    /**
     * テスト用。入れ替えの直後で止められるように、開き直しの後始末に差し込む口を持つ。
     *
     * @param afterReopen {@link #inner} を入れ替えた直後に呼ばれる。詳細は {@link #afterReopen}
     */
    RetryingAudioSource(IAudioSource inner, Opener opener, YoutubeSession session,
            Predicate<FailureReason> retryable, int maxRetries, long graceMs,
            LongSupplier monotonicMs, Executor faultAwaiter, Runnable afterReopen) {
        this.afterReopen = afterReopen;
        this.inner = inner;
        this.opener = opener;
        this.session = session;
        this.retryable = retryable;
        this.retriesLeft = maxRetries;
        this.graceMs = graceMs;
        this.monotonicMs = monotonicMs;
        this.faultAwaiter = faultAwaiter;
    }

    @Override
    public int read(byte[] dst, int off, int len) {
        if (terminated) {
            return -1;
        }
        while (true) {
            final IAudioSource current = inner;
            final int n = current.read(dst, off, len);
            if (n > 0) {
                emitted = true;
                return n;
            }
            if (n == 0) {
                return 0;
            }
            if (closed) {
                return terminal();
            }
            current.onPlaybackFault(relay::record);
            if (emitted) {
                final PlaybackFault immediateFault = current.playbackFault();
                if (immediateFault != null) {
                    relay.record(immediateFault);
                }
                return terminal();
            }
            if (resolverDispatchedFor.add(current)) {
                final InFlight mark = new InFlight();
                resolversInFlight.incrementAndGet();
                try {
                    faultAwaiter.execute(() -> resolveSilentEnd(current, mark));
                } catch (final Throwable t) {
                    // 投げる先が受け取らなかった (飽和・シャットダウン)。飛行中の印を立てたままに
                    // すると read は 0 を返し続け、MC は終端を受け取れない = source と channel を
                    // 掴んだまま二度と手放されない。決着が付かないなら終端に倒す。
                    mark.lower();
                    LOGGER.warn("Could not hand the end of the stream to the fault awaiter", t);
                    return terminal();
                }
            }
            if (!terminated && inner != current) {
                continue;
            }
            if (!terminated && resolversInFlight.get() > 0) {
                return 0; // やり直しが飛行中。終端はまだ確定していない
            }
            return -1;
        }
    }

    /** 終端を確定させて {@code -1} を返す。 */
    private int terminal() {
        terminated = true;
        return -1;
    }

    /**
     * 理由待ちと再オープンは Sound engine の外で行う。
     *
     * <p><b>どう抜けても飛行中の印を下ろす</b> ({@code finally})。ここを正常系だけで下ろしていた
     * ので、届け先 (sink) の例外が {@code relay.record} から返ってくると
     * 飛行中の印が立ったまま / {@code terminated=false} / {@link #inner} 未交換のまま残り、
     * 後続の {@link #read} が永久に {@code 0} を返し続けた。
     *
     * <p>例外を飲んだ時は<b>終端に倒す</b>。決着が付かないまま {@code 0} を返し続けるより、
     * 音源と channel を手放させる方が害が小さい。届け先が投げた場合は理由が利用者へ届かないが、
     * 壊れているのは届け先の側で、こちらが握り潰したわけではない。
     */
    private void resolveSilentEnd(IAudioSource current, InFlight mark) {
        try {
            final PlaybackFault fault = awaitFault(current);
            if (fault != null) {
                if (!canRetry(fault)) {
                    relay.record(fault);
                    terminated = true;
                } else {
                    final PlaybackFault giveUp = reopen(fault);
                    if (giveUp != null) {
                        relay.record(giveUp);
                        terminated = true;
                    }
                }
            } else if (!closed) {
                endedWithoutReason();
                terminated = true;
            }
        } catch (final Throwable t) {
            LOGGER.warn("Failed to settle the end of the stream", t);
            terminated = true;
        } finally {
            mark.lower(); // 下ろすのは自分の 1 本ぶんだけ (他の世代の飛行には触れない)
        }
    }

    /**
     * 理由が付かないまま終端に達した時の答え。
     *
     * <p>PCM を 1 バイトでも渡していれば「最後まで鳴った」で正しい。渡していないなら違う —
     * <b>開けたのに一音も鳴らないまま終わった</b>のであって、理由が付いていないだけ。
     * 従来はこの 2 つを同じ {@code -1} に潰していたので、上の層は正常終了として扱い、
     * 利用者にもログにも何も出ないまま無音になっていた。
     *
     * <p>{@link #closed} を見るのは、{@link #awaitFault} が理由の到着を待っている間 (最大
     * {@link #graceMs}) に停止されうるから。その {@code null} は失敗ではなく停止の結果なので、
     * 失敗を捏造しない。
     */
    private void endedWithoutReason() {
        if (emitted || closed) {
            return;
        }
        LOGGER.warn("The stream ended without producing any audio and without reporting a reason");
        relay.record(new PlaybackFault(FailureReason.UNKNOWN, "stream ended without audio"));
    }

    /** やり直す価値があるか。 */
    private boolean canRetry(PlaybackFault fault) {
        return !emitted && !closed && retriesLeft > 0 && retryable.test(fault.reason());
    }

    /** セッションを入れ替えて開き直す。 */
    private PlaybackFault reopen(PlaybackFault fault) {
        retriesLeft--;
        final long seen = session.generation();
        if (!session.rollIfStale(seen)) {
            return fault;
        }
        closeQuietly(inner);
        try {
            final IAudioSource fresh = opener.open();
            if (fresh == null) {
                return fault;
            }
            inner = fresh;
            afterReopen.run(); // 本番では何もしない (test が並びを固定する口。{@link #afterReopen})
            if (closed) {
                closeQuietly(fresh);
                return fault;
            }
            LOGGER.info("Playback died, so the YouTube session was rotated and the stream reopened ({})", fault.reason());
            return null;
        } catch (final ResolveException ex) {
            return new PlaybackFault(ex.reason(), "retry: " + ex.reason());
        } catch (final Throwable t) {
            LOGGER.warn("Failed to reopen the stream", t);
            return fault;
        }
    }

    /** 理由が確定するのを少しだけ待つ (終端の方が先に来るため)。 */
    private PlaybackFault awaitFault(IAudioSource current) {
        final long deadline = monotonicMs.getAsLong() + graceMs;
        while (true) {
            final PlaybackFault fault = current.playbackFault();
            if (fault != null) {
                return fault;
            }
            if (closed || monotonicMs.getAsLong() >= deadline) {
                return null;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                return current.playbackFault();
            }
        }
    }

    private static void closeQuietly(IAudioSource source) {
        try {
            source.close();
        } catch (final Throwable ignored) {
            // 閉じ損ねても再生は止める
        }
    }

    @Override
    public PlaybackFault playbackFault() {
        return relay.fault();
    }

    @Override
    public void onPlaybackFault(Consumer<PlaybackFault> sink) {
        relay.sink(sink);
    }

    @Override
    public int sampleRate() {
        return inner.sampleRate();
    }

    @Override
    public int channels() {
        return inner.channels();
    }

    @Override
    public int bitsPerSample() {
        return inner.bitsPerSample();
    }

    @Override
    public boolean bigEndian() {
        return inner.bigEndian();
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(inner);
    }
}
