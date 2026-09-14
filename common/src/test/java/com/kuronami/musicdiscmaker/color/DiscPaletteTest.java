package com.kuronami.musicdiscmaker.color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.component.DiscDyeData;

/**
 * {@link DiscPalette} の盤面と有彩色アクセントが Python の参照実装
 * ({@code _work/stonecutter-loader-2026-08-24/assets-v3/mkauto.py}) と同じ色を返すことを固定する。
 *
 * <p>無彩色アクセントだけは 2026-09-10 裁定で参照実装から分岐する。利用者が選んだ
 * black / gray / light_gray / white の明度を保持し、盤面との ΔE 床は例外にする。
 * 未染色曲の自動配色は {@link DiscPalette#automaticAccent(DiscDye, DiscPalette.Board)} を通して
 * 旧来のコントラスト探索を維持する。
 */
class DiscPaletteTest {

    private static void assertBoard(DiscDye dye, int... expected) {
        final DiscPalette.Board board = DiscPalette.board(dye);
        assertArrayEquals(expected, board.steps(), "board steps for " + dye.id());
        assertEquals(expected[3], board.dominant(), "dominant for " + dye.id());
    }

    private static void assertAccent(DiscDye boardDye, DiscDye accentDye, double expectedDeltaE, int... expected) {
        final DiscPalette.Board board = DiscPalette.board(boardDye);
        final DiscPalette.Accent accent = DiscPalette.accent(accentDye, board);
        final String label = boardDye.id() + " x " + accentDye.id();
        assertArrayEquals(expected, accent.steps(), "accent steps for " + label);
        assertEquals(expectedDeltaE, accent.deltaE(), 1e-6, "deltaE for " + label);
        assertEquals(expected[4], accent.main(), "main step for " + label);
    }

    private static void assertAutomaticAccent(DiscDye boardDye, DiscDye accentDye, double expectedDeltaE,
            int... expected) {
        final DiscPalette.Board board = DiscPalette.board(boardDye);
        final DiscPalette.Accent accent = DiscPalette.automaticAccent(accentDye, board);
        final String label = "automatic " + boardDye.id() + " x " + accentDye.id();
        assertArrayEquals(expected, accent.steps(), "accent steps for " + label);
        assertEquals(expectedDeltaE, accent.deltaE(), 1e-6, "deltaE for " + label);
    }
    // ── 参照実装との一致 ────────────────────────────────────────────────

    @Test
    void boardsMatchPythonReference() {
        assertBoard(DiscDye.BLUE, 0x0B122E, 0x16215A, 0x192467, 0x2B37AD, 0x454ED1);
        assertBoard(DiscDye.WHITE, 0x3D3D3D, 0x777777, 0x888888, 0xE5E5E5, 0xF5F5F5);
        assertBoard(DiscDye.BLACK, 0x0B0B0B, 0x161616, 0x191919, 0x292929, 0x353535);
        assertBoard(DiscDye.RED, 0x300607, 0x5F0E0C, 0x6C140E, 0xB52618, 0xE23D26);
        assertBoard(DiscDye.LIME, 0x243604, 0x446908, 0x4A7909, 0x75CA10, 0x8FEF2A);
        assertBoard(DiscDye.YELLOW, 0x4A3702, 0x927103, 0xA78703, 0xFBD823, 0xFCEB73);
        assertBoard(DiscDye.GRAY, 0x121212, 0x242424, 0x292929, 0x454545, 0x595959);
        assertBoard(DiscDye.LIGHT_GRAY, 0x242424, 0x464646, 0x505050, 0x878787, 0xAEAEAE);
    }

    /**
     * 赤/橙/茶/桃は色相が 0 度をまたぐので、盤面 1 段目の色相が負になる。Python の剰余は
     * 常に正なので 359.43 度、Java の {@code %} なら -0.57 度になり G だけ静かにずれる。
     * 上の {@code assertBoard(RED, ...)} がその検出器だが、意図を明示して固定しておく。
     */
    @Test
    void hueWrapsTheWayPythonDoes() {
        assertEquals(359.4285714285714, ColorMath.pyMod(DiscDye.RED.hue() - 4, 360.0), 1e-12);
        assertEquals(0x300607, DiscPalette.board(DiscDye.RED).step(0));
    }

