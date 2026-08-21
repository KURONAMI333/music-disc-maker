package com.kuronami.musicdiscmaker.client.audio;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFormat;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;

/**
 * {@link IAudioSource} (LavaPlayer PCM) を MC sound engine の {@link AudioStream} に橋渡しする。
 * MC の {@code Channel.pumpBuffers} がこの {@code read} を呼び、OpenAL バッファに流す。
 */
public class LavaPlayerAudioStream implements AudioStream {

    private final IAudioSource source;
    private final AudioFormat format;
    private final byte[] scratch = new byte[8192];
    /**
     * ストリームが終端に達した (read=-1) 時に一度だけ呼ばれるコールバック。ラジオの瞬断検知用
     * (再接続のトリガー)。streaming thread から呼ばれるので、実装は重い処理を別スレッドへ逃がすこと。
     */
    @Nullable
    private final Runnable onEnded;
    private final AtomicBoolean endedNotified = new AtomicBoolean(false);
    /**
     * 再生スレッドの中で落ちた失敗の届け先。{@code null} なら曲名を持たない経路として
     * このクラスが直接報告する。
     */
    @Nullable
    private final Consumer<PlaybackFailure> onFailure;
    /** 1 つのストリームから同じ失敗を二度上げないためのガード (read は何度も呼ばれる)。 */
    private final AtomicBoolean failureReported = new AtomicBoolean(false);
    /**
     * これまでに MC へ渡した PCM のバイト数。終端が「最後まで鳴った」のか「一音も鳴らなかった」のかは
     * {@code read} の戻り値では区別できないので、ここで数える。
     */
    private final AtomicLong pcmBytes = new AtomicLong();
    /**
     * こちらが意図して終わらせた印 (ディスク取り出し・撤去・差し替え)。立っている間の終端は
     * 失敗ではないので報告しない。<b>これが無いと、曲の途中でディスクを抜くたびに
     * 「途中で切れた」が出る</b>。
     */
    private final AtomicBoolean expectedEnd = new AtomicBoolean(false);
    /**
     * 「理由なく終わった」の INFO を 1 曲につき 1 回だけにするガード。{@code read} は終端を見た後も
     * 何度も呼ばれるので、これが無いと同じ行が 1 曲で 4 回出る (MDM_DECISIONS D28)。
     */
    private final AtomicBoolean endLogged = new AtomicBoolean(false);
    /** 無音で埋めた合計バイト数 ({@link #pcmBytes} には入れない)。 */
    private final AtomicLong silenceBytes = new AtomicLong();
    /** 無音で埋めた回数 (アンダーランの回数)。 */
    private final AtomicInteger underruns = new AtomicInteger();
    /** 無音の要約を 1 曲につき 1 行だけにするガード。 */
    private final AtomicBoolean underrunLogged = new AtomicBoolean(false);

    /**
     * この切り替わりの所要時間の集め先 ({@code null} = 計測しない経路)。
     * 書き込み = {@code SoundEngine#play} を回している Render thread / 読み出し = streaming スレッド。
     */
    @Nullable
    private volatile PlaybackTiming timing;

    /**
     * MC が再生開始時に引く量 (byte)。{@code Channel.attachBufferStream} は
     * {@code calculateBufferSize(format, 1)} = 1 秒分を 4 回 ({@code pumpBuffers(4)}) 引く。
     */
    private final long firstBufferTargetBytes;

    /** MC が最初に {@code read} を呼んだ時刻 (streaming スレッド専用)。 */
    private long firstReadNanos;
    /** 最初の 4 秒分の計測を報告済みか (streaming スレッド専用)。 */
    private boolean firstBufferReported;

