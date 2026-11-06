package com.kuronami.musicdiscmaker.lavaplayer.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import org.junit.jupiter.api.Test;

/** native libconnector を load できない環境 (Android) で使う pure-Java codec の単体確認。 */
class JavaCodecFallbackTest {

    private static final int RATE = 48_000;
    private static final int FRAME = 960;

    @Test
    void opusRoundTripKeepsTheSignal() {
        final JavaOpusEncoder encoder = new JavaOpusEncoder(RATE, 2, 64_000);
        final JavaOpusDecoder decoder = new JavaOpusDecoder(RATE, 2);
        final ShortBuffer pcm = ByteBuffer.allocateDirect(FRAME * 2 * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        final ByteBuffer packet = ByteBuffer.allocateDirect(4_000);
        final ShortBuffer decoded = ByteBuffer.allocateDirect(FRAME * 2 * 2).order(ByteOrder.nativeOrder()).asShortBuffer();

        double energy = 0;
        for (int frame = 0; frame < 25; frame++) {
            for (int i = 0; i < FRAME; i++) {
                final short value = (short) (8_000 * Math.sin(2 * Math.PI * 440 * (frame * FRAME + i) / RATE));
                pcm.put(2 * i, value);
                pcm.put(2 * i + 1, value);
            }
            final int length = encoder.encode(pcm, FRAME, packet);
            assertTrue(length > 0);
            packet.limit(length);
            assertEquals(FRAME, decoder.decode(packet, decoded, FRAME));
            packet.clear();
            if (frame >= 5) {
                for (int i = 0; i < FRAME * 2; i++) {
                    energy += (double) decoded.get(i) * decoded.get(i);
                }
            }
        }
        final double rms = Math.sqrt(energy / (20.0 * FRAME * 2));
        // 振幅 8000 の正弦波の RMS は約 5657
        assertTrue(rms > 4_000 && rms < 7_000, "rms " + rms);
    }

    @Test
    void resamplerConvertsTheRateAndKeepsTheTone() {
        for (final int type : new int[] { JavaResampler.LINEAR, JavaResampler.SINC_FASTEST }) {
            final JavaResampler resampler = new JavaResampler(type, 44_100, RATE);
            final float[] input = new float[1_024];
            final float[] output = new float[4_096];
            final int[] progress = new int[2];
            final float[] all = new float[RATE];
            int produced = 0;
            for (int chunk = 0; chunk < 43; chunk++) {
                for (int i = 0; i < input.length; i++) {
                    input[i] = (float) Math.sin(2 * Math.PI * 1_000 * (chunk * input.length + i) / 44_100.0);
                }
                resampler.process(input, 0, input.length, output, 0, output.length, progress);
                assertEquals(input.length, progress[0]);
                System.arraycopy(output, 0, all, produced, Math.min(progress[1], all.length - produced));
                produced += progress[1];
            }
            final double expected = 43 * 1_024 * (double) RATE / 44_100;
            assertTrue(Math.abs(produced - expected) < 40, "type " + type + " produced " + produced);

            // 1kHz の周期 (48 sample) ごとの零交差数で音程が保たれたかを見る
            int crossings = 0;
            for (int i = 1_000; i < 41_000; i++) {
                if (all[i - 1] < 0 && all[i] >= 0) {
                    crossings++;
                }
            }
            assertTrue(Math.abs(crossings - 40_000 / 48) <= 2, "type " + type + " crossings " + crossings);
        }
    }
}
