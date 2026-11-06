package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

/**
 * {@link DirectPcmSource#read} の終端判定と、producer の終了処理の順序。
 * producer は何もしない (止まったまま待つ) decoder にして、queue へ手で積む。
 */
class DirectPcmSourceTest {

    /** 中断されるまで止まっている decoder。 */
    private static final class IdleDecoder implements PcmDecoder {
        @Override
        public int sampleRate() {
            return 48_000;
        }

        @Override
        public int channels() {
            return 1;
        }

        @Override
        public void decode(PcmFrameSink sink) throws InterruptedException {
            new CountDownLatch(1).await();
        }
    }

    /** 何も変換しない converter。close の挙動だけ差し込める。 */
    private static class NoopConverter implements PcmConverter {
        @Override
        public void accept(short[] interleaved, int frames) {
        }

        @Override
        public void finish() throws IOException {
        }

        @Override
        public void close() {
        }
    }

    /**
     * 「空を見た後、終端を見る前」に producer が最後の chunk を積んで終端を記録した状況を、
     * ended() の中に差し込んで決定的に作る。read が poll() と ended() を別々に見る旧形だと、
     * ended() が true を返して終端 (-1) と誤認し chunk を捨てる。空と終端を同じ lock で見る
     * 現行形は ended() を使わないので、差し込みが起きず 0 (今は無い) を返す。
     */
    private static final class RacingQueue extends PcmQueue {
        @Override
        synchronized boolean ended() {
            try {
                put(new byte[] {1, 2, 3, 4});
            } catch (final java.io.InterruptedIOException e) {
                throw new IllegalStateException(e);
            }
            end();
            return super.ended();
        }
    }

    @Test
    void readNeverReportsTheEndWhileAChunkIsStillQueued() {
        final RacingQueue queue = new RacingQueue();
        final DirectPcmSource source = new DirectPcmSource("race", new IdleDecoder(), new NoopConverter(), queue);
        try {
            final byte[] dst = new byte[16];
            final int result = source.read(dst, 0, dst.length);

            assertTrue(result != -1, "最後の chunk が残る間に終端を返してはいけない: " + result);
        } finally {
            source.close();
        }
    }

    @Test
    void readReturnsZeroWhileOpenThenTheBytesThenTheEnd() throws Exception {
        final PcmQueue queue = new PcmQueue();
        final DirectPcmSource source = new DirectPcmSource("read", new IdleDecoder(), new NoopConverter(), queue);
        try {
            final byte[] dst = new byte[16];
            assertEquals(0, source.read(dst, 0, dst.length), "空で未終端なら 0");

            queue.put(new byte[] {5, 6, 7});
            queue.end();

            assertEquals(3, source.read(dst, 0, dst.length), "書いた分を返す");
            assertEquals(-1, source.read(dst, 0, dst.length), "次の呼びで終端");
            assertEquals(-1, source.read(dst, 0, dst.length), "終端は続く");
        } finally {
            source.close();
        }
    }

    @Test
    void closedSourceReadsAsEnded() {
        final DirectPcmSource source = new DirectPcmSource("closed", new IdleDecoder(), new NoopConverter(), new PcmQueue());
        source.close();

        assertEquals(-1, source.read(new byte[8], 0, 8));
    }

    /** converter の close が例外を投げても、queue.end() は呼ばれて read が終端に届く。 */
    @Test
    void endsTheQueueEvenWhenClosingTheConverterThrows() throws Exception {
        final CountDownLatch closeCalled = new CountDownLatch(1);
        final PcmConverter throwing = new NoopConverter() {
            @Override
            public void close() {
                closeCalled.countDown();
                throw new IllegalStateException("native close failed");
            }
        };
        final PcmDecoder immediate = new PcmDecoder() {
            @Override
            public int sampleRate() {
                return 48_000;
            }

            @Override
            public int channels() {
                return 1;
            }

            @Override
            public void decode(PcmFrameSink sink) {
            }
        };
        final DirectPcmSource source = new DirectPcmSource("close-throws", immediate, throwing, new PcmQueue());
        try {
            assertTrue(closeCalled.await(5, TimeUnit.SECONDS), "close が呼ばれる");
            final byte[] dst = new byte[8];
            final long deadline = System.nanoTime() + 5_000_000_000L;
            int read = source.read(dst, 0, dst.length);
            while (read == 0 && System.nanoTime() < deadline) {
                Thread.sleep(2L);
                read = source.read(dst, 0, dst.length);
            }
            assertEquals(-1, read, "close が失敗しても終端が記録される");
        } finally {
            source.close();
        }
    }

    @Test
    void closesTheConverterAfterAFailedDecode() throws Exception {
        final CountDownLatch closed = new CountDownLatch(1);
        final PcmConverter converter = new NoopConverter() {
            @Override
            public void close() {
                closed.countDown();
            }
        };
        final PcmDecoder failing = new PcmDecoder() {
            @Override
            public int sampleRate() {
                return 48_000;
            }

            @Override
            public int channels() {
                return 1;
            }

            @Override
            public void decode(PcmFrameSink sink) throws IOException {
                throw new IOException("broken");
            }
        };
        final IAudioSource source = new DirectPcmSource("failed", failing, converter, new PcmQueue());
        try {
            assertTrue(closed.await(5, TimeUnit.SECONDS), "失敗でも close が呼ばれる");
        } finally {
            source.close();
        }
    }
}
