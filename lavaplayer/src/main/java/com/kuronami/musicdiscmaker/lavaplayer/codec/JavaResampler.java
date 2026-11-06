package com.kuronami.musicdiscmaker.lavaplayer.codec;

/**
 * 1 channel 分の streaming resampler。native {@code SampleRateLibrary} (libsamplerate) の代わり。
 *
 * <p>入力は毎回すべて受け取り (inputUsed = 入力長)、出力 buffer に入りきらない分は内部に残して
 * 次の呼び出し (入力 0 でも可) で吐く。{@code ResamplingPcmAudioFilter} は出力が満杯の間
 * 呼び直すので、これで取りこぼさない。
 *
 * <p>libsamplerate の型に合わせて、LINEAR = 線形補間、ZERO_ORDER_HOLD = 直前値、
 * SINC_* = Blackman 窓つき sinc (片側 8 / 16 / 32 tap、縮小時は帯域を絞る)。
 * LavaPlayer 既定の resampling quality (LOW) は LINEAR。
 */
public final class JavaResampler {

    public static final int SINC_BEST_QUALITY = 0;
    public static final int SINC_MEDIUM_QUALITY = 1;
    public static final int SINC_FASTEST = 2;
    public static final int ZERO_ORDER_HOLD = 3;
    public static final int LINEAR = 4;

    private static final int TABLE_STEPS_PER_TAP = 256;

    private final int type;
    private final double step;
    private final int halfWidth;
    private final float[] kernel;

    private float[] buffer = new float[0];
    private int length;
    private double position;

    public JavaResampler(int type, int sourceRate, int targetRate) {
        this.type = type;
        this.step = (double) sourceRate / targetRate;
        this.halfWidth = switch (type) {
            case SINC_BEST_QUALITY -> 32;
            case SINC_MEDIUM_QUALITY -> 16;
            case SINC_FASTEST -> 8;
            default -> 1;
        };
        this.kernel = isSinc() ? buildKernel(halfWidth, Math.min(1.0, (double) targetRate / sourceRate)) : null;
        reset();
    }

    /** 過去の入力の影響を消す (seek 後)。先頭に無音の履歴を置き、最初の入力から出力が始まる位置へ。 */
    public void reset() {
        ensureCapacity(halfWidth);
        java.util.Arrays.fill(buffer, 0, halfWidth, 0f);
        length = halfWidth;
        position = halfWidth;
    }

    /** @param progress [0] = 使った入力数, [1] = 書いた出力数 */
    public void process(float[] input, int inputOffset, int inputLength, float[] output, int outputOffset,
            int outputLength, int[] progress) {
        ensureCapacity(length + inputLength);
        System.arraycopy(input, inputOffset, buffer, length, inputLength);
        length += inputLength;

        int produced = 0;
        while (produced < outputLength) {
            final int base = (int) position;
            if (base + halfWidth >= length) {
                break;
            }
            output[outputOffset + produced++] = sample(base, position - base);
            position += step;
        }

        // 次の出力が参照する最古の位置 (base - halfWidth + 1) より前を捨てる
        final int drop = Math.min(length, Math.max(0, (int) position - halfWidth + 1));
        if (drop > 0) {
            System.arraycopy(buffer, drop, buffer, 0, length - drop);
            length -= drop;
            position -= drop;
        }

        progress[0] = inputLength;
        progress[1] = produced;
    }

    private float sample(int base, double fraction) {
        if (type == ZERO_ORDER_HOLD) {
            return buffer[base];
        }
        if (!isSinc()) {
            return (float) (buffer[base] + fraction * (buffer[base + 1] - buffer[base]));
        }
        double sum = 0;
        for (int tap = -halfWidth + 1; tap <= halfWidth; tap++) {
            sum += buffer[base + tap] * kernelAt(Math.abs(tap - fraction));
        }
        return (float) sum;
    }

    private double kernelAt(double distance) {
        final double scaled = distance * TABLE_STEPS_PER_TAP;
        final int index = (int) scaled;
        if (index >= kernel.length - 1) {
            return 0;
        }
        final double fraction = scaled - index;
        return kernel[index] + fraction * (kernel[index + 1] - kernel[index]);
    }

    private boolean isSinc() {
        return type == SINC_BEST_QUALITY || type == SINC_MEDIUM_QUALITY || type == SINC_FASTEST;
    }

    private void ensureCapacity(int required) {
        if (buffer.length < required) {
            buffer = java.util.Arrays.copyOf(buffer, Math.max(required, buffer.length * 2));
        }
    }

    /** 距離 0..halfWidth の窓つき sinc を表にする (cutoff は入力 Nyquist に対する比)。 */
    private static float[] buildKernel(int halfWidth, double cutoff) {
        final float[] table = new float[halfWidth * TABLE_STEPS_PER_TAP + 1];
        for (int i = 0; i < table.length; i++) {
            final double x = (double) i / TABLE_STEPS_PER_TAP;
            final double t = x / halfWidth;
            final double window = 0.42 + 0.5 * Math.cos(Math.PI * t) + 0.08 * Math.cos(2 * Math.PI * t);
            final double arg = Math.PI * cutoff * x;
            final double sinc = x == 0 ? 1.0 : Math.sin(arg) / arg;
            table[i] = (float) (cutoff * sinc * window);
        }
        return table;
    }
}