    /**
     * {@link #prefill} が先に引いておいた PCM。使い切ったら {@code null} に戻して手放す。
     *
     * <p>書き込み = 充填スレッド ({@link #prefill}) / 読み出し = MC の streaming スレッド。
     * 両者は {@code CompletableFuture} の完了を挟んで直列なので競合しないが、
     * 可視性を型で見えるようにするため {@code volatile} にしてある。
     */
    @Nullable
    private volatile byte[] prebuf;
    /** {@link #prebuf} の有効長。 */
    private volatile int prebufLen;
    /** 充填中に終端を見たか。見ていたら {@code source} を引き直さない。 */
    private volatile boolean prebufferEnded;
    /** {@link #prebuf} の読み出し位置 (streaming スレッド専用)。 */
    private int prebufPos;

    /**
     * PCM 段のゲイン (1.0 = 素通し)。{@code SoundEngine#calculateVolume} が OpenAL へ渡す gain を
     * [0,1] にクランプするので、1.0 を超える音量はサンプル値そのものに掛けるしかない。
     * {@link DiscSoundInstance} がスライダーの現在値から算出して押し込む
     * (書き込み = client tick スレッド / 読み出し = streaming スレッド)。
     */
    private volatile float pcmGain = 1.0F;
    /** 適用中のゲイン。目標値へ 1 極フィルタで追従させる (段差はクリックノイズになる)。streaming スレッド専用。 */
    private float appliedGain = 1.0F;
    /** PCM ゲインを適用できるフォーマットか。16bit little-endian 以外は素通しにする。 */
    private final boolean gainApplicable;

    /** 1 サンプルあたりのゲイン追従係数。48kHz で時定数 ≒ 43ms。 */
    private static final float GAIN_SMOOTHING = 1.0F / 2048.0F;
    /** 目標値への吸着幅。1 極フィルタは厳密には到達しないので、この差まで来たら合わせる。 */
    private static final float GAIN_EPSILON = 1.0E-4F;
    /**
     * ソフトリミッタの直線領域の上端 (full scale 比)。ここまでは無加工で通し、超えた分だけ
     * 1.0 へ漸近圧縮する。単純な clip は波形の頭を平らに切り落として矩形波成分を作る = 歪むので使わない。
     */
    private static final float LIMIT_KNEE = 0.8F;

    public LavaPlayerAudioStream(IAudioSource source) {
        this(source, null, null);
    }

    public LavaPlayerAudioStream(IAudioSource source, @Nullable Runnable onEnded) {
        this(source, onEnded, null);
    }

    /**
     * @param source    PCM ソース
     * @param onEnded   終端で一度だけ呼ばれるコールバック (ラジオ再接続用。null=無効)
     * @param onFailure 再生中に壊れた時に一度だけ呼ばれる届け先 (null=曲名なしで自分で報告する)
     */
    public LavaPlayerAudioStream(IAudioSource source, @Nullable Runnable onEnded,
            @Nullable Consumer<PlaybackFailure> onFailure) {
        this.source = source;
        this.onEnded = onEnded;
        this.onFailure = onFailure;
        this.format = new AudioFormat(
                source.sampleRate(), source.bitsPerSample(), source.channels(), true, source.bigEndian());
        this.gainApplicable = source.bitsPerSample() == 16 && !source.bigEndian();
        this.firstBufferTargetBytes = (long) MC_FIRST_BUFFERS * format.getChannels()
                * (format.getSampleSizeInBits() / 8) * (long) format.getSampleRate();
    }

    /**
     * MC が {@code attachBufferStream} で引く 1 秒バッファの本数 ({@code Channel.pumpBuffers(4)})。
     * この本数を引き切るまで "Sound engine" スレッドは戻らない。
     */
    private static final int MC_FIRST_BUFFERS = 4;

    /**
     * 先読み充填に確保してよいメモリの上限 (byte)。想定フォーマット (mono/48kHz/16bit) の 4 秒は
     * 384,000 byte なので通常は当たらない。想定外のフォーマットで巨大な配列を掴まないための蓋。
     */
    private static final int MAX_PREBUFFER_BYTES = 2_000_000;

    /**
     * 充填中にデータが無かった時に次を試すまでの間隔 (ms)。{@code source.read} は待たずに
     * {@code 0} を返す設計なので、この刻みが無いと空回りで CPU を焼く。
     */
    private static final long IDLE_POLL_MS = 20L;

