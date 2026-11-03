package com.kuronami.musicdiscmaker.lavaplayer.codec;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/** Concentus (pure-Java libopus 移植) による Opus decoder。native {@code OpusDecoderLibrary} の代わり。 */
public final class JavaOpusDecoder {

    private final OpusDecoder decoder;
    private final int channels;
    private byte[] packet = new byte[0];
    private short[] pcm = new short[0];

    public JavaOpusDecoder(int sampleRate, int channels) {
        try {
            this.decoder = new OpusDecoder(sampleRate, channels);
        } catch (final OpusException ex) {
            throw new IllegalStateException("Failed to create a decoder instance with sample rate "
                    + sampleRate + " and channel count " + channels, ex);
        }
        this.channels = channels;
    }

    /**
     * 1 packet を decode し、interleaved PCM を {@code output} の先頭から書く。
     *
     * @return channel あたりの sample 数
     */
    public int decode(ByteBuffer input, ShortBuffer output, int maxFrameSize) {
        final int length = input.remaining();
        if (packet.length < length) {
            packet = new byte[length];
        }
        input.duplicate().get(packet, 0, length);
        if (pcm.length < maxFrameSize * channels) {
            pcm = new short[maxFrameSize * channels];
        }
        final int samples;
        try {
            samples = decoder.decode(packet, 0, length, pcm, 0, maxFrameSize, false);
        } catch (final OpusException ex) {
            throw new IllegalStateException("Decoding failed: " + ex.getMessage(), ex);
        }
        output.put(0, pcm, 0, samples * channels);
        return samples;
    }
}
