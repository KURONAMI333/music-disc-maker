package com.kuronami.musicdiscmaker.client.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import javax.sound.sampled.AudioFormat;


/**
 * Resource-pack resolved vanilla PCMをSpeaker用に整えるstream adapter。
 *
 * <p>開始offsetまでは小さいchunkでframe単位に読み捨てる。delegateが要求量より多く返した場合は
 * 境界以降を保持するので、最初に返すsampleを失わない。stereo signed-16 PCMは左右平均のmonoへ
 * streaming変換し、全曲をメモリへ展開しない。
 */
public final class VanillaSpeakerPcmStream implements AutoCloseable {
    /** Pull-based decoded PCM, independent of Minecraft's client classes. */
    public interface Input extends AutoCloseable {
        AudioFormat getFormat();
        ByteBuffer read(int requestedBytes) throws IOException;
        @Override void close() throws IOException;
    }


    private static final int READ_CHUNK_BYTES = 64 * 1024;
    private static final ByteBuffer EMPTY = ByteBuffer.allocateDirect(0);
    private static final float GAIN_SMOOTHING = 1.0F / 2048.0F;
    private static final float GAIN_EPSILON = 1.0E-4F;
    private static final float LIMIT_KNEE = 0.8F;

    private final Input delegate;
    private final AudioFormat inputFormat;
    private final AudioFormat outputFormat;
    private final int inputFrameSize;
    private final BooleanSupplier cancelled;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final byte[] frame;

    private ByteBuffer pending = EMPTY;
    private int frameFill;
    private boolean eof;
    private volatile float pcmGain = 1.0F;
    private float appliedGain = 1.0F;