    /**
     * この切り替わりの所要時間の集め先を差す。{@code SoundManager#play} へ渡す前に差すこと
     * (差した後は streaming スレッドしか読まない)。
     *
     * @param value 集め先 ({@code null} 可)
     */
    void setTiming(@Nullable PlaybackTiming value) {
        this.timing = value;
    }

    /**
     * MC が再生開始時に引く 4 秒分を、<b>あらかじめメモリへ引いておく</b>。
     *
     * <h2>これが要る理由</h2>
     * {@code SoundEngine#play} は Render thread で {@code channelAccess.createHandle(...).join()}
     * を待つ。その待ち行列を捌く "Sound engine" は<b>単一スレッド</b>で、同じスレッドが
     * {@code Channel.attachBufferStream} → {@code pumpBuffers(4)} を回す。cold なソースだと
     * ここが 4 秒帰ってこないので、<b>その間に Render thread から出た全ての音 (バニラの足音・
     * アイテム音を含む) が待たされる</b> = 曲の切り替わりのフリーズ (MDM_DECISIONS D28)。
     *
     * <p>ここで先に満たしておけば {@code attachBufferStream} はメモリから即座に埋まり、
     * 待ち行列は空くのが早い。<b>先読み ({@link PlaybackPrefetch}) の当否とは無関係に効く</b> —
     * 先読みが縮めるのは read の中の待ち時間だけで、per-switch の直列化そのものは消していない。
     *
     * <h2>lavaplayer 側の制約との関係</h2>
     * ここがやるのは frame buffer を<b>引き抜く</b>ことなので、251 frame (≒5 秒) で満杯になって
     * デコードスレッドが {@code put()} で止まる状態は、むしろこの充填で解ける。
     * {@code AudioPlayerLifecycleManager} の 60 秒リーパーが見る {@code lastRequestTime} を
     * 打つのは {@code startTrack} と {@code provide} だけなので、{@code provide} を通る
     * この充填はリーパーの時計も同時に叩き直す。どちらに対しても不利に働かない。
     *
     * <h2>いつ諦めるか</h2>
     * 待ち時間の上限を {@code budgetMs} で置く。判定は<b>読み出しと読み出しの間</b>だけで行い、
     * 走っている {@code source.read} を放り出さない — 放り出すと充填スレッドと streaming
     * スレッドが同じソースを同時に読むことになり、{@code LavaAudioSource} の {@code leftover}
     * が壊れる。上限に達した時は引けた分だけを持って戻り、残りは従来どおり
     * {@code pumpBuffers} が引く (= この変更を入れる前の挙動に落ちるだけで、悪化はしない)。
     *
     * <p><b>{@code 0} で諦めない。</b> ソースはデータが無い時に待たずに {@code 0} を返すので
     * ({@code LavaAudioSource} の javadoc)、cold なソースの 1 回目はほぼ必ず {@code 0} になる。
     * そこで打ち切るとここが常に空振りし、4 秒ぶん全部を Sound engine スレッドが引く
     * = この充填を入れた意味が消える。
     *
     * <p>再生が始まる時刻は変わらない。従来も {@code pumpBuffers(4)} が終わるまで
     * {@code channel.play()} は撃たれていないので、鳴り始めまでの実時間は同じ場所で待っている。
     *
     * @param budgetMs 充填に使ってよい時間の上限 (ms)
     */
    void prefill(long budgetMs) {
        final int target = (int) Math.min(firstBufferTargetBytes, MAX_PREBUFFER_BYTES);
        final byte[] buf = new byte[target];
        final long start = System.nanoTime();
        final long deadline = start + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        int len = 0;
        boolean ended = false;
        while (len < target && System.nanoTime() < deadline) {
            final int n = source.read(buf, len, target - len);
            if (n < 0) {
                ended = true;
                break;
            }
            if (n == 0) {
                // まだ届いていないだけ。ここは専用スレッドで誰も待っていないので、
                // 上限まで粘ってよい。粘った分だけ Sound engine スレッドが引く量が減る。
                // (source.read はデータが無ければ待たずに 0 を返すので、ここで刻む)
                try {
                    Thread.sleep(IDLE_POLL_MS);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            len += n;
        }
        this.prebuf = len > 0 ? buf : null; // 1 バイトも引けなかったら抱えない
        this.prebufLen = len;
        this.prebufferEnded = ended;
        final PlaybackTiming t = timing;
        if (t != null) {
            t.prefilled(PlaybackTiming.msSince(start), len, len >= target);
        }
    }

    /**
     * {@link #prefill} が引いておいた分を {@link #scratch} へ移す。
     *
     * @param want 欲しいバイト数
     * @return 移せたバイト数。持ち合わせが無ければ 0
     */
    private int takePrebuffered(int want) {
        final byte[] pre = prebuf;
        if (pre == null || prebufPos >= prebufLen) {
            return 0;
        }
        final int n = Math.min(want, prebufLen - prebufPos);
        System.arraycopy(pre, prebufPos, scratch, 0, n);
        prebufPos += n;
        if (prebufPos >= prebufLen) {
            prebuf = null; // 使い切った。4 秒分 (≒384KB) を抱えたままにしない
        }
        return n;
    }

    /** 終端に達した時の後始末。{@code read} の 2 つの終端経路から呼ぶ。 */
    private void endOfStream() {
        noteFirstBuffer(true);
        reportUnderruns();
        // トラック終端 → 空 (もしくは残り) を返すと MC が再生終了とみなす。
        // ただし「最後まで鳴った」のか「途中で落ちた」のかは read の戻り値では区別が
        // つかない。落ちていた時だけソースが理由を持っているので、ここで引いて報告する
        // (ここを見ないと、再生スレッドの中で落ちた失敗は完全な無音のまま終わる)。
        reportEnd();
        // ラジオの瞬断もここに来る (lavaplayer が track を終了させる) ので再接続を促す。
        if (onEnded != null && endedNotified.compareAndSet(false, true)) {
            onEnded.run();
        }
    }

    /**
     * MC が最初に引く 4 秒分を渡し切ったかを見て、1 回だけ計測を報告する。
     *
     * @param ended 終端に達したので、届いていなくてもそこで打ち切る
     */
    private void noteFirstBuffer(boolean ended) {
        if (firstBufferReported) {
            return;
        }
        final boolean complete = pcmBytes.get() >= firstBufferTargetBytes;
        if (!complete && !ended) {
            return;
        }
        firstBufferReported = true;
        final PlaybackTiming t = timing;
        if (t != null) {
            t.buffered(PlaybackTiming.msSince(firstReadNanos), complete);
        }
    }

    /**
     * PCM 段のゲインを設定する。1.0 = 素通し。{@link DiscSoundInstance} が
     * 「OpenAL では表現できない 1.0 超の領域」だけをここへ回す。
     */
    public void setPcmGain(float gain) {
        this.pcmGain = gain > 0.0F ? gain : 0.0F;
    }

    /**
     * {@code buf[0..len)} の 16bit little-endian サンプルへゲインとソフトリミッタを掛ける (in-place)。
     *
     * <p>ゲインが 1.0 のまま定常なら 1 サンプルも触らずに返る = 既定状態の音は今までと bit 一致する。
     * 目標値が変わったときは 1 極フィルタで滑らかに追従させる (スライダーは操作中に毎 tick 押し込まれる
     * ので、段差で入れるとドラッグのたびにクリックノイズが乗る)。
     */
    private void applyGain(byte[] buf, int len) {
        if (!gainApplicable) {
            return;
        }
        final float target = pcmGain;
        if (target == 1.0F && appliedGain == 1.0F) {
            return;
        }
        float g = appliedGain;
        for (int i = 0; i + 1 < len; i += 2) {
            g += (target - g) * GAIN_SMOOTHING;
            if (Math.abs(target - g) < GAIN_EPSILON) {
                g = target;
            }
            final short raw = (short) ((buf[i] & 0xFF) | (buf[i + 1] << 8));
            final int out = limit(raw / 32768.0F * g);
            buf[i] = (byte) (out & 0xFF);
            buf[i + 1] = (byte) ((out >> 8) & 0xFF);
        }
        appliedGain = g;
    }

    /**
     * ソフトリミッタ + 16bit 量子化。{@link #LIMIT_KNEE} までは無加工、それを超えた分は
     * {@code u/(1+u)} で 1.0 へ漸近させる (knee 上で傾き 1 = 連続、full scale を絶対に超えない)。
     */
    private static int limit(float x) {
        final float a = Math.abs(x);
        float y = a;
        if (a > LIMIT_KNEE) {
            final float u = (a - LIMIT_KNEE) / (1.0F - LIMIT_KNEE);
            y = LIMIT_KNEE + (1.0F - LIMIT_KNEE) * (u / (1.0F + u));
        }
        final int s = Math.round((x < 0.0F ? -y : y) * 32767.0F);
        if (s < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return s > Short.MAX_VALUE ? Short.MAX_VALUE : s;
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>足りない分は無音で埋める。待たない。</b>
     * この {@code read} を引くのは MC の単一の "Sound engine" スレッドで、同じスレッドが
     * {@code SoundEngine#play} の {@code createHandle().join()} も捌く。データが届くまで
     * ここで待つと<b>バニラの効果音も Render thread も道連れで止まる</b>。
     *
     * <p>そこでソースが短く返したら (= 今この瞬間に出せる分が尽きたら)、<b>残りを無音で埋めて
     * 満杯の buffer を返す</b>。アンダーランを無音で埋めるのは音声ストリームの常道であり、
     * ここでは<b>そうしないと再生そのものが死ぬ</b>という事情もある — 空の buffer を返すと
     * OpenAL のキューが空になり、ソースが {@code AL_STOPPED} へ落ちる。MC はそれを
     * 「鳴り終わった」と読んで ({@code SoundEngine#tickNonPaused} の {@code isStopped()})
     * チャンネルごと捨てるので、曲は途中で無音のまま終わる。
     */
    @Override
    public ByteBuffer read(int size) {
        if (firstReadNanos == 0L) {
            firstReadNanos = System.nanoTime();
        }
        final ByteBuffer buffer = BufferUtils.createByteBuffer(size);
        while (buffer.hasRemaining()) {
            final int want = Math.min(scratch.length, buffer.remaining());
            final int fromPrebuffer = takePrebuffered(want);
            if (fromPrebuffer > 0) {
                emit(buffer, fromPrebuffer);
                continue; // 先読み分は素直に流し切る (尽きたら次の周で source を引く)
            }
            if (prebufferEnded) {
                // 充填中に終端を見ている。source を引き直すと RetryingAudioSource の
                // 理由待ち (graceMs) をもう一度払うだけなので、ここで終わらせる。
                endOfStream();
                break;
            }
            final int read = source.read(scratch, 0, want);
            if (read < 0) {
                endOfStream();
                break;
            }
            if (read > 0) {
                emit(buffer, read);
            }
            if (read < want) {
                // ソースが短く返した = 今は出せない。待たずに残りを無音で埋めて返す。
                padWithSilence(buffer);
                break;
            }
        }
        buffer.flip();
        return buffer;
    }

    /** 実データを 1 かたまり buffer へ移す (ゲイン適用と計測を伴う)。 */
    private void emit(ByteBuffer buffer, int len) {
        applyGain(scratch, len);
        buffer.put(scratch, 0, len);
        pcmBytes.addAndGet(len);
        noteFirstBuffer(false);
    }

    /**
     * buffer の残りを無音 (ゼロ) で埋める。
     *
     * <p><b>{@link #pcmBytes} には数えない。</b> あれは「実際に鳴った音」の量で、
     * {@code reportEnd} が「一音も鳴らずに終わった」を見分けるのに使っている。
     * 無音を数えると、一度も音の出なかった曲が正常に鳴ったように見える。
     */
    private void padWithSilence(ByteBuffer buffer) {
        final int n = buffer.remaining();
        if (n <= 0) {
            return;
        }
        for (int i = 0; i < n; i++) {
            buffer.put((byte) 0);
        }
        silenceBytes.addAndGet(n);
        underruns.incrementAndGet();
    }

    /**
     * 無音で埋めた事実を 1 曲につき 1 行だけ残す。終端と close の両方から呼ぶ
     * (曲の途中でディスクを抜いた場合は終端を通らない)。
     */
    private void reportUnderruns() {
        final long bytes = silenceBytes.get();
        if (bytes > 0L && underrunLogged.compareAndSet(false, true)) {
            MusicDiscMaker.LOGGER.info("Filled {} ms of silence across {} buffer underruns"
                    + " (audio data did not arrive in time)", playedMs(bytes), underruns.get());
        }
    }

    /**
     * こちらが意図して終わらせることを伝える (ディスク取り出し・撤去・差し替え)。
     * 以後の終端は失敗として扱わない。{@link DiscSoundInstance} が音源を閉じる前に呼ぶ。
     */
    void expectEnd() {
        expectedEnd.set(true);
    }

    /**
     * 終端に達した時の後始末。理由が付いていれば報告し、付いていなければ
     * 「最後まで鳴った」のか「一音も鳴らなかった」のかをここで分ける。
     *
     * <p>streaming スレッドから呼ばれるので、届け先を持たない場合の既定の報告は main thread へ
     * 移してから行う ({@link PlaybackFailureReport} はチャットを触る)。
     */
    private void reportEnd() {
        final PlaybackFault fault = source.playbackFault();
        if (fault != null) {
            report(PlaybackFailure.ofReason(fault.reason(), fault.detail()));
            return;
        }
        if (expectedEnd.get()) {
            return; // 止めたのはこちら。失敗ではない
        }
        final long bytes = pcmBytes.get();
        if (bytes == 0L) {
            // 開けたのに 1 バイトも鳴らないまま終わった。理由が付いていないので、従来は
            // 「最後まで鳴った」と同じ扱いで完全な無音のまま何も出なかった。
            if (endLogged.compareAndSet(false, true)) {
                MusicDiscMaker.LOGGER.warn(
                        "The stream ended without producing any audio and without reporting a reason");
            }
            report(PlaybackFailure.ofReason(FailureReason.UNKNOWN, "no audio"));
            return;
        }
        // 途中で切れたのか最後まで鳴ったのかは、ここでは曲の尺を知らないので断定できない。
        // 断定できないものをチャットへ出す代わりに、鳴った長さをログへ残す (latest.log を貼れば
        // 「12 秒で終わった」と「3 分 33 秒鳴った」が区別できる)。
        // 終端を見た後も read は呼ばれ続けるので、出すのは 1 曲につき 1 回だけ。
        if (endLogged.compareAndSet(false, true)) {
            MusicDiscMaker.LOGGER.info("Stream ended after {} ms of audio with no failure reported",
                    playedMs(bytes));
        }
    }

    /** 同じストリームから二度報告しない ({@code read} は何度も呼ばれる)。 */
    private void report(PlaybackFailure failure) {
        if (!failureReported.compareAndSet(false, true)) {
            return;
        }
        if (onFailure != null) {
            onFailure.accept(failure);
            return;
        }
        // 曲名を持たない経路 (compat 側の再生等)。曲名が無くても分類とログは残す。
        Minecraft.getInstance().execute(
                () -> PlaybackFailureReport.report((String) null, null, failure));
    }

    /** 渡した PCM のバイト数を再生時間 (ms) に直す。 */
    private long playedMs(long bytes) {
        final int bytesPerFrame = format.getChannels() * (format.getSampleSizeInBits() / 8);
        final float rate = format.getSampleRate();
        if (bytesPerFrame <= 0 || rate <= 0.0F) {
            return 0L;
        }
        return Math.round(bytes / (double) bytesPerFrame / rate * 1000.0);
    }

    @Override
    public void close() {
        reportUnderruns();
        source.close();
    }
}
