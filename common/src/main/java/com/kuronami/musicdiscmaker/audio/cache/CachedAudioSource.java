package com.kuronami.musicdiscmaker.audio.cache;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.IOpusDecoder;

/**
 * キャッシュファイルから PCM を供給する {@link IAudioSource}。ネットワークには一切触らない
 * = 外部プラットフォームが壊れていてもこの経路だけで鳴る (これが柱5 の本体)。
 *
 * <p>{@code LavaPlayerAudioStream} 以降は元のネットワーク経路とまったく同じものを見る
 * (PCM bytes と format だけの interface なので、供給元が差し替わったことに気づかない)。
 *
 * <h2>シーク</h2>
 * 20ms 固定フレームなので「先頭から {@code startMs / 20} フレームぶんを読み飛ばす」だけで
 * 位置が決まる。読み飛ばしはローカルファイルの長さ読み + skip なので、5 分の曲でも 15,000 回の
 * 2 byte 読みで済む (実時間では無視できる)。索引を持たない分、壊れ方も減る。
 */
public final class CachedAudioSource implements IAudioSource {

    private final Path file;
    private final InputStream in;
    private final IOpusDecoder decoder;
    private final AudioCacheFormat.Header header;
    private final byte[] packet = new byte[AudioCacheFormat.MAX_PACKET_BYTES];
    private final short[] samples;
    /** デコード済みで未引き渡しの PCM (S16LE)。 */
    private final byte[] pending;
    private int pendingPos;
    private int pendingLen;
    private boolean ended;
    private boolean closed;

    private CachedAudioSource(Path file, InputStream in, IOpusDecoder decoder,
            AudioCacheFormat.Header header) {
        this.file = file;
        this.in = in;
        this.decoder = decoder;
        this.header = header;
        // Opus は 1 パケットで最長 120ms を返しうる。書き手は 20ms 固定だが、読み手は上限で構える。
        this.samples = new short[5760 * header.channels()];
        this.pending = new byte[samples.length * 2];
    }

    /**
     * キャッシュファイルを開いて {@code startMs} まで進める。
     *
     * @return ソース。開けない / 壊れている / decoder が無い時は {@code null}
     *         (壊れていたファイルはその場で削除し、次回はネットワークから取り直す)
     */
    @Nullable
    public static CachedAudioSource open(Path file, @Nullable IOpusDecoder decoder, long startMs) {
        if (decoder == null) {
            return null;
        }
        InputStream in = null;
        try {
            in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
            final AudioCacheFormat.Header header = AudioCacheFormat.readHeader(in);
            if (header == null) {
                throw new IOException("ヘッダが読めない (形式違い or 破損)");
            }
            final CachedAudioSource source = new CachedAudioSource(file, in, decoder, header);
            source.skipTo(startMs);
            return source;
        } catch (final IOException | RuntimeException ex) {
            closeQuietly(in);
            decoder.close();
            MusicDiscMaker.LOGGER.warn("音源キャッシュが読めないので捨てる ({}): {}", file, ex.toString());
            deleteQuietly(file);
            return null;
        }
    }

    /** 先頭から数えて {@code startMs} 地点のフレームまで読み飛ばす。 */
    private void skipTo(long startMs) throws IOException {
        if (startMs <= 0L) {
            return;
        }
        final long frames = startMs / header.frameDurationMs();
        for (long i = 0; i < frames; i++) {
            final int length = AudioCacheFormat.readFrameLength(in);
            if (length < 0) {
                ended = true; // 要求位置がファイル末尾より後ろ = 何も鳴らない (曲が終わっている)
                return;
            }
            long left = length;
            while (left > 0) {
                final long skipped = in.skip(left);
                if (skipped <= 0) {
                    if (in.read() < 0) {
                        ended = true;
                        return;
                    }
                    left--;
                } else {
                    left -= skipped;
                }
            }
        }
    }

    @Override
    public int sampleRate() {
        return header.sampleRate();
    }

    @Override
    public int channels() {
        return header.channels();
    }

    @Override
    public int bitsPerSample() {
        return AudioCacheFormat.BITS_PER_SAMPLE;
    }

    @Override
    public boolean bigEndian() {
        return false;
    }

    @Override
    public int read(byte[] dst, int off, int len) {
        if (closed) {
            return -1;
        }
        int written = 0;
        while (written < len) {
            if (pendingPos >= pendingLen && !decodeNextFrame()) {
                break;
            }
            final int n = Math.min(len - written, pendingLen - pendingPos);
            System.arraycopy(pending, pendingPos, dst, off + written, n);
            pendingPos += n;
            written += n;
        }
        if (written == 0) {
            return -1; // ファイル終端 = 曲の終わり (ネットワーク経路と同じ意味)
        }
        return written;
    }

    /** 次のフレームを読んでデコードする。終端 / 破損なら {@code false}。 */
    private boolean decodeNextFrame() {
        if (ended) {
            return false;
        }
        try {
            final int length = AudioCacheFormat.readFrameLength(in);
            if (length < 0) {
                ended = true;
                return false;
            }
            if (!AudioCacheFormat.readFully(in, packet, 0, length)) {
                throw new IOException("パケットが途中で切れている");
            }
            final int decoded = decoder.decode(packet, 0, length, samples);
            if (decoded <= 0) {
                throw new IOException("Opus のデコードに失敗");
            }
            final int count = decoded * header.channels();
            for (int i = 0; i < count; i++) {
                final short s = samples[i];
                pending[i * 2] = (byte) (s & 0xFF);
                pending[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }
            pendingPos = 0;
            pendingLen = count * 2;
            return true;
        } catch (final IOException | RuntimeException ex) {
            // 途中で壊れているファイルは残さない (次回はネットワークから取り直す)。
            ended = true;
            MusicDiscMaker.LOGGER.warn("音源キャッシュが途中で壊れているので捨てる ({}): {}",
                    file, ex.toString());
            closeQuietly(in);
            deleteQuietly(file);
            return false;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeQuietly(in);
        decoder.close();
    }

    private static void closeQuietly(@Nullable InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (final IOException ignored) {
            // 閉じられなくても実害は無い
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (final IOException ignored) {
            // 読み出し中でロックされている等。次回消せる
        }
    }
}
