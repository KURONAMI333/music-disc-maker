package com.kuronami.musicdiscmaker.color;

/**
 * 染料 (色相) からディスクの盤面 5 段とアクセント 7 段を計算する。
 *
 * <p><b>規則の正本</b>は {@code kuronami-mods/knowledge/MDM_DECISIONS.md} の
 * 「アクセントの色は表で持たず、盤面から計算する」(2026-09-06 設計上の決定)。要旨:
 * <ul>
 *   <li>利用者が選ぶのは色相 (= 染料) だけ。彩度と明度はここが決める</li>
 *   <li>盤面の支配色とアクセント主段の色差 (CIE76) を
 *       床 {@value #FLOOR} 以上 / 天井 {@value #CEIL} 以下 (努力目標) の帯へ入れる</li>
 *   <li>明度は両方向へ動かす (暗い盤面では明るい側へ・明るい盤面では暗い側へ)</li>
 *   <li>上振れは彩度で抑え、下振れは明度で持ち上げる</li>
 *   <li>帯に入る解が無い時は、床を満たす中で天井に一番近い点 (色差が最大の点ではない)</li>
 * </ul>
 *
 * <p><b>参照実装</b>は {@code _work/stonecutter-loader-2026-08-24/assets-v3/mkauto.py}。
 * 有彩色と自動配色の探索は、探索順・スコア・早期打ち切り・フォールバックまで
 * 参照実装と一致する。明示的な白/薄灰/灰/黒は、2026-09-10の黒化報告への修正として
 * 染料の明るさを優先し、色差の床を適用しない。自動配色は従来の探索を維持する。
 *
 * <p>純粋計算 = MC も描画も参照しない。tint レイヤーの宣言はここの外側 (次のユニット)。
 */
public final class DiscPalette {

    /** 有彩色・自動配色の色差の床。明示的な無彩色の染色には適用しない。 */
    public static final double FLOOR = 52.0;
    /** 同・天井。努力目標 (盤面が明るく彩度も高いと満たせない)。 */
    public static final double CEIL = 80.0;

    /** 盤面 5 段の明度骨格 (暗 → 明)。確定した青盤面の実測値。 */
    private static final double[] L_BOARD = { 4.90, 9.61, 10.98, 18.43, 23.73 };
    /** アクセント 7 段の明度骨格 (暗 → 明)。index 4 が主段。 */
    private static final double[] L_ACC = { 8.43, 13.53, 16.67, 21.18, 31.57, 34.12, 38.63 };

    /** 未染色の単曲盤。MDM_DECISIONS の確定 8bit RGB。 */
    private static final int[] DEFAULT_BOARD = { 0x040711, 0x070F22, 0x081127, 0x0E2041, 0x122C53 };
    /** 未染色の単曲盤アクセント。MDM_DECISIONS の確定 8bit RGB。 */
    private static final int[] DEFAULT_ACCENT = {
            0x062225, 0x0A373B, 0x0C4449, 0x0F575D, 0x17808A, 0x188C96, 0x1C9DA9
    };
    /** 盤面の支配色の段 (L_BOARD の index)。 */
    private static final int BOARD_DOMINANT = 3;
    /** アクセントの主段 (L_ACC の index)。ここの明度を基準に 7 段をずらす。 */
    private static final double ACC_MAIN_L = 31.57;

    // 明示的に選んだ無彩色アクセントの主段。黒でも下側4段を潰さず、白は盤面との
    // コントラスト探索より色名の明るさを優先する。未染色曲の自動割当には使わない。
    private static final double ACHROMATIC_MAIN_OFFSET = 12.0;
    private static final double ACHROMATIC_MAIN_DYE_SCALE = 0.75;
    private static final double ACHROMATIC_MAIN_MIN = 20.0;
    private static final double ACHROMATIC_MAIN_MAX = 86.0;

