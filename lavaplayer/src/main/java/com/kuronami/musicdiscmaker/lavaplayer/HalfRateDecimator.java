package com.kuronami.musicdiscmaker.lavaplayer;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * 48kHz mono S16 を 24kHz へ落とす 2:1 の間引き器（長い曲の出力を 32MiB に収めるため）。
 *
 * <p>同梱の {@link com.sedmelluq.discord.lavaplayer.natives.opus.OpusEncoder} は bitrate を
 * 設定できない（native の create は sampleRate / channels / application / complexity しか受け取らず、
 * libopus が入力サンプルレートから出力 bitrate を自動で決める。実測: 48kHz mono で約 51kbps、
 * 24kHz mono で約 27kbps）。そのため長い曲は encoder へ渡すレートそのものを半分にする。
 *
 * <p>単純な 2 サンプルおきの切り出しは 12kHz 以上の成分が 12kHz 以下へ折り返して耳に付くので、
 * 先に線形位相 FIR で 12kHz 以上を落としてから間引く。遮断は 11kHz（Opus の 24kHz =
 * super-wideband の帯域 12kHz の少し内側）で、63 tap の Blackman 窓により遷移域は約 ±2.1kHz、
 * 13.1kHz 以上は -74dB まで落ちる。折り返しは 11kHz 以下の帯域へは入らない。
 *
 * <p>フィルタは前後 {@link #DELAY} sample を見るので、最初の 31 sample は入力の外 (0) として
 * 扱い、末尾は {@link #flushFrame} が同じく 0 詰めする。両端を同じ規則で埋めるので、尺は
 * 入力 (48kHz の sample 数を 2 で割ったもの) と一致する。呼出し側は 20ms 分 (480 sample)
 * たまってから 1 frame 取り出す。
 */
final class HalfRateDecimator {

    /** 1 回の {@link #push} で受け取る入力 sample 数（48kHz の 20ms）。 */
    static final int BLOCK_SAMPLES = 960;
    /** 1 frame の出力 sample 数（24kHz の 20ms）。 */
    static final int FRAME_SAMPLES = 480;
    /** 係数の本数（奇数）。Blackman 窓なので遷移域は約 5.5/63 周期。 */
    static final int TAPS = 63;
    /** フィルタ中心から端までの sample 数。出力はこの分だけ入力を先読みする。 */
    static final int DELAY = (TAPS - 1) / 2;
    /** 遮断周波数（入力サンプルレートに対する正規化周期）。11000 / 48000。 */
    private static final double CUTOFF = 11_000.0 / 48_000.0;

    private final double[] taps;

    /** 直前の block の末尾（{@link #DELAY} 個）+ 今回の block。 */
    private final short[] input = new short[TAPS - 1 + BLOCK_SAMPLES];
    private int inputCount;
    /** {@code input[0]} の絶対 sample 番号。 */
    private long inputBase;
    /** 次に出す出力の絶対 sample 番号。 */
    private long nextOutput;

    /** 取り出し待ちの出力 sample。1 回の push で最大 480 個増え、繰り越しは 479 個まで。 */
    private final short[] ready = new short[FRAME_SAMPLES * 2];
    private int readyCount;

    HalfRateDecimator() {
        taps = designTaps();
    }

    /** 入力 1 block（{@link #BLOCK_SAMPLES} sample）を足し、出せる分だけ出力を積む。 */
    void push(ShortBuffer block) {
        compact();
        while (block.hasRemaining() && inputCount < input.length) {
            input[inputCount++] = block.get();
        }
        emit();
    }

    /**
     * 20ms 分（{@link #FRAME_SAMPLES} sample）たまっていれば {@code out} へ LE16 で書く。
     *
     * @return frame を書いたら true（まだ足りなければ false）
     */
    boolean takeFrame(ByteBuffer out) {
        if (readyCount < FRAME_SAMPLES) {
            return false;
        }
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            out.putShort(ready[i]);
        }
        System.arraycopy(ready, FRAME_SAMPLES, ready, 0, readyCount - FRAME_SAMPLES);
        readyCount -= FRAME_SAMPLES;
        return true;
    }

    /**
     * 末尾で 20ms に満たない分を 0 詰めして 1 frame として書く（48kHz 経路の末尾の扱いと揃える）。
     */
    void flushFrame(ByteBuffer out) {
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            out.putShort(i < readyCount ? ready[i] : 0);
        }
        readyCount = 0;
    }

    /** 次の出力に要る最古の入力より前を捨てる（{@code input} は常に TAPS-1 + block で足りる）。 */
    private void compact() {
        final int drop = (int) Math.max(0L, (2L * nextOutput - DELAY) - inputBase);
        if (drop <= 0) {
            return;
        }
        System.arraycopy(input, drop, input, 0, inputCount - drop);
        inputCount -= drop;
        inputBase += drop;
    }

    /**
     * 先読みが揃った出力を順に積む。{@code input} の範囲外は読まない (compact が保証する)。
     *
     * <p>出力 1 個は {@code x[2n-31] .. x[2n+31]} の積和 (中心 {@code x[2n]}) なので、始点では
     * 前半 31 sample が入力の外にある。そこは 0 として扱い、末尾は {@link #flushFrame} が
     * 同じく 0 詰めする (両端を揃えないと尺が 0.65ms ずれる)。
     */
    private void emit() {
        final long last = inputBase + inputCount - 1L;
        while (2L * nextOutput + DELAY <= last) {
            final long center = 2L * nextOutput + DELAY;
            double sum = 0.0;
            for (int k = 0; k < TAPS; k++) {
                final long index = center - k;
                if (index < inputBase) {
                    break; // これより前は入力の外 = 0
                }
                sum += taps[k] * input[(int) (index - inputBase)];
            }
            ready[readyCount++] = clamp(sum);
            nextOutput++;
        }
    }

    /** Blackman 窓つき sinc。DC 利得が 1 になるよう正規化する。 */
    private static double[] designTaps() {
        final double[] taps = new double[TAPS];
        double sum = 0.0;
        for (int i = 0; i < TAPS; i++) {
            final int offset = i - DELAY;
            final double sinc = offset == 0
                    ? 2.0 * CUTOFF
                    : Math.sin(2.0 * Math.PI * CUTOFF * offset) / (Math.PI * offset);
            final double window = 0.42
                    - 0.5 * Math.cos(2.0 * Math.PI * i / (TAPS - 1))
                    + 0.08 * Math.cos(4.0 * Math.PI * i / (TAPS - 1));
            taps[i] = sinc * window;
            sum += taps[i];
        }
        for (int i = 0; i < TAPS; i++) {
            taps[i] /= sum;
        }
        return taps;
    }

    private static short clamp(double value) {
        final long rounded = Math.round(value);
        if (rounded > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (rounded < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) rounded;
    }
}
