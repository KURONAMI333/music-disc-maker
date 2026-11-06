package com.sedmelluq.discord.lavaplayer.natives.vorbis;

import com.kuronami.musicdiscmaker.lavaplayer.codec.JavaVorbisDecoder;
import com.kuronami.musicdiscmaker.lavaplayer.codec.NativeCodecs;
import com.sedmelluq.lava.common.natives.NativeResourceHolder;

import java.nio.ByteBuffer;

/**
 * A wrapper around the native methods of AacDecoder, which uses libvorbis native library.
 *
 * <p>Music Disc Maker: replaces LavaPlayer's class of the same name (excluded from the bundled jar). Falls back to
 * {@link JavaVorbisDecoder} when the native connector library cannot be loaded.
 */
public class VorbisDecoder extends NativeResourceHolder {
    private final VorbisDecoderLibrary library;
    private final long instance;
    private final JavaVorbisDecoder fallback;
    private int channelCount = 0;

    /**
     * Create an instance.
     */
    public VorbisDecoder() {
        if (!NativeCodecs.available()) {
            library = null;
            instance = 0;
            fallback = new JavaVorbisDecoder();
            return;
        }

        fallback = null;
        library = VorbisDecoderLibrary.getInstance();
        instance = library.create();
    }

    /**
     * Initialize the decoder by passing in identification and setup header data. See
     * https://xiph.org/vorbis/doc/Vorbis_I_spec.html#x1-170001.2.6 for definitions. The comment header is not required as
     * it is not actually used for decoding setup.
     *
     * @param infoBuffer  Identification header, including the 'vorbis' string.
     * @param setupBuffer Setup header (also known as codebook header), including the 'vorbis' string.
     */
    public void initialise(ByteBuffer infoBuffer, ByteBuffer setupBuffer) {
        checkNotReleased();

        if (!infoBuffer.isDirect() || !setupBuffer.isDirect()) {
            throw new IllegalArgumentException("Buffer argument must be a direct buffer.");
        }

        if (fallback != null) {
            if (!fallback.initialise(infoBuffer, setupBuffer)) {
                throw new IllegalStateException("Could not initialise library.");
            }

            channelCount = fallback.getChannelCount();
            return;
        }

        if (!library.initialise(instance, infoBuffer, infoBuffer.position(), infoBuffer.remaining(), setupBuffer,
            setupBuffer.position(), setupBuffer.remaining())) {

            throw new IllegalStateException("Could not initialise library.");
        }

        channelCount = library.getChannelCount(instance);
    }

    /**
     * Get the number of channels, valid only after initialisation.
     *
     * @return Number of channels
     */
    public int getChannelCount() {
        return channelCount;
    }

    /**
     * Provide input for the decoder
     *
     * @param buffer Buffer with the input
     */
    public void input(ByteBuffer buffer) {
        checkNotReleased();

        if (!buffer.isDirect()) {
            throw new IllegalArgumentException("Buffer argument must be a direct buffer.");
        }

        if (fallback != null) {
            fallback.input(buffer);
            buffer.position(buffer.position() + buffer.remaining());
            return;
        }

        int result = library.input(instance, buffer, buffer.position(), buffer.remaining());
        buffer.position(buffer.position() + buffer.remaining());

        if (result != 0) {
            throw new IllegalStateException("Passing input failed with error " + result + ".");
        }
    }

    /**
     * Fetch output from the decoder
     *
     * @param channels Channel buffers to fetch the output to
     * @return The number of samples fetched for each channel
     */
    public int output(float[][] channels) {
        checkNotReleased();

        if (channels.length != channelCount) {
            throw new IllegalStateException("Invalid channel float buffer length");
        }

        if (fallback != null) {
            return fallback.output(channels);
        }

        int result = library.output(instance, channels, channels[0].length);

        if (result < 0) {
            throw new IllegalStateException("Retrieving output failed");
        }

        return result;
    }

    @Override
    protected void freeResources() {
        if (library != null) {
            library.destroy(instance);
        }
    }
}
