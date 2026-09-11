package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import javax.sound.sampled.AudioFormat;

import org.junit.jupiter.api.Test;



class VanillaSpeakerAudioStreamTest {

    private static final BooleanSupplier NEVER_CANCELLED = () -> false;

    @Test
    void monoOffsetKeepsOverreadAfterTheExactFrameBoundary() throws Exception {
        final FakeStream source = new FakeStream(format(1, false), shorts(false, 100, 200, 300), 64);
        final VanillaSpeakerPcmStream stream = prepared(source, 1L, NEVER_CANCELLED);

        assertArrayEquals(shorts(false, 200, 300), bytes(stream.read(32)));
        assertEquals(1, stream.getFormat().getChannels());
    }

    @Test
    void stereoLittleEndianIsDownmixedWhileStreaming() throws Exception {
        final FakeStream source = new FakeStream(format(2, false),
                shorts(false, 1000, -500, 32767, 32767), 3);
        final VanillaSpeakerPcmStream stream = prepared(source, 0L, NEVER_CANCELLED);

        assertArrayEquals(shorts(false, 250, 32767), bytes(stream.read(4)));
        assertEquals(1, stream.getFormat().getChannels());
        assertEquals(2, stream.getFormat().getFrameSize());
    }

    @Test
    void stereoBigEndianOffsetAndPartialFramesStayAligned() throws Exception {
        final FakeStream source = new FakeStream(format(2, true),
                shorts(true, 900, 100, -2000, 1000, 300, 700), 1);
        final VanillaSpeakerPcmStream stream = prepared(source, 1L, NEVER_CANCELLED);

        assertArrayEquals(shorts(true, -500, 500), bytes(stream.read(5)));
        assertEquals(true, stream.getFormat().isBigEndian());
    }

    @Test
    void negativeOffsetStartsAtFirstFrame() throws Exception {
        final VanillaSpeakerPcmStream stream = prepared(
                new FakeStream(format(1, false), shorts(false, 7, 8), 8), -99L, NEVER_CANCELLED);
        assertArrayEquals(shorts(false, 7, 8), bytes(stream.read(4)));
    }

    @Test
    void eofBeforeHugeOrOverflowingOffsetReturnsEmptyWithoutAllocatingTheTrack() throws Exception {
        final FakeStream source = new FakeStream(format(2, false), shorts(false, 1, 2), 4);
        final VanillaSpeakerPcmStream stream = prepared(source, Long.MAX_VALUE, NEVER_CANCELLED);
        assertEquals(0, stream.read(Integer.MAX_VALUE).remaining());
        assertEquals(2, source.readCalls.get(), "EOF後もoffsetを満たそうと読み続けている");
    }

    @Test
    void cancellationIsCheckedBetweenSkipChunksAndClosesOnce() {
        final FakeStream source = new FakeStream(format(1, false), shorts(false, 1, 2, 3, 4), 2);
        final AtomicInteger checks = new AtomicInteger();
        final CompletionException failure = assertThrows(CompletionException.class,
                () -> prepared(source, 4L, () -> checks.incrementAndGet() >= 3));
        assertInstanceOf(CancellationException.class, failure.getCause());
        assertEquals(1, source.closeCalls.get());
    }

