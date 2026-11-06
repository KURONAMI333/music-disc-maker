package com.kuronami.musicdiscmaker.lavaplayer.codec;

import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/** Concentus による Opus encoder。native {@code OpusEncoderLibrary} の代わり。 */
public final class JavaOpusEncoder {

    private final OpusEncoder encoder;
    private final int channels;
    private short[] pcm = new short[0];
    private byte[] packet = new byte[0];

    /**
     * @param quality native 側と同じく complexity (0-10) として扱う。範囲外は native の
     *                {@code OPUS_SET_COMPLEXITY} が弾くのと同様に無視して既定値のまま
     */
    public JavaOpusEncoder(int sampleRate, int channels, int quality) {
        try {
            this.encoder = new OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_AUDIO);
        } catch (final OpusException ex) {
            throw new IllegalStateException("Failed to create an encoder instance", ex);
        }
        if (quality >= 0 && quality <= 10) {
            encoder.setComplexity(quality);
        }
        this.channels = channels;
    }

    /** @return {@code output} の先頭から書いた byte 数 */
    public int encode(ShortBuffer input, int frameSize, ByteBuffer output) {
        final int samples = frameSize * channels;
        if (pcm.length < samples) {
            pcm = new short[samples];
        }
        input.duplicate().get(pcm, 0, samples);
        final int capacity = output.capacity();
        if (packet.length < capacity) {
            packet = new byte[capacity];
        }
        final int length;
        try {
            length = encoder.encode(pcm, 0, frameSize, packet, 0, capacity);
        } catch (final OpusException ex) {
            throw new IllegalStateException("Encoding failed: " + ex.getMessage(), ex);
        }
        output.put(0, packet, 0, length);
        return length;
    }
}
