package com.sedmelluq.discord.lavaplayer.natives.opus;

import com.kuronami.musicdiscmaker.lavaplayer.codec.JavaOpusEncoder;
import com.kuronami.musicdiscmaker.lavaplayer.codec.NativeCodecs;
import com.sedmelluq.lava.common.natives.NativeResourceHolder;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * A wrapper around the native methods of OpusEncoderLibrary.
 *
 * <p>Music Disc Maker: replaces LavaPlayer's class of the same name (excluded from the bundled jar). Falls back to
 * {@link JavaOpusEncoder} when the native connector library cannot be loaded.
 */
public class OpusEncoder extends NativeResourceHolder {
    private final OpusEncoderLibrary library;
    private final long instance;
    private final JavaOpusEncoder fallback;

    /**
     * @param sampleRate Input sample rate
     * @param channels   Channel count
     * @param quality    Encoding quality (0-10)
     */
    public OpusEncoder(int sampleRate, int channels, int quality) {
        if (!NativeCodecs.available()) {
            library = null;
            instance = 0;
            fallback = new JavaOpusEncoder(sampleRate, channels, quality);
            return;
        }

        fallback = null;
        library = OpusEncoderLibrary.getInstance();
        instance = library.create(sampleRate, channels, OpusEncoderLibrary.APPLICATION_AUDIO, quality);

        if (instance == 0) {
            throw new IllegalStateException("Failed to create an encoder instance");
        }
    }

    /**
     * Encode the input buffer to output.
     *
     * @param directInput  Input sample buffer
     * @param frameSize    Number of samples per channel
     * @param directOutput Output byte buffer
     * @return Number of bytes written to the output
     */
    public int encode(ShortBuffer directInput, int frameSize, ByteBuffer directOutput) {
        checkNotReleased();

        if (!directInput.isDirect() || !directOutput.isDirect()) {
            throw new IllegalArgumentException("Arguments must be direct buffers.");
        }

        directOutput.clear();
        int result = fallback != null
            ? fallback.encode(directInput, frameSize, directOutput)
            : library.encode(instance, directInput, frameSize, directOutput, directOutput.capacity());

        if (result < 0) {
            throw new IllegalStateException("Encoding failed with error " + result);
        }

        directOutput.position(result);
        directOutput.flip();

        return result;
    }

    @Override
    protected void freeResources() {
        if (library != null) {
            library.destroy(instance);
        }
    }
}
