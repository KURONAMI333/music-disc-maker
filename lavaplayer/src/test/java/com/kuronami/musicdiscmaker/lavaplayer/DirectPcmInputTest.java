package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

/**
 * 直 decode の {@link IAudioSource} (48kHz / mono / S16LE) の試験。
 *
 * <p>長さは resampler の実測に合わせる。48kHz 入力はそのまま 1 sample = 2 byte、
 * 44.1kHz 入力は 48kHz へ直す時に末尾が少し返らない (resampler の遅延分。libsamplerate で
 * 51 sample = 1.06ms、native を load できない環境の Java resampler で 17 sample)。
 * 差は 1 秒単位の「曲が途中で終わった」判定には影響しない。
 */
class DirectPcmInputTest {

    @Test
    void returnsTheWhole48kInputAsMonoS16() throws Exception {
        final byte[] pcm = drain(alac("a24.m4a", "direct-48k"));

        assertEquals(192_000, pcm.length, "2 秒 × 48kHz × mono × 2 byte");
    }

    @Test
    void resamplesA44kInputWithinOneMillisecondOfTheInputLength() throws Exception {
        final byte[] pcm = drain(alac("a16.m4a", "direct-44k"));

        assertTrue(isResampledLength(pcm.length), "44.1kHz → 48kHz の長さ: " + pcm.length + " byte");
    }

    /**
     * 2 秒の 44.1kHz 入力を 48kHz へ直した長さ。入力 (192000 byte) を超えず、resampler の
     * 遅延分 (libsamplerate の 191898 byte から 32 sample の余裕まで) より短くならない。
     */
    static boolean isResampledLength(int bytes) {
        return bytes <= 192_000 && bytes >= 191_898 - 64;
    }

    /** 全 byte が読め、終端の -1 の後に失敗理由が無い (最後まで鳴った)。 */
    @Test
    void endsCleanlyWithoutAPlaybackFault() throws Exception {
        final DirectPcmInput input = alac("a16.m4a", "direct-clean");
        final IAudioSource source = input.open();
        try {
            assertEquals(MusicLoaderImpl.SAMPLE_RATE, source.sampleRate());
            assertEquals(1, source.channels());
            assertEquals(16, source.bitsPerSample());
            assertFalse(source.bigEndian());
            drainInto(source, new ByteArrayOutputStream());
            assertEquals(-1, source.read(new byte[64], 0, 64), "終端は -1 のまま");
            assertNull(source.playbackFault());
        } finally {
            source.close();
        }
    }

    /** 途中で close したら producer (daemon) が 1 秒以内に終わる。 */
    @Test
    void closeStopsTheProducerThread() throws Exception {
        final String name = "direct-close-test";
        final IAudioSource source = alac("a24.m4a", name).open();
        final ByteArrayOutputStream head = new ByteArrayOutputStream();
        final byte[] buffer = new byte[512];
        while (head.size() < 16_000) {
            final int read = source.read(buffer, 0, buffer.length);
            if (read > 0) {
                head.write(buffer, 0, read);
            } else {
                Thread.sleep(1L);
            }
        }
        assertTrue(isProducerAlive(name), "close の前に producer が居る");

        source.close();

        final long deadline = System.nanoTime() + 1_000_000_000L;
        while (isProducerAlive(name) && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertFalse(isProducerAlive(name), "close で producer が終わる");
        // close は冪等。
        source.close();
    }

    private static DirectPcmInput alac(String fixture, String name) throws Exception {
        final Path file = fixture(fixture);
        final AlacPcmDecoder.Info info = AlacPcmDecoder.probe(file);
        return new DirectPcmInput(new AlacPcmDecoder(file, info), info.durationMs(),
                PcmInput.Origin.ALAC, name);
    }

    private static byte[] drain(DirectPcmInput input) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final IAudioSource source = input.open();
        try {
            drainInto(source, out);
            assertNull(source.playbackFault());
        } finally {
            source.close();
        }
        return out.toByteArray();
    }

    private static void drainInto(IAudioSource source, ByteArrayOutputStream out) throws Exception {
        final byte[] buffer = new byte[8_192];
        final long deadline = System.nanoTime() + 60_000_000_000L;
        while (true) {
            final int read = source.read(buffer, 0, buffer.length);
            if (read < 0) {
                return;
            }
            if (read > 0) {
                out.write(buffer, 0, read);
            } else {
                assertTrue(System.nanoTime() < deadline, "decode が終わらない");
                Thread.sleep(1L);
            }
        }
    }

    private static boolean isProducerAlive(String name) {
        final String threadName = "mdm-local-decode-" + name;
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && threadName.equals(thread.getName()));
    }

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                DirectPcmInputTest.class.getResource("/local-import/" + name),
                "missing fixture " + name).toURI());
    }
}
