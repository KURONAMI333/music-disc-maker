package com.kuronami.musicdiscmaker.lavaplayer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Random;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.sedmelluq.discord.lavaplayer.natives.opus.OpusEncoder;

/**
 * レートごとの Opus 出力 bitrate を測る probe。数値は {@code LocalFileNormalizer} の
 * {@code MEASURED_BPS_*} と javadoc の根拠なので、測り直す時だけ実行する。
 *
 * <p>結果は {@code _work/mdm-310-execution/opus-size-probe-20261101/README.md}。
 */
@Disabled("実測用。通常の試験では走らせない")
class OpusSizeProbeTest {

    private static short[] noise(int samples, long seed) {
        final Random random = new Random(seed);
        final short[] out = new short[samples];
        for (int i = 0; i < samples; i++) {
            out[i] = (short) (random.nextInt(20_000) - 10_000);
        }
        return out;
    }

    /** 正弦波の重ね合わせ + ゆっくりした包絡 (音楽的な信号の代用)。 */
    private static short[] music(int samples, int rate) {
        final short[] out = new short[samples];
        final double[] freqs = {220.0, 277.18, 329.63, 440.0, 554.37, 1318.51};
        for (int i = 0; i < samples; i++) {
            final double t = i / (double) rate;
            final double env = 0.55 + 0.45 * Math.sin(2 * Math.PI * 0.35 * t);
            double v = 0.0;
            for (int k = 0; k < freqs.length; k++) {
                v += Math.sin(2 * Math.PI * freqs[k] * t + k) / (k + 1.5);
                v += 0.35 * Math.sin(2 * Math.PI * freqs[k] * 2 * t) / (k + 1.5);
            }
            out[i] = (short) Math.max(-30_000, Math.min(30_000, v * 3_000 * env));
        }
        return out;
    }

    private static final class Result {
        long bytes;
        int frames;
    }

    private static Result encode(short[] pcm, int rate, int frameSamples, int quality) {
        final OpusEncoder encoder = new OpusEncoder(rate, 1, quality);
        final Result result = new Result();
        final ByteBuffer out = ByteBuffer.allocateDirect(16_000);
        int pos = 0;
        while (pos + frameSamples <= pcm.length) {
            final ByteBuffer in = ByteBuffer.allocateDirect(frameSamples * 2)
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frameSamples; i++) {
                in.putShort(pcm[pos + i]);
            }
            in.flip();
            final ShortBuffer samples = in.asShortBuffer();
            out.clear();
            final int length = encoder.encode(samples, frameSamples, out);
            if (length <= 0) {
                throw new IllegalStateException("encode failed");
            }
            result.bytes += length;
            result.frames++;
            pos += frameSamples;
        }
        encoder.close();
        return result;
    }

    private static double kbps(Result r, int frameSamples, int rate) {
        final double seconds = r.frames * (frameSamples / (double) rate);
        return r.bytes * 8.0 / seconds / 1000.0;
    }

    private static short[] decimate(short[] in, int factor) {
        final short[] out = new short[in.length / factor];
        for (int i = 0; i < out.length; i++) {
            int sum = 0;
            for (int k = 0; k < factor; k++) {
                sum += in[i * factor + k];
            }
            out[i] = (short) (sum / factor);
        }
        return out;
    }

    @Test
    void probe() {
        final int seconds = 60;
        System.out.println("=== PROBE START ===");
        final short[] noise48 = noise(48_000 * seconds, 7L);
        final short[] music48 = music(48_000 * seconds, 48_000);

        for (final int rate : new int[] {48_000, 24_000, 16_000, 12_000, 8_000}) {
            final int factor = 48_000 / rate;
            final short[] n = factor == 1 ? noise48 : decimate(noise48, factor);
            final short[] m = factor == 1 ? music48 : decimate(music48, factor);
            final int frame = rate / 50; // 20ms
            final Result rn = encode(n, rate, frame, 10);
            final Result rm = encode(m, rate, frame, 10);
            System.out.printf("rate=%5d frame=%4d q=10  noise=%6.2fkbps music=%6.2fkbps%n",
                    rate, frame, kbps(rn, frame, rate), kbps(rm, frame, rate));
        }

        // 48kHz で frame 長だけ変えた場合 (20ms と 60ms)
        for (final int frame : new int[] {960, 2_880}) {
            final Result rn = encode(noise48, 48_000, frame, 10);
            final Result rm = encode(music48, 48_000, frame, 10);
            System.out.printf("rate=48000 frame=%4d q=10  noise=%6.2fkbps music=%6.2fkbps%n",
                    frame, kbps(rn, frame, 48_000), kbps(rm, frame, 48_000));
        }
        // 48kHz で complexity を振る
        for (final int q : new int[] {0, 3, 5, 10}) {
            final Result rn = encode(noise48, 48_000, 960, q);
            System.out.printf("rate=48000 frame= 960 q=%2d  noise=%6.2fkbps%n", q,
                    kbps(rn, 960, 48_000));
        }
        System.out.println("=== PROBE END ===");
    }
}