    /** 設計で検証した 16 通り (mkauto.py のシート)。ΔE 52〜89 に収まるのも合わせて固定する。 */
    @Test
    void mkautoSheetReproducesExactly() {
        assertAccent(DiscDye.WHITE, DiscDye.RED, 79.4651165274,
                0x901F18, 0xA6231C, 0xB4261E, 0xC72B21, 0xDF483F, 0xE1524A, 0xE4655D);
        assertAccent(DiscDye.WHITE, DiscDye.CYAN, 52.0747983017,
                0x041717, 0x082D2E, 0x0A3B3B, 0x0D4E4F, 0x157B7D, 0x178688, 0x1A9A9C);
        assertAccent(DiscDye.WHITE, DiscDye.YELLOW, 64.9427914880,
                0x251F06, 0x3B310A, 0x493D0C, 0x5C4D0F, 0x8A7417, 0x957D19, 0xA98D1C);
        assertAutomaticAccent(DiscDye.WHITE, DiscDye.WHITE, 56.9124750692,
                0x151515, 0x222222, 0x2A2A2A, 0x363636, 0x505050, 0x575757, 0x626262);
        assertAccent(DiscDye.BLACK, DiscDye.RED, 56.9257329011,
                0x250806, 0x3B0D0A, 0x49100C, 0x5C140F, 0x8A1D17, 0x952019, 0xA9241C);
        assertAccent(DiscDye.BLACK, DiscDye.CYAN, 52.2012189765,
                0x093536, 0x0D4B4C, 0x0F595A, 0x126C6E, 0x1A999B, 0x1CA4A6, 0x1FB8BA);
        assertAccent(DiscDye.BLACK, DiscDye.YELLOW, 59.7839592332,
                0x251F06, 0x3B310A, 0x493D0C, 0x5C4D0F, 0x8A7417, 0x957D19, 0xA98D1C);
        assertAutomaticAccent(DiscDye.BLACK, DiscDye.WHITE, 52.2759521875,
                0x6D6D6D, 0x7A7A7A, 0x828282, 0x8E8E8E, 0xA8A8A8, 0xAFAFAF, 0xBABABA);
        assertAccent(DiscDye.RED, DiscDye.RED, 52.5407051010,
                0xDE4137, 0xE2564E, 0xE4635B, 0xE7766F, 0xEFA19D, 0xF1ACA8, 0xF4BFBB);
        assertAccent(DiscDye.RED, DiscDye.CYAN, 79.9315447389,
                0x0F1414, 0x1A2323, 0x202C2D, 0x2A3A3A, 0x405859, 0x466060, 0x4F6D6E);
        assertAccent(DiscDye.RED, DiscDye.YELLOW, 63.1525311546,
                0x4C400D, 0x625210, 0x705E13, 0x846E16, 0xB1951D, 0xBC9E1F, 0xD0AE23);
        assertAutomaticAccent(DiscDye.RED, DiscDye.WHITE, 70.8040662198,
                0x2C2C2C, 0x393939, 0x414141, 0x4D4D4D, 0x676767, 0x6E6E6E, 0x797979);
        assertAccent(DiscDye.LIME, DiscDye.RED, 88.7233697918,
                0x9E8584, 0xAA9492, 0xB19D9B, 0xBBA9A8, 0xD2C7C6, 0xD8CECD, 0xE2DADA);
        assertAccent(DiscDye.LIME, DiscDye.CYAN, 83.7488178571,
                0x327374, 0x398586, 0x3E9091, 0x45A0A1, 0x60BABB, 0x69BDBF, 0x79C5C6);
        assertAccent(DiscDye.LIME, DiscDye.YELLOW, 52.1274583521,
                0xBE9F20, 0xD4B223, 0xDCBA2B, 0xDFC03F, 0xE7CF6C, 0xE9D378, 0xECD98B);
        assertAutomaticAccent(DiscDye.LIME, DiscDye.WHITE, 87.6939330957,
                0x7A7A7A, 0x878787, 0x8F8F8F, 0x9B9B9B, 0xB5B5B5, 0xBCBCBC, 0xC7C7C7);
    }

    /**
     * mkauto のシートに入っていない盤面も 1 つ固定する。青は既定色の染料で、ここには
     * 帯に解が無くてフォールバック (床を満たす中で天井に一番近い点) を通る組み合わせが混ざる
     * (lime は ΔE 80.06 = 天井超え)。
     */
    @Test
    void blueBoardAccentsMatchPythonReference() {
        assertAccent(DiscDye.BLUE, DiscDye.CYAN, 79.8760404885,
                0x000000, 0x000000, 0x020B0B, 0x051F1F, 0x0D4C4D, 0x0F5758, 0x126A6B);
        assertAccent(DiscDye.BLUE, DiscDye.MAGENTA, 52.4992068938,
                0x73136F, 0x8A1785, 0x971992, 0xAB1CA5, 0xD824D1, 0xDC2DD5, 0xDF41D9);
        assertAccent(DiscDye.BLUE, DiscDye.PURPLE, 52.6361879453,
                0x9E3ADE, 0xA950E2, 0xB05EE4, 0xB971E7, 0xD09FEF, 0xD5AAF1, 0xDFBEF4);
        assertAccent(DiscDye.BLUE, DiscDye.PINK, 78.3830382140,
                0x550E27, 0x6B1231, 0x791437, 0x8C1740, 0xBA1F55, 0xC5215A, 0xD92463);
    }

