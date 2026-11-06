package com.kuronami.musicdiscmaker.lavaplayer.codec;

import net.sourceforge.jaad.aac.AACException;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;

/**
 * JAAD による AAC decoder (transport 無しの raw access unit)。native {@code AacDecoderLibrary}
 * (fdk-aac) の代わり。
 *
 * <p>fdk-aac は fill で受けた access unit を decode 時に 1 つずつ消費し、出力 buffer が
 * 0 の decode で stream 情報だけを確定させる。ここでは fill を queue に積み、
 * {@link #resolveStreamInfo} が先頭を decode して PCM を保留、次の {@link #decode} がそれを返す。
 */
public final class JavaAacDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(JavaAacDecoder.class);

    private final ArrayDeque<byte[]> units = new ArrayDeque<>();
    private final SampleBuffer samples = new SampleBuffer();
    private Decoder decoder;
    private byte[] pending;
    private int pendingRate;
    private int pendingChannels;
    private boolean reportedFailure;

    public JavaAacDecoder() {
        samples.setBigEndian(false);
    }

    /** @param audioSpecificConfig ISO 14496-3 AudioSpecificConfig。@return 成功なら 0 */
    public int configure(byte[] audioSpecificConfig) {
        try {
            decoder = new Decoder(audioSpecificConfig);
        } catch (final AACException | RuntimeException ex) {
            LOGGER.debug("JAAD rejected the AAC configuration", ex);
            return -1;
        }
        units.clear();
        pending = null;
        return 0;
    }

    /** native の encodeConfiguration と同じ並びで AudioSpecificConfig を組む。 */
    public static byte[] audioSpecificConfig(int objectType, int frequencyIndex, int frequency, int channels,
            int extensionFrequencyIndex, int extensionFrequency, int extensionProfile, boolean extension) {
        final BitWriter writer = new BitWriter();
        writer.write(objectType, 5);
        writer.write(frequencyIndex, 4);
        if (frequencyIndex == 15) {
            writer.write(frequency, 24);
        }
        writer.write(channels, 4);
        if (extension) {
            writer.write(extensionFrequencyIndex, 4);
            if (extensionFrequencyIndex == 15) {
                writer.write(extensionFrequency, 24);
            }
            writer.write(extensionProfile, 5);
        }
        // GASpecificConfig: frameLengthFlag / dependsOnCoreCoder / extensionFlag = 0
        writer.write(0, 3);
        return writer.toByteArray();
    }

    /** @return 読み込んだ byte 数 (常に全量) */
    public int fill(ByteBuffer buffer) {
        final byte[] unit = new byte[buffer.remaining()];
        buffer.duplicate().get(unit);
        if (unit.length > 0) {
            units.add(unit);
        }
        return unit.length;
    }

    /** 1 frame 分を {@code output} の先頭から容量いっぱいに書く。@return 書いたら true */
    public boolean decode(ShortBuffer output) {
        if (pending == null && !decodeNext()) {
            return false;
        }
        final int capacity = output.capacity();
        final int count = Math.min(capacity, pending.length / 2);
        for (int i = 0; i < count; i++) {
            output.put(i, (short) ((pending[2 * i] & 0xFF) | (pending[2 * i + 1] << 8)));
        }
        for (int i = count; i < capacity; i++) {
            output.put(i, (short) 0);
        }
        pending = null;
        return true;
    }

    /** @return {sampleRate, channels, frameSize}。まだ 1 frame も無ければ null */
    public int[] resolveStreamInfo() {
        if (pending == null && !decodeNext()) {
            return null;
        }
        return new int[] { pendingRate, pendingChannels, pending.length / 2 / Math.max(1, pendingChannels) };
    }

    private boolean decodeNext() {
        if (decoder == null) {
            return false;
        }
        byte[] unit;
        while ((unit = units.poll()) != null) {
            try {
                decoder.decodeFrame(unit, samples);
            } catch (final AACException | RuntimeException ex) {
                // 壊れた 1 frame で再生全体を止めない (fdk-aac の concealment 相当として読み捨てる)
                if (!reportedFailure) {
                    reportedFailure = true;
                    LOGGER.warn("Skipping an AAC frame JAAD could not decode", ex);
                }
                continue;
            }
            final byte[] data = samples.getData();
            if (data == null || data.length == 0) {
                continue;
            }
            pending = data.clone();
            pendingRate = samples.getSampleRate();
            pendingChannels = samples.getChannels();
            return true;
        }
        return false;
    }

    private static final class BitWriter {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        private int current;
        private int bits;

        void write(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                current = (current << 1) | ((value >>> i) & 1);
                if (++bits == 8) {
                    out.write(current);
                    current = 0;
                    bits = 0;
                }
            }
        }

        byte[] toByteArray() {
            if (bits > 0) {
                out.write(current << (8 - bits));
                current = 0;
                bits = 0;
            }
            return out.toByteArray();
        }
    }
}
