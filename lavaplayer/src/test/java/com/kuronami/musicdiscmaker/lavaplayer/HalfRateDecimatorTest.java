package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link HalfRateDecimator} の単体試験。実 encoder を通さず、出てくる sample の並びだけを見る。
 *
 * <h2>端の扱いが要点</h2>
 * フィルタは前後 {@link HalfRateDecimator#DELAY} sample を見るので、入力の外は 0 として計算する
 * (先頭も末尾も同じ規則)。末尾を計算せずに残りを 0 で埋めると、実音がある最後の出力が 0 で
 * 置き換わり、データの途中に段差ができる。ここでは DC と矩形波で、計算した末尾が 0 埋めに
 * なっていないこと・sample 数が入力の半分になることを固定する。encoder と container を通した
 * 確認は {@code LocalFileNormalizerTest} が持つ。
 */
class HalfRateDecimatorTest {

    private static final short AMPLITUDE = 10_000;

    /** DC は端以外で入力と同値 (フィルタの DC 利得が 1)。両端では 0 から立ち上がり、0 へ戻る。 */
    @Test
    void aDcInputPassesThroughExceptAtTheEnds() {
        final int samples = 3 * HalfRateDecimator.BLOCK_SAMPLES;
        final short[] out = decimate(dc(samples));

        assertEquals(samples / 2, out.length, "20ms の倍数なら出力はちょうど入力の半分");
        // 先頭は入力の外 (0) から立ち上がる。中心が入力をはみ出している間は DC に届かない。
        assertTrue(out[0] < AMPLITUDE * 3 / 4, "先頭は 0 から立ち上がる: " + out[0]);
        // 端以外は入力のまま (丸めの ±1 だけ許す)。
        for (int i = 40; i < out.length - 40; i++) {
            assertNear(AMPLITUDE, out[i], 1, "端以外は DC のまま通る (index " + i + ")");
        }
        // 末尾も 0 として計算されている (実音のまま止まる)。段差の検査は下の専用の試験。
        assertTrue(Math.abs(out[out.length - 1]) <= AMPLITUDE * 1.1, "末尾が発散している");
    }

    /**
     * 計算した末尾の中に段差が無い。前の実装は最後の 15 sample を計算せず 0 で埋めていたので、
     * DC の 1 sample 手前で {@link #AMPLITUDE} ぶんの段差が出ていた。
     */
    @Test
    void theComputedTailDoesNotContainAStep() {
        final int samples = 3 * HalfRateDecimator.BLOCK_SAMPLES;
        final short[] out = decimate(dc(samples));

        // 末尾 32 sample の隣接差。計算した末尾は振幅のまま終わるので、窓のリンギング
        // (実測 5.4%) までは許し、0 埋めのような 1 sample の段差 (100%) は許さない。
        int maxStep = 0;
        for (int i = out.length - 32; i < out.length - 1; i++) {
            maxStep = Math.max(maxStep, Math.abs(out[i + 1] - out[i]));
        }
        assertTrue(maxStep <= AMPLITUDE / 8,
                "末尾に段差がある (最大 " + maxStep + " / 振幅 " + AMPLITUDE + ")");
    }

    /** 全振幅の矩形波でも sample の範囲を出ない (clamp が効き、折り返して符号が反転しない)。 */
    @Test
    void aFullScaleSquareWaveStaysInsideTheSampleRange() {
        final short[] input = squareWave(2 * HalfRateDecimator.BLOCK_SAMPLES, 480);
        final short[] out = decimate(input);

        for (int i = 64; i < out.length - 64; i++) {
            final int center = 2 * i;
            // 変換点から 64 sample 以上離れた plateau は、入力と同じ向きのまま出る。
            if (!sameSignAround(input, center, 64)) {
                continue;
            }
            assertEquals(Integer.signum(input[center]), Integer.signum(out[i]),
                    "plateau の符号が反転している (clamp せず short が折り返した): index " + i);
        }
    }

    /** 1 block (960 sample) 未満の入力も 1 frame になる。実音の後ろだけが 0 で埋まる。 */
    @Test
    void anInputShorterThanOneBlockBecomesOneFrame() {
        final short[] out = decimate(dc(500));

        assertEquals(HalfRateDecimator.FRAME_SAMPLES, out.length, "1 frame だけ出る");
        // 500 の半分 = 250 sample を計算し、残りの 230 は frame を埋める 0。
        assertNear(AMPLITUDE, out[100], 1, "計算した範囲は実音");
        assertTrue(out[249] != 0, "最後の計算 sample を 0 で置いていない");
        assertEquals(0, out[250], "frame を埋める分だけ 0");
    }

    /**
     * 出力 sample の総数は入力の半分 (端数 1 sample は 1 出力)。20ms の倍数では frame の
     * 0 埋めが起きないので、入力の半分ちょうどになる。
     */
    @Test
    void aWholeNumberOfBlocksHasExactlyHalfTheSamples() {
        for (final int blocks : new int[] {1, 2, 3, 10}) {
            final int samples = blocks * HalfRateDecimator.BLOCK_SAMPLES;
            final short[] out = decimate(dc(samples));
            assertEquals(samples / 2, out.length, blocks + " block の出力数");
            // 最後の sample まで計算されている (0 埋めなら 0 になる)。
            assertTrue(out[out.length - 1] != 0, blocks + " block の末尾が 0 埋めになっている");
        }
    }

    // ==== helpers ====

    /** 出力 sample を全部集める。呼出し側 (encoder 経路) と同じく、毎 block で揃った frame を取る。 */
    private static short[] decimate(short[] input) {
        final HalfRateDecimator decimator = new HalfRateDecimator();
        final ByteBuffer frame = ByteBuffer.allocate(HalfRateDecimator.FRAME_SAMPLES * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        final List<Short> out = new ArrayList<>();
        int offset = 0;
        while (offset < input.length) {
            final int chunk = Math.min(HalfRateDecimator.BLOCK_SAMPLES, input.length - offset);
            decimator.push(ShortBuffer.wrap(input, offset, chunk));
            offset += chunk;
            frame.clear();
            while (decimator.takeFrame(frame)) {
                frame.flip();
                drain(frame, out);
                frame.clear();
            }
        }
        decimator.flushFrame(frame);
        frame.flip();
        drain(frame, out);

        final short[] samples = new short[out.size()];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = out.get(i);
        }
        return samples;
    }

    private static void drain(ByteBuffer frame, List<Short> out) {
        while (frame.hasRemaining()) {
            out.add(frame.getShort());
        }
    }

    private static short[] dc(int samples) {
        final short[] out = new short[samples];
        java.util.Arrays.fill(out, AMPLITUDE);
        return out;
    }

    /** 全振幅 (±32767) の矩形波。{@code halfPeriod} sample ごとに符号が反転する。 */
    private static short[] squareWave(int samples, int halfPeriod) {
        final short[] out = new short[samples];
        for (int i = 0; i < samples; i++) {
            out[i] = (i / halfPeriod) % 2 == 0 ? Short.MAX_VALUE : Short.MIN_VALUE;
        }
        return out;
    }

    /** {@code center} の前後 {@code reach} sample がすべて同じ符号か。 */
    private static boolean sameSignAround(short[] input, int center, int reach) {
        final int sign = Integer.signum(input[center]);
        for (int i = Math.max(0, center - reach); i <= Math.min(input.length - 1, center + reach); i++) {
            if (Integer.signum(input[i]) != sign) {
                return false;
            }
        }
        return true;
    }

    private static void assertNear(int expected, short actual, int tolerance, String message) {
        assertTrue(Math.abs(expected - actual) <= tolerance,
                message + ": " + actual + " (期待 " + expected + " ±" + tolerance + ")");
    }
}
