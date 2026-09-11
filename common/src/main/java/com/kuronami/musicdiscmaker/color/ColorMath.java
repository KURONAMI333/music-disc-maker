package com.kuronami.musicdiscmaker.color;

/**
 * 色空間の変換と色差。JDK だけに依存する (MC / バニラ / DFU を一切参照しない) ので
 * headless の JUnit でそのまま回せる。
 *
 * <p><b>参照実装との一致</b>: 値は Python 側 (
 * {@code _work/stonecutter-loader-2026-08-24/assets-v3/mk12.py} の {@code to_lab}/{@code dE} と
 * {@code mkauto.py} の {@code rgb}) と 8bit RGB まで完全一致させる。そのために踏んだ地雷が 3 つある:
 * <ul>
 *   <li>剰余。Python の {@code %} は常に除数と同符号なので、{@code (h-4) % 360} は h=3.43 で
 *       359.43 を返す。Java の {@code %} は -0.57 を返し、赤/橙/茶/桃だけ G が静かにずれる。
 *       → {@link #pyMod(double, double)} を通す。</li>
 *   <li>丸め。Python の {@code round()} は半数偶数丸め。{@code Math.round} は半数切り上げなので
 *       使えない。→ {@link Math#rint(double)}。</li>
 *   <li>HLS→RGB は {@code colorsys.hls_to_rgb} の分岐をそのまま写す (定数 1/6・1/3・2/3 の
 *       評価順も含む)。</li>
 * </ul>
 */
public final class ColorMath {

    private static final double ONE_THIRD = 1.0 / 3.0;
    private static final double ONE_SIXTH = 1.0 / 6.0;
    private static final double TWO_THIRD = 2.0 / 3.0;

    private ColorMath() {
    }

    /** Python の {@code x % y} (結果は必ず y と同符号)。 */
    static double pyMod(double x, double y) {
        final double r = x % y;
        return (r != 0.0 && ((r < 0.0) != (y < 0.0))) ? r + y : r;
    }

    private static double hueValue(double m1, double m2, double hue) {
        final double h = pyMod(hue, 1.0);
        if (h < ONE_SIXTH) {
            return m1 + (m2 - m1) * h * 6.0;
        }
        if (h < 0.5) {
            return m2;
        }
        if (h < TWO_THIRD) {
            return m1 + (m2 - m1) * (TWO_THIRD - h) * 6.0;
        }
        return m1;
    }

    /**
     * {@code colorsys.hls_to_rgb} の移植。h は回転数 (0..1)、l/s は 0..1。
     * 戻り値は {r, g, b} の 0..1。
     */
    static double[] hlsToRgb(double h, double l, double s) {
        if (s == 0.0) {
            return new double[] { l, l, l };
        }
        final double m2 = (l <= 0.5) ? l * (1.0 + s) : l + s - (l * s);
        final double m1 = 2.0 * l - m2;
        return new double[] {
                hueValue(m1, m2, h + ONE_THIRD),
                hueValue(m1, m2, h),
                hueValue(m1, m2, h - ONE_THIRD)
        };
    }

    /**
     * 色相 (度)・彩度 (%)・明度 (%) から 0xRRGGBB。
     *
     * <p>明度の上限は <b>96</b>、彩度の上限は 100 (参照実装の clamp をそのまま写す)。
     * 上限 96 は「白飛びした段を作らない」ための設計値で、100 に上げると確定済みの盤面が変わる。
     */
    public static int rgb(double hueDeg, double saturationPct, double lightnessPct) {
        final double l = Math.max(0.0, Math.min(lightnessPct, 96.0)) / 100.0;
        final double s = Math.max(0.0, Math.min(saturationPct, 100.0)) / 100.0;
        final double[] c = hlsToRgb(hueDeg / 360.0, l, s);
        return pack((int) Math.rint(c[0] * 255.0),
                (int) Math.rint(c[1] * 255.0),
                (int) Math.rint(c[2] * 255.0));
    }

    public static int pack(int r, int g, int b) {
        return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public static int red(int rgb) {
        return (rgb >> 16) & 0xFF;
    }

    public static int green(int rgb) {
        return (rgb >> 8) & 0xFF;
    }

    public static int blue(int rgb) {
        return rgb & 0xFF;
    }

    /** HSL の L (0..100)。{@code colorsys.rgb_to_hls} の l と同じ ((max+min)/2)。 */
    static double hslLightness(int rgb) {
        final double r = red(rgb) / 255.0;
        final double g = green(rgb) / 255.0;
        final double b = blue(rgb) / 255.0;
        final double maxc = Math.max(r, Math.max(g, b));
        final double minc = Math.min(r, Math.min(g, b));
        return (maxc + minc) / 2.0 * 100.0;
    }

    private static double linearize(int channel) {
        final double u = channel / 255.0;
        return u <= 0.04045 ? u / 12.92 : Math.pow((u + 0.055) / 1.055, 2.4);
    }

    private static double labF(double t) {
        return t > 0.008856 ? Math.pow(t, 1.0 / 3.0) : 7.787 * t + 16.0 / 116.0;
    }

    /** sRGB (D65) → CIE Lab。戻り値は {L, a, b}。 */
    public static double[] toLab(int rgb) {
        final double r = linearize(red(rgb));
        final double g = linearize(green(rgb));
        final double b = linearize(blue(rgb));
        final double x = r * .4124 + g * .3576 + b * .1805;
        final double y = r * .2126 + g * .7152 + b * .0722;
        final double z = r * .0193 + g * .1192 + b * .9505;
        final double fx = labF(x / .95047);
        final double fy = labF(y / 1.0);
        final double fz = labF(z / 1.08883);
        return new double[] { 116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz) };
    }

    /** CIE76 の色差 (Lab のユークリッド距離)。 */
    public static double deltaE(int a, int b) {
        final double[] la = toLab(a);
        final double[] lb = toLab(b);
        final double dl = la[0] - lb[0];
        final double da = la[1] - lb[1];
        final double db = la[2] - lb[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }
}