    /** 探索する彩度 (高い順)。帯へ入る中で最も彩度が高い点を採るための順序。 */
    private static final double[] SEARCH_SATURATIONS = { 71.5, 64, 56, 48, 40, 32, 24, 16 };
    /** 帯に解が無い時の粗い彩度リスト。 */
    private static final double[] FALLBACK_SATURATIONS = { 71.5, 56, 40, 24, 12 };

    // 明度の探索格子: 5.0 .. 94.0 を 0.5 刻み。整数で回して 10 で割る
    // (double を加算していくと後半でずれ、参照実装と一致しなくなる)。
    private static final int L_STEP_MIN = 50;
    private static final int L_STEP_MAX = 941;
    private static final int L_STEP = 5;

    private DiscPalette() {
    }

    /** 確定した未染色の単曲盤面。 */
    public static Board defaultBoard() {
        return new Board(DEFAULT_BOARD.clone(), DEFAULT_BOARD[BOARD_DOMINANT]);
    }

    /** 確定した未染色の単曲アクセント。 */
    public static Accent defaultAccent() {
        return new Accent(DEFAULT_ACCENT.clone(),
                ColorMath.deltaE(DEFAULT_BOARD[BOARD_DOMINANT], DEFAULT_ACCENT[4]));
    }

    /** 盤面 5 段 (暗 → 明) と、そのうちの支配色。 */
    public record Board(int[] steps, int dominant) {

        public int step(int index) {
            return steps[index];
        }

        public int size() {
            return steps.length;
        }
    }

    /** アクセント 7 段 (暗 → 明) と、主段と盤面支配色の色差。 */
    public record Accent(int[] steps, double deltaE) {

        public int step(int index) {
            return steps[index];
        }

        public int size() {
            return steps.length;
        }

        /** 主段 (盤面との色差を測った段)。 */
        public int main() {
            return steps[4];
        }
    }

    /**
     * 盤面 5 段。色相は染料から暗 → 明で +8 度回し、彩度は染料の彩度を 1.25 倍 (上限 96)、
     * 明度は確定した骨格を「染料自身の明るさ」で持ち上げる。
     *
     * <p>無彩色の染料 (白/薄灰/灰/黒) は彩度 0 になるので、盤面は明度だけで分かれる。
     */
    public static Board board(DiscDye dye) {
        final double h = dye.hue();
        final double s = dye.isChromatic() ? Math.min(dye.saturation() * 1.25, 96.0) : 0.0;
        final double scale = Math.max(dye.lightness(), 18.0) / 18.43 * 0.9;
        final int[] steps = new int[L_BOARD.length];
        for (int i = 0; i < L_BOARD.length; i++) {
            steps[i] = ColorMath.rgb(ColorMath.pyMod(h - 4 + 8.0 * i / 4.0, 360.0), s, L_BOARD[i] * scale);
        }
        return new Board(steps, steps[BOARD_DOMINANT]);
    }

    /** 明示染色用のアクセント。無彩色は色名に対応する明度を優先する。 */
    public static Accent accent(DiscDye dye, Board board) {
        return accent(dye, board.dominant());
    }

    /**
     * 明示染色用のアクセント。白・薄灰・灰・黒は盤面との ΔE 床よりも、選んだ染料の
     * 明度と順序を優先する。有彩色は従来どおりコントラスト探索を使う。
     *
     * @param dominant 盤面の支配色 (0xRRGGBB)
     */
    public static Accent accent(DiscDye dye, int dominant) {
        return dye.isChromatic() ? contrastAccent(dye, dominant) : achromaticAccent(dye, dominant);
    }

    /**
     * 未染色曲の自動配色用。選択されていない色名を優先せず、全16色で従来の可読性を保つ。
     */
    public static Accent automaticAccent(DiscDye dye, Board board) {
        return contrastAccent(dye, board.dominant());
    }

