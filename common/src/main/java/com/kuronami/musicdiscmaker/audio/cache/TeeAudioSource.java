package com.kuronami.musicdiscmaker.audio.cache;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

/**
 * ネットワーク再生の {@link IAudioSource} を包み、流れた PCM をそのままキャッシュへ写す decorator。
 *
 * <p><b>差し込む層をここにした理由</b>: {@code IAudioSource} は隔離 classloader をまたぐ唯一の口で、
 * バニラジューク・強化版ジューク・スピーカー・ブームボックス・Create/Aeronautics の移動構造物・
 * SB バックパックの<b>全経路がここを通る</b>。1 箇所包めば全経路が恩恵を受ける。
 *
 * <p>終端 (-1) の観測もここでやる。上位の {@code LavaPlayerAudioStream} は -1 を受けると
 * 短いバッファを返して抜けるので、-1 という事実は<b>この層でしか正確に見えない</b>。
 */
public final class TeeAudioSource implements IAudioSource {

    private final IAudioSource delegate;
    private final AudioCacheWriter writer;
    /** close は main thread ({@code requestStop}) と streaming スレッド (engine) の両方から来る。 */
    private volatile boolean closed;

    public TeeAudioSource(IAudioSource delegate, AudioCacheWriter writer) {
        this.delegate = delegate;
        this.writer = writer;
    }

    /** 元のソースの形式がキャッシュの形式と一致しているか (違えば包まない)。 */
    public static boolean formatMatches(IAudioSource source) {
        return source.sampleRate() == AudioCacheFormat.SAMPLE_RATE
                && source.channels() == AudioCacheFormat.CHANNELS
                && source.bitsPerSample() == AudioCacheFormat.BITS_PER_SAMPLE
                && !source.bigEndian();
    }

    @Override
    public int sampleRate() {
        return delegate.sampleRate();
    }

    @Override
    public int channels() {
        return delegate.channels();
    }

    @Override
    public int bitsPerSample() {
        return delegate.bitsPerSample();
    }

    @Override
    public boolean bigEndian() {
        return delegate.bigEndian();
    }

    @Override
    public int read(byte[] dst, int off, int len) {
        final int n = delegate.read(dst, off, len);
        if (n > 0) {
            writer.accept(dst, off, n);
        } else if (n < 0) {
            writer.endOfStream();
        }
        return n;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            delegate.close();
        } finally {
            writer.close();
        }
    }
}
