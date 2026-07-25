package com.kuronami.musicdiscmaker.client.audio;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.BufferUtils;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

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
     * 音が実際に鳴り始める直前に一度だけ呼ばれるコールバック (ビート連動の校正報告用)。
     * streaming thread から呼ばれるので、実装は重い処理を別スレッドへ逃がすこと。
     */
    @Nullable
    private final Runnable onStarted;
    private final AtomicBoolean startedNotified = new AtomicBoolean(false);
    private int readCalls;

    /**
     * {@code alSourcePlay} の前に MC が積むバッファ本数 ({@code Channel.QUEUED_BUFFER_COUNT})。
     *
     * <p>{@code SoundEngine} は {@code attachBufferStream} → {@code pumpBuffers(4)} → {@code play()}
     * の順で動くので、<b>4 回目の read が返った時点がほぼ実際の発音開始</b>。1 バッファ = 1 秒ぶんなので、
     * ここまでにネットワークから 4 秒ぶんの実データを読み終える必要があり、これが「server が再生を
     * 指示した時刻」と「音が出た時刻」の秒オーダーのずれの正体。
     *
     * <p>{@code SoundManager.play(instance)} の直後に報告する案は採らない。あれはキューに積むだけで、
     * バッファ充填の<b>前</b>に返るので、本来潰したい遅延ぶんそのまま早くずれる。
     */
    private static final int PREBUFFER_READS = 4;

    public LavaPlayerAudioStream(IAudioSource source) {
        this(source, null, null);
    }

    public LavaPlayerAudioStream(IAudioSource source, @Nullable Runnable onEnded) {
        this(source, onEnded, null);
    }

    public LavaPlayerAudioStream(IAudioSource source, @Nullable Runnable onEnded, @Nullable Runnable onStarted) {
        this.source = source;
        this.onEnded = onEnded;
        this.onStarted = onStarted;
        this.format = new AudioFormat(
                source.sampleRate(), source.bitsPerSample(), source.channels(), true, source.bigEndian());
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
                // ラジオの瞬断もここに来る (lavaplayer が track を終了させる) ので再接続を促す。
                if (onEnded != null && endedNotified.compareAndSet(false, true)) {
                    onEnded.run();
                }
                break;
            }
            if (read == 0) {
                break; // 取得できず (中断等) → 持ってる分を返す
            }
            buffer.put(scratch, 0, read);
        }
        buffer.flip();
        if (++readCalls >= PREBUFFER_READS && onStarted != null
                && startedNotified.compareAndSet(false, true)) {
            onStarted.run();
        }
        return buffer;
    }

    @Override
    public void close() {
        source.close();
    }
}