    @Test
    void rejectsUnsupportedPcmAndCloseIsIdempotent() throws Exception {
        final AudioFormat[] invalid = {
                new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, 1000, 16, 1, 2, 1000, false),
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 1000, 8, 1, 1, 1000, false),
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 1000, 16, 3, 6, 1000, false),
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 1000, 16, 2, 2, 1000, false),
                new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 1000, 16, 1, 2, -1, false)
        };
        for (AudioFormat format : invalid) {
            final FakeStream rejected = new FakeStream(format, new byte[0], 8);
            assertInstanceOf(IllegalArgumentException.class,
                    assertThrows(CompletionException.class,
                            () -> prepared(rejected, 0L, NEVER_CANCELLED)).getCause());
            assertEquals(1, rejected.closeCalls.get());
        }

        final FakeStream stereo = new FakeStream(format(2, false), shorts(false, 1, 1), 8);
        final VanillaSpeakerPcmStream stream = prepared(stereo, 0L, NEVER_CANCELLED);
        stream.close();
        stream.close();
        assertEquals(1, stereo.closeCalls.get());
    }

    @Test
    void livePcmGainRisesSmoothlyWithoutOverflowingSigned16() throws Exception {
        final int[] samples = new int[4096];
        java.util.Arrays.fill(samples, 20_000);
        final VanillaSpeakerPcmStream stream = (VanillaSpeakerPcmStream) prepared(
                new FakeStream(format(1, false), shorts(false, samples), 8192), 0L, NEVER_CANCELLED);
        stream.setPcmGain(2.0F);

        final byte[] amplified = bytes(stream.read(samples.length * 2));
        final int first = littleEndianSample(amplified, 0);
        final int last = littleEndianSample(amplified, amplified.length - 2);
        assertEquals(true, first >= 20_000, "gain更新の先頭で逆に減衰している");
        assertEquals(true, last > first, "live gainへ滑らかに追従していない");
        assertEquals(true, last <= Short.MAX_VALUE, "signed16をoverflowしている");
    }

    @Test
    void rejectedPreparationClosesDelegate() {
        final FakeStream source = new FakeStream(format(1, false), shorts(false, 42), 2);
        final var future = VanillaSpeakerPcmStream.prepareAsync(source, 0L, NEVER_CANCELLED,
                task -> { throw new java.util.concurrent.RejectedExecutionException("closed executor"); });
        org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.CompletionException.class, future::join);
        assertEquals(1, source.closeCalls.get());
    }

    @Test
    void cancellationAfterPreparationEndsReadWithoutThrowingOnSoundThread() throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        final FakeStream source = new FakeStream(format(1, false), shorts(false, 42, 43), 4);
        final VanillaSpeakerPcmStream stream = prepared(source, 0L, cancelled::get);
        cancelled.set(true);
        assertEquals(0, stream.read(4).remaining());
        stream.close();
        assertEquals(1, source.closeCalls.get());
    }

    private static VanillaSpeakerPcmStream prepared(FakeStream source, long offsetMs, BooleanSupplier cancelled) {
        return VanillaSpeakerPcmStream.prepareAsync(source, offsetMs, cancelled, Runnable::run).join();
    }

    private static AudioFormat format(int channels, boolean bigEndian) {
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 1000, 16, channels,
                channels * 2, 1000, bigEndian);
    }

    private static byte[] shorts(boolean bigEndian, int... samples) {
        final byte[] result = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            if (bigEndian) {
                result[i * 2] = (byte) (samples[i] >> 8);
                result[i * 2 + 1] = (byte) samples[i];
            } else {
                result[i * 2] = (byte) samples[i];
                result[i * 2 + 1] = (byte) (samples[i] >> 8);
            }
        }
        return result;
    }

    private static byte[] bytes(ByteBuffer buffer) {
        final byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    private static int littleEndianSample(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 0xFF) | (bytes[offset + 1] << 8));
    }

    private static final class FakeStream implements VanillaSpeakerPcmStream.Input {
        private final AudioFormat format;
        private final byte[] data;
        private final int maxChunk;
        private int position;
        private final AtomicInteger readCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();

        private FakeStream(AudioFormat format, byte[] data, int maxChunk) {
            this.format = format;
            this.data = data;
            this.maxChunk = maxChunk;
        }

        @Override
        public AudioFormat getFormat() {
            return format;
        }

        @Override
        public ByteBuffer read(int requested) {
            readCalls.incrementAndGet();
            if (position >= data.length) {
                return ByteBuffer.allocate(0);
            }
            // requestedを越すfakeも許し、adapterがoffset境界後を保持することを検証する。
            final int count = Math.min(maxChunk, data.length - position);
            final ByteBuffer result = ByteBuffer.wrap(data, position, count).slice();
            position += count;
            return result;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }
}
