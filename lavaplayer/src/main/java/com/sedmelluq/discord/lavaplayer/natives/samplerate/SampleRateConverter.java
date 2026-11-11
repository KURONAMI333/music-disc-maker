package com.sedmelluq.discord.lavaplayer.natives.samplerate;

import com.kuronami.musicdiscmaker.lavaplayer.codec.JavaResampler;
import com.kuronami.musicdiscmaker.lavaplayer.codec.NativeCodecs;
import com.sedmelluq.lava.common.natives.NativeResourceHolder;

/**
 * Sample rate converter backed by libsamplerate
 *
 * <p>Music Disc Maker: replaces LavaPlayer's class of the same name (excluded from the bundled jar). Falls back to
 * {@link JavaResampler} (mono only, which is how {@code ResamplingPcmAudioFilter} uses it) when the native connector
 * library cannot be loaded.
 */
public class SampleRateConverter extends NativeResourceHolder {
    private final SampleRateLibrary library;
    private final double ratio;
    private final long instance;
    private final JavaResampler fallback;

    /**
     * @param type       Resampling type
     * @param channels   Number of channels
     * @param sourceRate Source sample rate
     * @param targetRate Target sample rate
     */
    public SampleRateConverter(ResamplingType type, int channels, int sourceRate, int targetRate) {
        ratio = (double) targetRate / (double) sourceRate;

        if (!NativeCodecs.available()) {
            if (channels != 1) {
                throw new IllegalArgumentException("The Java sample rate converter only supports one channel.");
            }

            library = null;
            instance = 0;
            fallback = new JavaResampler(type.ordinal(), sourceRate, targetRate);
            return;
        }

        fallback = null;
        library = SampleRateLibrary.getInstance();
        instance = library.create(type.ordinal(), channels);

        if (instance == 0) {
            throw new IllegalStateException("Could not create an instance of sample rate converter.");
        }
    }

    /**
     * Reset the converter, makes sure previous data does not affect next incoming data
     */
    public void reset() {
        checkNotReleased();

        if (fallback != null) {
            fallback.reset();
        } else {
            library.reset(instance);
        }
    }

    /**
     * @param input        Input buffer
     * @param inputOffset  Offset for input buffer
     * @param inputLength  Length for input buffer
     * @param output       Output buffer
     * @param outputOffset Offset for output buffer
     * @param outputLength Length for output buffer
     * @param endOfInput   If this is the last piece of input
     * @param progress     Instance that is filled with the progress
     */
    public void process(float[] input, int inputOffset, int inputLength, float[] output, int outputOffset, int outputLength, boolean endOfInput, Progress progress) {
        checkNotReleased();

        if (fallback != null) {
            fallback.process(input, inputOffset, inputLength, output, outputOffset, outputLength, progress.fields);
            return;
        }

        int error = library.process(instance, input, inputOffset, inputLength, output, outputOffset, outputLength, endOfInput, ratio, progress.fields);

        if (error != 0) {
            throw new RuntimeException("Failed to convert sample rate, error " + error + ".");
        }
    }

    @Override
    protected void freeResources() {
        if (library != null) {
            library.destroy(instance);
        }
    }

    /**
     * Progress of converting one piece of data
     */
    public static class Progress {
        private final int[] fields = new int[2];

        /**
         * @return Number of samples used from the input buffer
         */
        public int getInputUsed() {
            return fields[0];
        }

        /**
         * @return Number of samples written to the output buffer
         */
        public int getOutputGenerated() {
            return fields[1];
        }
    }

    /**
     * Available resampling types
     */
    public enum ResamplingType {
        SINC_BEST_QUALITY,
        SINC_MEDIUM_QUALITY,
        SINC_FASTEST,
        ZERO_ORDER_HOLD,
        LINEAR
    }
}