    private VanillaSpeakerPcmStream(Input delegate, long offsetMs, BooleanSupplier cancelled)
            throws IOException {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.cancelled = Objects.requireNonNull(cancelled, "cancelled");
        this.inputFormat = requireSupported(delegate.getFormat());
        this.inputFrameSize = inputFormat.getFrameSize();
        this.frame = new byte[inputFrameSize];
        this.outputFormat = inputFormat.getChannels() == 1 ? inputFormat : new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                inputFormat.getSampleRate(),
                16,
                1,
                2,
                inputFormat.getFrameRate(),
                inputFormat.isBigEndian());
        checkCancelled();
        skipOffset(offsetMs);
        checkCancelled();
    }

    /**
     * decoder生成後の読み飛ばしを指定executorで行う。Sound engine threadから呼ぶ前にfutureを完成させる。
     */
    public static CompletableFuture<VanillaSpeakerPcmStream> prepareAsync(Input delegate, long offsetMs,
            BooleanSupplier cancelled, Executor executor) {
        Objects.requireNonNull(executor, "executor");
        try {
            return CompletableFuture.supplyAsync(() -> {
            VanillaSpeakerPcmStream stream = null;
            try {
                stream = new VanillaSpeakerPcmStream(delegate, offsetMs, cancelled);
                return stream;
            } catch (final Throwable failure) {
                if (stream != null) {
                    closeAfterFailure(stream, failure);
                } else {
                    closeAfterFailure(delegate, failure);
                }
                if (failure instanceof CancellationException cancelledFailure) {
                    throw cancelledFailure;
                }
                throw new CompletionException(failure);
            }
            }, executor);
        } catch (final RuntimeException rejected) {
            closeAfterFailure(delegate, rejected);
            return CompletableFuture.failedFuture(rejected);
        }
    }

    private static void closeAfterFailure(AutoCloseable closeable, Throwable failure) {
        try {
            closeable.close();
        } catch (final Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static AudioFormat requireSupported(AudioFormat format) {
        Objects.requireNonNull(format, "delegate format");
        final int channels = format.getChannels();
        final float sampleRate = format.getSampleRate();
        final float frameRate = format.getFrameRate();
        if (!AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                || format.getSampleSizeInBits() != 16
                || (channels != 1 && channels != 2)
                || format.getFrameSize() != channels * 2
                || !Float.isFinite(sampleRate) || sampleRate <= 0.0F
                || !Float.isFinite(frameRate) || frameRate <= 0.0F) {
            throw new IllegalArgumentException("Unsupported vanilla speaker PCM format: " + format);
        }
        return format;
    }

    private void skipOffset(long offsetMs) throws IOException {
        long remaining = offsetBytes(offsetMs, inputFormat.getFrameRate(), inputFrameSize);
        while (remaining > 0L) {
            checkCancelled();
            if (!pending.hasRemaining()) {
                final int request = (int) Math.min(READ_CHUNK_BYTES, remaining);
                pending = nextBuffer(Math.max(inputFrameSize, request));
                if (!pending.hasRemaining()) {
                    eof = true;
                    return;
                }
                checkCancelled();
            }
            final int consumed = (int) Math.min(remaining, pending.remaining());
            pending.position(pending.position() + consumed);
            remaining -= consumed;
        }
        pending = pending.hasRemaining() ? pending.slice() : EMPTY;
    }

    private static long offsetBytes(long offsetMs, float frameRate, int frameSize) {
        if (offsetMs <= 0L) {
            return 0L;
        }
        final double framesValue = Math.floor(offsetMs * (double) frameRate / 1000.0D);
        final long maxFrames = Long.MAX_VALUE / frameSize;
        final long frames = !Double.isFinite(framesValue) || framesValue >= maxFrames
                ? maxFrames : (long) framesValue;
        return frames * frameSize;
    }

    private ByteBuffer nextBuffer(int requestedBytes) throws IOException {
        final ByteBuffer read = delegate.read(requestedBytes);
        return read == null ? EMPTY : read.slice();
    }

    private void checkCancelled() {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("vanilla speaker stream preparation cancelled");
        }
    }

    public AudioFormat getFormat() {
        return outputFormat;
    }

    /** OpenALの1.0上限を越える分をPCMへ適用する。client tickから更新できる。 */
    public void setPcmGain(float gain) {
        this.pcmGain = Float.isFinite(gain) && gain > 0.0F ? gain : 0.0F;
    }

    public synchronized ByteBuffer read(int size) throws IOException {
        final int outputBytes = Math.max(0, size - Math.floorMod(size, 2));
        if (outputBytes == 0 || closed.get() || cancelled.getAsBoolean()
                || (eof && !pending.hasRemaining() && frameFill == 0)) {
            return EMPTY;
        }
        final ByteBuffer output = ByteBuffer.allocateDirect(outputBytes);
        while (output.remaining() >= 2 && readFrame()) {
            if (inputFormat.getChannels() == 1) {
                writeMono(output, applyGain(sampleAt(0)), inputFormat.isBigEndian());
            } else {
                writeMono(output, applyGain(average(sampleAt(0), sampleAt(2))), inputFormat.isBigEndian());
            }
            frameFill = 0;
        }
        output.flip();
        return output;
    }

    private boolean readFrame() throws IOException {
        while (frameFill < inputFrameSize) {
            if (closed.get() || cancelled.getAsBoolean()) return false;
            if (!pending.hasRemaining()) {
                if (eof) {
                    return false;
                }
                pending = nextBuffer(READ_CHUNK_BYTES);
                if (!pending.hasRemaining()) {
                    eof = true;
                    return false;
                }
            }
            final int count = Math.min(inputFrameSize - frameFill, pending.remaining());
            pending.get(frame, frameFill, count);
            frameFill += count;
        }
        return true;
    }

    private int sampleAt(int offset) {
        if (inputFormat.isBigEndian()) {
            return (short) ((frame[offset] << 8) | (frame[offset + 1] & 0xFF));
        }
        return (short) ((frame[offset] & 0xFF) | (frame[offset + 1] << 8));
    }

    private static int average(int left, int right) {
        return (left + right) / 2;
    }

    private int applyGain(int sample) {
        final float target = pcmGain;
        float gain = appliedGain;
        if (target == 1.0F && gain == 1.0F) {
            return sample;
        }
        gain += (target - gain) * GAIN_SMOOTHING;
        if (Math.abs(target - gain) < GAIN_EPSILON) {
            gain = target;
        }
        appliedGain = gain;
        return limit(sample / 32768.0F * gain);
    }

    private static int limit(float sample) {
        final float absolute = Math.abs(sample);
        float limited = absolute;
        if (absolute > LIMIT_KNEE) {
            final float excess = (absolute - LIMIT_KNEE) / (1.0F - LIMIT_KNEE);
            limited = LIMIT_KNEE + (1.0F - LIMIT_KNEE) * (excess / (1.0F + excess));
        }
        final int quantized = Math.round((sample < 0.0F ? -limited : limited) * 32767.0F);
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, quantized));
    }

    private static void writeMono(ByteBuffer output, int sample, boolean bigEndian) {
        if (bigEndian) {
            output.put((byte) (sample >> 8)).put((byte) sample);
        } else {
            output.put((byte) sample).put((byte) (sample >> 8));
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            pending = EMPTY;
            delegate.close();
        }
    }
}