    // ── 規則そのもの ───────────────────────────────────────────────────

    /** 色相を持つアクセントは床を必達にする。無彩色は選択した明度を優先する例外。 */
    @Test
    void everyChromaticAccentClearsTheFloor() {
        double min = Double.MAX_VALUE;
        double max = 0.0;
        for (DiscDye boardDye : DiscDye.values()) {
            final DiscPalette.Board board = DiscPalette.board(boardDye);
            for (DiscDye accentDye : DiscDye.values()) {
                if (!accentDye.isChromatic()) {
                    continue;
                }
                final double e = DiscPalette.accent(accentDye, board).deltaE();
                assertTrue(e >= DiscPalette.FLOOR,
                        "床を割った: " + boardDye.id() + " x " + accentDye.id() + " dE=" + e);
                min = Math.min(min, e);
                max = Math.max(max, e);
            }
        }
        assertTrue(min >= DiscPalette.FLOOR, "有彩色アクセントの最小 ΔE");
        assertTrue(max >= min, "有彩色アクセントの ΔE 範囲");
    }

    /**
     * 無彩色 4 色は色相が使えないので、<b>盤面</b>を明度だけで分ける。
     * 暗い順に black &lt; gray &lt; light_gray &lt; white で、隣同士が実際に見分けられること。
     */
    @Test
    void achromaticBoardsSeparateByLightness() {
        final DiscDye[] ramp = { DiscDye.BLACK, DiscDye.GRAY, DiscDye.LIGHT_GRAY, DiscDye.WHITE };
        final int[] dominants = new int[ramp.length];
        for (int i = 0; i < ramp.length; i++) {
            final DiscPalette.Board board = DiscPalette.board(ramp[i]);
            dominants[i] = board.dominant();
            // 無彩色の染料は彩度 0 になるので、盤面も無彩色になる。
            assertEquals(ColorMath.red(dominants[i]), ColorMath.green(dominants[i]));
            assertEquals(ColorMath.green(dominants[i]), ColorMath.blue(dominants[i]));
        }
        for (int i = 1; i < dominants.length; i++) {
            assertTrue(ColorMath.red(dominants[i]) > ColorMath.red(dominants[i - 1]),
                    "明度が単調に上がっていない: " + ramp[i - 1].id() + " -> " + ramp[i].id());
        }
        // 4 色が互いに見分けられる (CIE76 で 10 以上離れていれば別色として読める)。
        for (int i = 0; i < dominants.length; i++) {
            for (int j = i + 1; j < dominants.length; j++) {
                final double e = ColorMath.deltaE(dominants[i], dominants[j]);
                assertTrue(e >= 10.0, ramp[i].id() + " と " + ramp[j].id() + " が近すぎる dE=" + e);
            }
        }
    }

