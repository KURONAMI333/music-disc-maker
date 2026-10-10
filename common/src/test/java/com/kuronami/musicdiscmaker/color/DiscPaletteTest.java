package com.kuronami.musicdiscmaker.color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.component.DiscDyeData;

/**
 * {@link DiscPalette} の盤面と未染色曲の自動アクセントが Python の参照実装
 * ({@code _work/stonecutter-loader-2026-08-24/assets-v3/mkauto.py}) と同じ色を返すことを固定する。
 * 明示染色のアクセントは 2026-10-10 から盤面に関係なく染料ごとに1色 (同じ染料どうしだけ特別な縁)。
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

    /** 未染色曲が実際に使う既定の盤面 (確定した青) の自動アクセント。 */
    private static void assertDefaultAutomatic(DiscDye accentDye, double expectedDeltaE, int... expected) {
        final DiscPalette.Accent accent = DiscPalette.automaticAccent(accentDye, DiscPalette.defaultBoard());
        assertArrayEquals(expected, accent.steps(), "default automatic " + accentDye.id());
        assertEquals(expectedDeltaE, accent.deltaE(), 1e-6, "deltaE for default automatic " + accentDye.id());
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

    /** 設計で検証したシート (mkauto.py) のうち自動配色の分。明示染色の分は 2026-10-10 に規則が変わった。 */
    @Test
    void mkautoSheetReproducesExactly() {
        assertAutomaticAccent(DiscDye.WHITE, DiscDye.WHITE, 56.9124750692,
                0x151515, 0x222222, 0x2A2A2A, 0x363636, 0x505050, 0x575757, 0x626262);
        assertAutomaticAccent(DiscDye.BLACK, DiscDye.WHITE, 52.2759521875,
                0x6D6D6D, 0x7A7A7A, 0x828282, 0x8E8E8E, 0xA8A8A8, 0xAFAFAF, 0xBABABA);
        assertAutomaticAccent(DiscDye.RED, DiscDye.WHITE, 70.8040662198,
                0x2C2C2C, 0x393939, 0x414141, 0x4D4D4D, 0x676767, 0x6E6E6E, 0x797979);
        assertAutomaticAccent(DiscDye.LIME, DiscDye.WHITE, 87.6939330957,
                0x7A7A7A, 0x878787, 0x8F8F8F, 0x9B9B9B, 0xB5B5B5, 0xBCBCBC, 0xC7C7C7);
        // 旧来は明示染色として固定していた値。自動配色の探索 (帯・フォールバック) の検出器として残す。
        assertAutomaticAccent(DiscDye.WHITE, DiscDye.RED, 79.4651165274,
                0x901F18, 0xA6231C, 0xB4261E, 0xC72B21, 0xDF483F, 0xE1524A, 0xE4655D);
        assertAutomaticAccent(DiscDye.WHITE, DiscDye.CYAN, 52.0747983017,
                0x041717, 0x082D2E, 0x0A3B3B, 0x0D4E4F, 0x157B7D, 0x178688, 0x1A9A9C);
        assertAutomaticAccent(DiscDye.RED, DiscDye.RED, 52.5407051010,
                0xDE4137, 0xE2564E, 0xE4635B, 0xE7766F, 0xEFA19D, 0xF1ACA8, 0xF4BFBB);
        assertAutomaticAccent(DiscDye.BLUE, DiscDye.CYAN, 79.8760404885,
                0x010404, 0x031414, 0x051E1E, 0x072C2C, 0x0D4C4D, 0x0F5758, 0x126A6B);
        assertAutomaticAccent(DiscDye.BLUE, DiscDye.PINK, 78.3830382140,
                0x550E27, 0x6B1231, 0x791437, 0x8C1740, 0xBA1F55, 0xC5215A, 0xD92463);
    }

    /** 未染色の単曲が実際に使う既定の盤面 × 16 色。未染色の見た目は 2026-10-10 の変更前と同じ。 */
    @Test
    void undyedDiscsKeepTheirAutomaticColours() {
        assertDefaultAutomatic(DiscDye.WHITE, 52.17080996170593,
                0x535353, 0x606060, 0x686868, 0x737373, 0x8E8E8E, 0x949494, 0xA0A0A0);
        assertDefaultAutomatic(DiscDye.ORANGE, 71.67619211869687,
                0x251506, 0x3B230A, 0x492B0C, 0x5C360F, 0x8A5117, 0x955719, 0xA9631C);
        assertDefaultAutomatic(DiscDye.MAGENTA, 56.556760865632256,
                0x250623, 0x3B0A39, 0x490C46, 0x5C0F59, 0x8A1785, 0x951990, 0xA91CA3);
        assertDefaultAutomatic(DiscDye.LIGHT_BLUE, 52.00661062837402,
                0x0E4555, 0x12576B, 0x146279, 0x17728C, 0x1F97BA, 0x21A1C5, 0x24B1D9);
        assertDefaultAutomatic(DiscDye.YELLOW, 79.20685865534969,
                0x1E1905, 0x342C09, 0x42370B, 0x56480E, 0x836E16, 0x8E7718, 0xA2881B);
        assertDefaultAutomatic(DiscDye.LIME, 79.50541689289311,
                0x050801, 0x141E05, 0x1D2C07, 0x29400B, 0x476D12, 0x4E7814, 0x5B8C17);
        assertDefaultAutomatic(DiscDye.PINK, 55.2340774639958,
                0x250611, 0x3B0A1B, 0x490C21, 0x5C0F2A, 0x8A173F, 0x951944, 0xA91C4D);
        assertDefaultAutomatic(DiscDye.GRAY, 52.17080996170593,
                0x535353, 0x606060, 0x686868, 0x737373, 0x8E8E8E, 0x949494, 0xA0A0A0);
        assertDefaultAutomatic(DiscDye.LIGHT_GRAY, 52.17080996170593,
                0x535353, 0x606060, 0x686868, 0x737373, 0x8E8E8E, 0x949494, 0xA0A0A0);
        assertDefaultAutomatic(DiscDye.CYAN, 53.37578057317173,
                0x062425, 0x0A3A3B, 0x0C4849, 0x0F5B5C, 0x17888A, 0x199395, 0x1CA6A9);
        assertDefaultAutomatic(DiscDye.PURPLE, 52.70462693238888,
                0x190625, 0x280A3B, 0x310C49, 0x3E0F5C, 0x5D178A, 0x641995, 0x721CA9);
        assertDefaultAutomatic(DiscDye.BLUE, 53.02610886801779,
                0x0A103A, 0x0D1651, 0x101A5E, 0x131F72, 0x1B2CA0, 0x1C2FAB, 0x2034BE);
        assertDefaultAutomatic(DiscDye.BROWN, 70.14570324901254,
                0x251306, 0x3B1E0A, 0x49250C, 0x5C2F0F, 0x8A4717, 0x954D19, 0xA9571C);
        assertDefaultAutomatic(DiscDye.GREEN, 78.65634079134388,
                0x060801, 0x171E05, 0x212C07, 0x30400B, 0x526D12, 0x5A7814, 0x698C17);
        assertDefaultAutomatic(DiscDye.RED, 69.99583700931197,
                0x250806, 0x3B0D0A, 0x49100C, 0x5C140F, 0x8A1D17, 0x952019, 0xA9241C);
        assertDefaultAutomatic(DiscDye.BLACK, 52.17080996170593,
                0x535353, 0x606060, 0x686868, 0x737373, 0x8E8E8E, 0x949494, 0xA0A0A0);
    }


    // ── 規則そのもの ───────────────────────────────────────────────────

    /** 未染色曲の自動配色のアクセントは、色相を持つ色で床を必達にする。 */
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
                final double e = DiscPalette.automaticAccent(accentDye, board).deltaE();
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
     * 明示した有彩色のアクセントは盤面に関係なく染料ごとに1色 (2026-10-10 本人「相対じゃなく絶対」)。
     * 全 256 配色を並べると、盤面との色差を探す旧規則で青の盤面の黄が黒、白の盤面の桃が暗い赤紫・
     * 赤紫が明るいピンクになっていた。同じ染料どうしだけは {@link DiscPalette#sameDyeAccent} の特別な縁。
     */
    @Test
    void explicitChromaticAccentIsTheSameOnEveryBoard() {
        for (DiscDye accentDye : DiscDye.values()) {
            if (!accentDye.isChromatic()) continue;
            final DiscPalette.Accent reference = DiscPalette.accent(accentDye, DiscDye.WHITE, DiscPalette.board(DiscDye.WHITE));
            for (DiscDye boardDye : DiscDye.values()) {
                if (boardDye == accentDye) continue;
                final DiscPalette.Accent accent = DiscPalette.accent(accentDye, boardDye, DiscPalette.board(boardDye));
                assertArrayEquals(reference.steps(), accent.steps(), boardDye.id() + " x " + accentDye.id());
            }
            final int main = reference.main();
            final int spread = Math.max(ColorMath.red(main), Math.max(ColorMath.green(main), ColorMath.blue(main)))
                    - Math.min(ColorMath.red(main), Math.min(ColorMath.green(main), ColorMath.blue(main)));
            assertTrue(spread >= 48, "有彩色の縁が灰に見える: " + accentDye.id());
        }
        // HappyMeek「Pink and Magenta dyes seem to be swapped」: 桃は赤紫より明るい。
        assertTrue(ColorMath.hslLightness(DiscPalette.accent(DiscDye.PINK, DiscPalette.board(DiscDye.WHITE)).main())
                > ColorMath.hslLightness(DiscPalette.accent(DiscDye.MAGENTA, DiscPalette.board(DiscDye.WHITE)).main()));
    }

    /** 同じ染料どうしは縁を盤面から浮かせる (2026-10-10 本人「同色コンビのときは特別に」)。 */
    @Test
    void sameDyeAccentStandsOutFromItsBoard() {
        for (DiscDye dye : DiscDye.values()) {
            final DiscPalette.Board board = DiscPalette.board(dye);
            final DiscPalette.Accent same = DiscPalette.accent(dye, dye, board);
            for (int step = 1; step < same.size(); step++) {
                assertTrue(ColorMath.hslLightness(same.step(step)) > ColorMath.hslLightness(same.step(step - 1)),
                        "同色の縁の陰影が単調でない: " + dye.id());
            }
            assertTrue(same.step(0) != 0, "同色の縁の暗部が純黒: " + dye.id());
            assertTrue(ColorMath.hslLightness(same.step(same.size() - 1)) <= 84.5, "同色の縁が白に飛んだ: " + dye.id());
            assertTrue(ColorMath.deltaE(board.dominant(), same.main()) >= 20.0,
                    "同じ染料どうしの縁が盤面に溶ける: " + dye.id() + " dE=" + ColorMath.deltaE(board.dominant(), same.main()));
        }
    }

    /** 明るい染料 (桃) の盤面でも、最も明るい段が白に飛ばない。 */
    @Test
    void chromaticBoardsDoNotTurnWhite() {
        for (DiscDye dye : DiscDye.values()) {
            if (!dye.isChromatic()) continue;
            final DiscPalette.Board board = DiscPalette.board(dye);
            assertTrue(ColorMath.hslLightness(board.step(board.size() - 1)) <= 84.5, "白に飛んだ: " + dye.id());
        }
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
                if (ramp[i] == boardDye) {
                    continue; // 同じ染料どうしは sameDyeAccent (別のテスト)
                }
                final DiscPalette.Accent accent = DiscPalette.accent(ramp[i], boardDye, board);
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
            if (boardDye != DiscDye.WHITE) {
                assertTrue(previousMain >= 0xD0, "white が十分に明るくない: " + boardDye.id());
            }
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
                final DiscPalette.Accent a1 = DiscPalette.accent(accentDye, boardDye, b1);
                final DiscPalette.Accent a2 = DiscPalette.accent(accentDye, boardDye, b2);
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

    @Test
    void chromaticAccentsKeepEveryTintLayerVisible() {
        for (DiscDye boardDye : DiscDye.values()) {
            final DiscPalette.Board board = DiscPalette.board(boardDye);
            for (DiscDye accentDye : DiscDye.values()) {
                if (!accentDye.isChromatic()) {
                    continue;
                }
                final int[] steps = DiscPalette.accent(accentDye, board).steps();
                int previous = -1;
                for (int i = 0; i < steps.length; i++) {
                    final int step = steps[i];
                    final String label = boardDye.id() + " x " + accentDye.id() + " step=" + i;
                    assertTrue(step != 0, "色相が純黒へ潰れた: " + label);
                    assertTrue(ColorMath.red(step) != ColorMath.green(step)
                                    || ColorMath.green(step) != ColorMath.blue(step),
                            "有彩色のはずの段が無彩色になった: " + label);
                    assertTrue(step != previous, "隣接する tint 段が同じ色になった: " + label);
                    previous = step;
                }
            }
        }
    }
}
