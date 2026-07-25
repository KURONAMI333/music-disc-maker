package com.kuronami.musicdiscmaker.beat;

/**
 * 実数入力用の radix-2 FFT と Hann 窓。ビート解析だけが使う最小実装。
 *
 * <p>外部 DSP ライブラリは持ち込まない: TarsosDSP 等は GPL-3 で、MDM の MIT ライセンスと非互換
 * (同梱すると配布物全体が GPL 化する)。必要なのは FFT・窓・振幅スペクトルだけなので自前で持つ。
 *
 * <p>複素 FFT に虚部 0 を入れて回す素直な実装。実数専用の最適化 (半分のサイズで回す) は
 * 2 倍の速度差しか生まず、解析は実時間の 200 倍以上で走る (実測) ので採らない。
 */
public final class Fft {

    private final int size;
    private final int levels;
    private final float[] cosTable;
    private final float[] sinTable;
    private final float[] window;
    /** {@code sum(w[n]^2)}。Parseval で時間領域の平均パワーへ戻すための正規化係数。 */
    private final double windowSumSquares;

    /**
     * @param size 2 のべき乗の変換長 (1024 / 2048 / 4096)
     */
    public Fft(int size) {
        if (size < 2 || Integer.bitCount(size) != 1) {
            throw new IllegalArgumentException("FFT サイズは 2 のべき乗でなければならない: " + size);
        }
        this.size = size;
        this.levels = Integer.numberOfTrailingZeros(size);
        this.cosTable = new float[size / 2];
        this.sinTable = new float[size / 2];
        for (int i = 0; i < size / 2; i++) {
            final double angle = 2 * Math.PI * i / size;
            cosTable[i] = (float) Math.cos(angle);
            sinTable[i] = (float) Math.sin(angle);
        }
        this.window = new float[size];
        double sumSq = 0.0;
        for (int i = 0; i < size; i++) {
            // Hann 窓。両端が 0 になるので、hop でつないだ時の不連続 (スペクトル漏れ) が小さい。
            final double w = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (size - 1));
            window[i] = (float) w;
            sumSq += w * w;
        }
        this.windowSumSquares = sumSq;
    }

    public int size() {
        return size;
    }

    /**
     * {@code samples}(長さ {@link #size}・値域 [-1, 1]) に Hann 窓をかけて振幅スペクトルを
     * {@code magOut}(長さ {@code size/2}) へ書く。
     *
     * <p>{@code re} / {@code im} は呼び出し側が使い回す作業領域 (長さ {@link #size})。
     * 毎フレームの確保を避けるために外から渡す。
     */
    public void magnitudeSpectrum(float[] samples, float[] re, float[] im, float[] magOut) {
        for (int i = 0; i < size; i++) {
            re[i] = samples[i] * window[i];
            im[i] = 0f;
        }
        transform(re, im);
        for (int k = 0; k < size / 2; k++) {
            magOut[k] = (float) Math.sqrt((double) re[k] * re[k] + (double) im[k] * im[k]);
        }
    }

    /**
     * 振幅スペクトルの {@code [fromBin, toBin)} 区間から、その帯域に絞った信号の平均パワーを返す。
     *
     * <p>Parseval の関係 {@code sum(x[n]w[n])^2 = (1/N) sum|X[k]|^2} を使い、負の周波数ぶんを 2 倍して
     * {@code sum(w^2)} で割る。窓と変換長に依存しない量になるので、FFT サイズを変えても
     * ビートマップの値が動かない (= config で {@code beatFftSize} を変えてもキャッシュの意味が変わらない)。
     */
    public double bandMeanSquare(float[] mag, int fromBin, int toBin) {
        double energy = 0.0;
        final int hi = Math.min(toBin, size / 2);
        for (int k = Math.max(0, fromBin); k < hi; k++) {
            energy += (double) mag[k] * mag[k];
        }
        return 2.0 * energy / size / windowSumSquares;
    }

    /** 周波数 (Hz) を bin 番号へ。 */
    public int binOf(double hz, int sampleRate) {
        return (int) Math.round(hz * size / sampleRate);
    }

    /** in-place の複素 FFT (Cooley-Tukey・decimation in time)。 */
    private void transform(float[] re, float[] im) {
        // bit 反転による並べ替え
        for (int i = 0; i < size; i++) {
            final int j = Integer.reverse(i) >>> (32 - levels);
            if (j > i) {
                float t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= size; len <<= 1) {
            final int half = len / 2;
            final int step = size / len;
            for (int i = 0; i < size; i += len) {
                for (int j = i, k = 0; j < i + half; j++, k += step) {
                    final int l = j + half;
                    final float cos = cosTable[k];
                    final float sin = sinTable[k];
                    final float tRe = re[l] * cos + im[l] * sin;
                    final float tIm = -re[l] * sin + im[l] * cos;
                    re[l] = re[j] - tRe;
                    im[l] = im[j] - tIm;
                    re[j] += tRe;
                    im[j] += tIm;
                }
            }
        }
    }
}
