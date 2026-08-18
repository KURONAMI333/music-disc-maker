package com.kuronami.musicdiscmaker.client.audio;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Override
    public ByteBuffer read(int size) {
        final ByteBuffer buffer = BufferUtils.createByteBuffer(size);
        while (buffer.hasRemaining()) {
            final int want = Math.min(scratch.length, buffer.remaining());
            final int read = source.read(scratch, 0, want);
            if (read < 0) {
                // トラック終端 → 空 (もしくは残り) を返すと MC が再生終了とみなす。
                // ただし「最後まで鳴った」のか「途中で落ちた」のかは read の戻り値では区別が
                // つかない。落ちていた時だけソースが理由を持っているので、ここで引いて報告する
                // (ここを見ないと、再生スレッドの中で落ちた失敗は完全な無音のまま終わる)。
                reportEnd();
                // ラジオの瞬断もここに来る (lavaplayer が track を終了させる) ので再接続を促す。
                if (onEnded != null && endedNotified.compareAndSet(false, true)) {
                    onEnded.run();
                }
                break;
            }
            if (read == 0) {
                break; // 取得できず (中断等) → 持ってる分を返す
            }
            applyGain(scratch, read);
            buffer.put(scratch, 0, read);
            pcmBytes.addAndGet(read);
        }
        buffer.flip();
        return buffer;
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
            MusicDiscMaker.LOGGER.warn(
                    "The stream ended without producing any audio and without reporting a reason");
            report(PlaybackFailure.ofReason(FailureReason.UNKNOWN, "no audio"));
            return;
        }
        // 途中で切れたのか最後まで鳴ったのかは、ここでは曲の尺を知らないので断定できない。
        // 断定できないものをチャットへ出す代わりに、鳴った長さをログへ残す (latest.log を貼れば
        // 「12 秒で終わった」と「3 分 33 秒鳴った」が区別できる)。
        MusicDiscMaker.LOGGER.info("Stream ended after {} ms of audio with no failure reported",
                playedMs(bytes));
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
        source.close();
    }
}