    /**
     * 明示した無彩色アクセントは、どの盤面でも選んだ色名の順を保つ。
     * 各7段は比例ランプなので暗部だけが純黒に潰れない。
     */
    @Test
    void explicitAchromaticAccentsKeepTheirLightnessOnEveryBoard() {
        final DiscDye[] ramp = { DiscDye.BLACK, DiscDye.GRAY, DiscDye.LIGHT_GRAY, DiscDye.WHITE };
        final int[][] expected = {
                { 0x0E0E0E, 0x171717, 0x1C1C1C, 0x242424, 0x353535, 0x393939, 0x414141 },
                { 0x171717, 0x262626, 0x2E2E2E, 0x3B3B3B, 0x585858, 0x5F5F5F, 0x6C6C6C },
                { 0x262626, 0x3D3D3D, 0x4C4C4C, 0x606060, 0x8F8F8F, 0x9B9B9B, 0xAFAFAF },
                { 0x3B3B3B, 0x5E5E5E, 0x747474, 0x939393, 0xDBDBDB, 0xEDEDED, 0xF5F5F5 }
        };
        for (DiscDye boardDye : DiscDye.values()) {
            final DiscPalette.Board board = DiscPalette.board(boardDye);
            int previousMain = -1;
            for (int i = 0; i < ramp.length; i++) {
                final DiscPalette.Accent accent = DiscPalette.accent(ramp[i], board);
                assertArrayEquals(expected[i], accent.steps(), "無彩色ランプ " + ramp[i].id());
                for (int step : accent.steps()) {
                    assertEquals(ColorMath.red(step), ColorMath.green(step));
                    assertEquals(ColorMath.green(step), ColorMath.blue(step));
                }
                for (int step = 1; step < accent.size(); step++) {
                    assertTrue(ColorMath.red(accent.step(step)) > ColorMath.red(accent.step(step - 1)),
                            "陰影が単調でない: " + ramp[i].id());
                }
                assertTrue(ColorMath.red(accent.main()) > previousMain,
                        "主段の順序が崩れた: " + boardDye.id() + " / " + ramp[i].id());
                previousMain = ColorMath.red(accent.main());
            }
            assertTrue(previousMain >= 0xD0, "white が十分に明るくない: " + boardDye.id());
        }
        for (int i = 1; i < ramp.length; i++) {
            final int previous = expected[i - 1][4];
            final int current = expected[i][4];
            assertTrue(ColorMath.deltaE(previous, current) >= 10.0,
                    ramp[i - 1].id() + " と " + ramp[i].id() + " が近すぎる");
        }
    }
    /** 同じ入力なら常に同じ結果 (乱数・時刻・環境に依存しない)。 */
    @Test
    void isDeterministic() {
        for (DiscDye boardDye : DiscDye.values()) {
            for (DiscDye accentDye : DiscDye.values()) {
                final DiscPalette.Board b1 = DiscPalette.board(boardDye);
                final DiscPalette.Board b2 = DiscPalette.board(boardDye);
                assertArrayEquals(b1.steps(), b2.steps());
                final DiscPalette.Accent a1 = DiscPalette.accent(accentDye, b1);
                final DiscPalette.Accent a2 = DiscPalette.accent(accentDye, b2);
                assertArrayEquals(a1.steps(), a2.steps());
                assertEquals(a1.deltaE(), a2.deltaE());
            }
        }
    }

    /** 明度の上限 96 と下限 0 のクランプ。参照実装の {@code max(0,min(l,96))} と同じ。 */
    @Test
    void lightnessIsClampedAt96() {
        assertEquals(ColorMath.rgb(0, 0, 96), ColorMath.rgb(0, 0, 120));
        assertEquals(0x000000, ColorMath.rgb(0, 0, -5));
        assertEquals(0xF5F5F5, ColorMath.rgb(0, 0, 96));
    }

    // ── 保存データ ─────────────────────────────────────────────────────

    /** id は往復する。空は未指定側、未知 id は破損として全体を未染色へ落とす。 */
    @Test
    void dyeDataRoundTripsById() {
        for (DiscDye dye : DiscDye.values()) {
            assertEquals(dye, DiscDye.byId(dye.id()));
        }
        final DiscDyeData data = new DiscDyeData(DiscDye.BLUE, DiscDye.CYAN);
        assertEquals(data, DiscDyeData.fromIds(data.boardId(), data.accentId()));
        assertEquals(new DiscDyeData(null, DiscDye.CYAN), DiscDyeData.fromIds("", "cyan"));
        assertEquals(new DiscDyeData(DiscDye.BLUE, null), DiscDyeData.fromIds("blue", ""));
        assertNull(DiscDyeData.fromIds("blue", "chartreuse"));
        assertNull(DiscDyeData.fromIds(null, null));
        assertNull(DiscDye.byId("light_bleu"));
    }

    @Test
    void dyeOverridesOnlyTheSuppliedRegion() {
        final DiscDyeData original = new DiscDyeData(DiscDye.BLUE, DiscDye.PINK);
        assertEquals(new DiscDyeData(DiscDye.RED, DiscDye.PINK),
                DiscDyeData.withOverrides(original, DiscDye.RED, null));
        assertEquals(new DiscDyeData(DiscDye.BLUE, DiscDye.CYAN),
                DiscDyeData.withOverrides(original, null, DiscDye.CYAN));
        assertEquals(new DiscDyeData(DiscDye.RED, null),
                DiscDyeData.withOverrides(null, DiscDye.RED, null));
        assertEquals(new DiscDyeData(null, DiscDye.CYAN),
                DiscDyeData.withOverrides(null, null, DiscDye.CYAN));
        assertNull(DiscDyeData.withOverrides(null, null, null));
    }

    /**
     * 未染色は「青で染めた」ではない。{@code DiscPalette.board(BLUE)} の支配色は
     * 確定した既定盤面 ({@code #0e2041}) と別物なので、フォールバックを既定染料で
     * 埋めると見た目が変わる — その事実を固定しておく。
     */
    @Test
    void blueDyeIsNotTheDefaultBoard() {
        assertTrue(DiscPalette.board(DiscDye.BLUE).dominant() != 0x0E2041,
                "青染料の盤面が確定済みの既定盤面と一致してしまっている");
    }
}
