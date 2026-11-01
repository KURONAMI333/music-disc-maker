package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.InterruptedIOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;

/**
 * 直 decode の PCM を再生側へ渡す pull 型ソース。48kHz / mono / 16bit LE を返す。
 *
 * <p>producer スレッド 1 本が {@link PcmDecoder} → {@link PcmConverter} →
 * {@link PcmQueue} を回し、{@link #read} は queue から取り出すだけで待たない
 * ({@code 0} = 今は無い、{@code -1} = 終端)。全曲を溜めないので長い入力でも常駐は queue 分だけ。
 *
 * <p>decode 中の失敗は {@link PlaybackFault} として残し、{@code read} は {@code -1} を返す。
 * 「最後まで鳴った」と「途中で落ちた」は戻り値では区別できないので、終端を見た側が
 * {@link #playbackFault()} を引く。{@link #close()} は冪等で、producer を中断して
 * decoder の資源 (file handle・pipe) を閉じさせる。
 */
final class DirectPcmSource implements IAudioSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(DirectPcmSource.class);

    private final PcmQueue queue;
    private final Thread producer;
    private volatile PlaybackFault fault;
    private byte[] current;
    private int currentOffset;
    private volatile boolean closed;

    DirectPcmSource(String name, PcmDecoder decoder, PcmConverter converter, PcmQueue queue) {
        this.queue = queue;
        this.producer = new Thread(() -> produce(decoder, converter), "mdm-local-decode-" + name);
        this.producer.setDaemon(true);
        this.producer.start();
    }

    private void produce(PcmDecoder decoder, PcmConverter converter) {
        try {
            decoder.decode(converter);
            converter.finish();
        } catch (final InterruptedException e) {
            // close() による中断。閉じた側が理由を持つので fault にはしない。
            Thread.currentThread().interrupt();
        } catch (final InterruptedIOException e) {
            // queue が閉じられた (close)。同じく fault にしない。
        } catch (final Throwable thrown) {
            if (!closed) {
                LOGGER.warn("Direct decode failed", thrown);
                fault = new PlaybackFault(FailureReason.UNKNOWN, describe(thrown));
            }
        } finally {
            // flush 後の出力は put 済みなので、native の resampler を閉じてから終端を記録する。
            // close が失敗しても終端は必ず記録する (read が待ち続けない)。
            try {
                converter.close();
            } catch (final Throwable closeFailure) {
                LOGGER.debug("closing the converter failed", closeFailure);
            } finally {
                queue.end();
            }
        }
    }

    /** 利用者に見せる短い詳細 (PlaybackFault が 1 行へ畳む)。 */
    private static String describe(Throwable thrown) {
        final String message = thrown.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return thrown.getClass().getSimpleName();
    }

    @Override
    public int sampleRate() {
        return MusicLoaderImpl.SAMPLE_RATE;
    }

    @Override
    public int channels() {
        return MusicLoaderImpl.CHANNELS;
    }

    @Override
    public int bitsPerSample() {
        return 16;
    }

    @Override
    public boolean bigEndian() {
        return false;
    }

    @Override
    public int read(byte[] dst, int off, int len) {
        if (len <= 0) {
            return 0;
        }
        int written = 0;
        while (written < len) {
            if (current == null || currentOffset >= current.length) {
                final byte[] next = queue.pollOrEnd();
                if (next == PcmQueue.END) {
                    // 空かつ終端を同じ lock で見た時だけ終端。書いた分があればそれを先に返す。
                    return written > 0 ? written : -1;
                }
                current = next;
                currentOffset = 0;
                if (current == null) {
                    break;
                }
            }
            final int copy = Math.min(len - written, current.length - currentOffset);
            System.arraycopy(current, currentOffset, dst, off + written, copy);
            currentOffset += copy;
            written += copy;
        }
        return written;
    }

    @Override
    public PlaybackFault playbackFault() {
        return fault;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        queue.close();
        producer.interrupt();
        try {
            producer.join(1_000L);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