    private static Accent achromaticAccent(DiscDye dye, int dominant) {
        final double mainLightness = Math.max(ACHROMATIC_MAIN_MIN,
                Math.min(ACHROMATIC_MAIN_MAX, ACHROMATIC_MAIN_OFFSET + dye.lightness() * ACHROMATIC_MAIN_DYE_SCALE));
        final double scale = mainLightness / ACC_MAIN_L;
        final int[] steps = new int[L_ACC.length];
        for (int i = 0; i < L_ACC.length; i++) {
            steps[i] = ColorMath.rgb(0.0, 0.0, L_ACC[i] * scale);
        }
        return new Accent(steps, ColorMath.deltaE(dominant, steps[4]));
    }

    /** コントラスト探索。明示有彩色と未染色曲の自動配色の共有実装。 */
    private static Accent contrastAccent(DiscDye dye, int dominant) {
        final double h = dye.hue();
        final boolean chroma = dye.isChromatic();
        final double dominantL = ColorMath.hslLightness(dominant);

        boolean found = false;
        double bestScore = 0.0;
        double bestS = 0.0;
        double bestL = 0.0;
        double bestE = 0.0;

        final double[] saturations = chroma ? SEARCH_SATURATIONS : new double[] { 0.0 };
        for (double s : saturations) {
            for (int l10 = L_STEP_MIN; l10 < L_STEP_MAX; l10 += L_STEP) {
                final double l = l10 / 10.0;
                final double e = ColorMath.deltaE(dominant, ColorMath.rgb(h, s, l));
                if (!(FLOOR <= e && e <= CEIL)) {
                    continue;
                }
                // 盤面から離れる向き (暗い盤面なら明るく・明るい盤面なら暗く) を優先し、
                // 彩度は高いほど、明度は主段の既定に近いほど良いとする。
                final double away = dominantL < 50 ? (l - dominantL) : (dominantL - l);
                final double score = (-s * 0.6) + Math.abs(l - ACC_MAIN_L) * 0.25 + (away > 0 ? 0 : 40);
                if (!found || score < bestScore) {
                    found = true;
                    bestScore = score;
                    bestS = s;
                    bestL = l;
                    bestE = e;
                }
            }
            // この彩度で最良が更新されていれば、より低い彩度は見ない (彩度は高いほど良い)。
            if (found && bestS == s) {
                break;
            }
        }

        if (!found) {
            // 帯に入る解が無い。床を満たす中で天井に一番近い点を採る
            // (色差が最大の点ではない = 裁定の但し書き)。
            boolean any = false;
            double keyBest = 0.0;
            final double[] fallback = chroma ? FALLBACK_SATURATIONS : new double[] { 0.0 };
            for (double s : fallback) {
                for (int l10 = L_STEP_MIN; l10 < L_STEP_MAX; l10 += L_STEP) {
                    final double l = l10 / 10.0;
                    final double e = ColorMath.deltaE(dominant, ColorMath.rgb(h, s, l));
                    if (e < FLOOR) {
                        continue;
                    }
                    final double key = Math.abs(e - CEIL);
                    // 参照実装は 4 要素タプルの辞書順 min。key → s → l の順に比べる。
                    if (!any || key < keyBest || (key == keyBest && (s < bestS || (s == bestS && l < bestL)))) {
                        any = true;
                        keyBest = key;
                        bestS = s;
                        bestL = l;
                        bestE = e;
                    }
                }
            }
            if (!any) {
                // 床すら満たせない。色差が最大の点 (3 要素タプルの辞書順 max)。
                final double s = chroma ? 71.5 : 0.0;
                for (int l10 = L_STEP_MIN; l10 < L_STEP_MAX; l10 += L_STEP) {
                    final double l = l10 / 10.0;
                    final double e = ColorMath.deltaE(dominant, ColorMath.rgb(h, s, l));
                    if (!any || e > bestE || (e == bestE && l > bestL)) {
                        any = true;
                        bestS = s;
                        bestL = l;
                        bestE = e;
                    }
                }
            }
        }

        final double off = bestL - ACC_MAIN_L;
        final int[] steps = new int[L_ACC.length];
        for (int i = 0; i < L_ACC.length; i++) {
            steps[i] = ColorMath.rgb(h, bestS, L_ACC[i] + off);
        }
        return new Accent(steps, bestE);
    }
}
