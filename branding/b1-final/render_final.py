#!/usr/bin/env python3
"""実装後の座標・寸法で両画面を実寸合成する (実機が使えないので、これが目視の一次証拠)。

実テクスチャ (伸ばした後のもの) + vanilla の実 bitmap font + ja_jp.json の実訳語 + 実装した
折り返し規則で描く。出すのは 4 状態:

    制作機   失敗あり (最長の文) / 失敗なし
    金ジューク 失敗あり (最長の文) / 失敗なし

あわせて、赤字が既存の要素に 1px も重なっていないことを<b>ピクセルで</b>確かめる
(帯だけを描いた版と、帯の無い版の差分を取り、赤字が乗った画素の下が無地だったか見る)。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "b1-legibility"))
sys.path.insert(0, str(HERE.parent / "b1-sentence"))
sys.path.insert(0, str(HERE.parent / "b1-gui-candidates"))
sys.path.insert(0, str(HERE))

import render_sentence  # noqa: E402  全角グリフを 8 行に収める差し替えを取り込む
import render_legibility as R  # noqa: E402
import metrics as M  # noqa: E402
import sweep  # noqa: E402  ハングル幅の補正
from mcgui import VANILLA_GUI, nine_slice  # noqa: E402

C = R.Canvas
FONT = R.FONT
TEXT = R.TEXT
ERROR = 0xB02020
SCALE = 3
LANG = R.LANG

BTN = Image.open(VANILLA_GUI / "sprites/widget/button.png")

# ── 実装した座標 (Java 側と一致させる) ───────────────────────────────
M_W, M_H = 200, 182
M_FAIL = (12, 68, 180, 2)  # x, 1 行目 y, 折り返し幅, 確保行数
M_INV_Y = M_H - 94  # 88

J_W, J_H = 176, 242
J_FAIL = (8, 119, 160, 3)
J_INV_Y = J_H - 94  # 148

LH = 9  # font.lineHeight

INV = "持ち物"
TRACK = "サンプル楽曲タイトル"
AUTHOR = "アーティスト名"


def maker_base() -> C:
    c = C.from_image(R.MAKER_TEX.crop((0, 0, M_W, M_H)))
    R.draw_jp(c, 8, 6, LANG["gui.music_disc_maker.title"], TEXT)
    R.draw_jp(c, 19, M_INV_Y, INV, TEXT)
    FONT.draw(c, 11, 27, FONT.plain_substr_by_width(R.M_URL, 98), 0xE0E0E0, True)
    c.blit(R.BLANK_DISC.resize((16, 16), Image.NEAREST), 62, 47)
    for x, key in ((120, "paste"), (158, "clear")):
        c.blit(nine_slice(BTN, 36, 18), x, 20)
        label = LANG["gui.music_disc_maker." + key]
        R.draw_jp(c, x + (36 - R.jp_width(label)) // 2, 25, label, 0xFFFFFF, True)
    return c


def juke_base() -> C:
    c = C.from_image(R.JUKE_TEX.crop((0, 0, J_W, J_H)))
    R.draw_jp(c, 8, J_INV_Y, INV, TEXT)
    R.draw_jp(c, 32, 19, TRACK, TEXT)
    R.draw_jp(c, 32, 31, AUTHOR, 0x606060)
    c.blit(R.JUKE_TEX, 8, 46, u=176, v=0, w=20, h=20)  # play
    c.blit(R.JUKE_TEX, 148, 48, u=232, v=0, w=16, h=16)  # loop off
    c.blit(R.JUKE_TEX, 152, 101, u=192, v=24, w=16, h=16)  # dir off
    c.fill(32, 48, 144, 64, 0xFF161616)
    c.fill(33, 49, 143, 63, 0xFF2C2C2C)
    c.fill(33, 49, 78, 63, 0xFFCEA844)
    FONT.draw(c, 32, 68, "1:12", 0x606060)
    FONT.draw(c, 144 - FONT.width("3:41"), 68, "3:41", 0x606060)
    render_sentence.slider(
        c,
        8,
        84,
        160,
        0.8,
        LANG["gui.music_disc_maker.golden_jukebox.volume"].replace("%s%%", "80%"),
    )
    render_sentence.slider(
        c,
        8,
        102,
        140,
        0.5,
        LANG["gui.music_disc_maker.golden_jukebox.range"].replace("%s", "64"),
    )
    return c


def band(c: C, geom, text: str | None):
    """実装と同じ規則で失敗の文を描く (確保行数を超えた分は描かない)。"""
    if text is None:
        return c
    x, y, w, lines = geom
    for i, ln in enumerate(M.split_vanilla(text, w)[:lines]):
        R.draw_jp(c, x, y + i * LH, ln, ERROR, True)
    return c


def longest(keys: list[str], w: int) -> tuple[str, str]:
    """幅 w で行数が最大 → 総幅が最大 の文 (キー, 本文)。"""
    best = None
    for k in keys:
        t = LANG["gui.music_disc_maker.failed." + k]
        score = (len(M.split_vanilla(t, w)), R.jp_width(t))
        if best is None or score > best[0]:
            best = (score, k, t)
    return best[1], best[2]


# ── 重なりのピクセル検査 ────────────────────────────────────────────
def overlap_report(name: str, base: C, filled: C, geom) -> list[str]:
    """赤字が乗った画素の下が、帯を描く前と同じ「無地」だったかを見る。"""
    a = np.array(base.image().convert("RGB"), dtype=int)
    b = np.array(filled.image().convert("RGB"), dtype=int)
    diff = np.any(a != b, axis=-1)
    ys, xs = np.nonzero(diff)
    out = [
        f"{name}: 赤字が変えた画素 {len(ys)} 個  y={ys.min()}..{ys.max()}  x={xs.min()}..{xs.max()}"
    ]
    x0, y0, w, lines = geom
    top, bottom = y0, y0 + (lines - 1) * LH + 8
    if ys.min() < top or ys.max() > bottom:
        out.append(f"  NG: 確保した帯 y={top}..{bottom} からはみ出している")
    else:
        out.append(f"  OK: 確保した帯 y={top}..{bottom} の中に収まっている")
    # 下に敷いてあった色が板の無地 1 色だけだったか (= 既存の要素に重なっていないか)
    under = {tuple(v) for v in a[diff]}
    plain = tuple(a[y0 + 4, x0 + w - 2])  # 帯の中の、文字が来ない右端
    stray = under - {plain}
    if stray:
        out.append(
            f"  NG: 無地でない画素の上に描いている ({len(stray)} 色) {sorted(stray)[:4]}"
        )
    else:
        out.append(f"  OK: 下は板の無地 1 色 {plain} だけ = 既存要素と重なっていない")
    return out


# ── シート組み ──────────────────────────────────────────────────────
BG, PAD = (232, 232, 232), (60, 60, 60)
JP = ImageFont.truetype("C:/Windows/Fonts/meiryo.ttc", 19)
JPB = ImageFont.truetype("C:/Windows/Fonts/meiryob.ttc", 27)
JPS = ImageFont.truetype("C:/Windows/Fonts/meiryo.ttc", 16)


def cell(c: C) -> Image.Image:
    img = c.image()
    p = Image.new("RGB", (img.width + 28, img.height + 28), PAD)
    p.paste(img.convert("RGB"), (14, 14), img)
    return p.resize((p.width * SCALE, p.height * SCALE), Image.NEAREST)


def build(rows) -> Image.Image:
    GAP, CAPH, HEADH = 26, 78, 54
    W = max(sum(i.width + GAP for _, i in r[1]) + GAP for r in rows)
    Hh = sum(max(i.height for _, i in r[1]) + CAPH + HEADH for r in rows) + 140
    out = Image.new("RGB", (W, Hh), BG)
    d = ImageDraw.Draw(out)
    d.text(
        (30, 28),
        "Music Disc Maker — 失敗表示を文にした後の実寸 (実装後の座標)",
        font=JPB,
        fill=(20, 20, 20),
    )
    d.text(
        (30, 70),
        "実テクスチャ・実訳語 (ja_jp)・vanilla の実文字幅と折り返し。赤字 0xB02020 + 影・板なし・左寄せ。"
        "拡大 3x。上部の操作系は簡略描画",
        font=JPS,
        fill=(90, 90, 90),
    )
    y = 122
    for title, cells in rows:
        d.text((30, y), title, font=JPB, fill=(20, 20, 20))
        y += HEADH
        top, x = y, GAP
        for cap, img in cells:
            out.paste(img, (x, top))
            cy = top + img.height + 10
            for line in cap:
                d.text((x, cy), line, font=JP, fill=(40, 40, 40))
                cy += 25
            x += img.width + GAP
        y = top + max(i.height for _, i in cells) + CAPH
    return out


def main() -> int:
    maker_keys = [
        "botcheck",
        "private",
        "region",
        "age",
        "refused",
        "connection",
        "unsupported",
        "blocked",
    ]
    juke_keys = maker_keys + ["muted", "limit", "generic"]
    mk, mtext = longest(maker_keys, M_FAIL[2])
    jk, jtext = longest(juke_keys, J_FAIL[2])

    m_fail, m_ok = band(maker_base(), M_FAIL, mtext), maker_base()
    j_fail, j_ok = band(juke_base(), J_FAIL, jtext), juke_base()

    # 高さを決めているのは英語 (幅 160 で 3 行になる文が 3 つある)。日本語は 2 行までしか
    # 来ないので、帯の下端が持ち物ラベルに触れないことは英語で確かめないと検証にならない。
    en = json.loads((R.ASSETS / "lang/en_us.json").read_text(encoding="utf-8"))
    ek, etext = max(
        ((k, en["gui.music_disc_maker.failed." + k]) for k in juke_keys),
        key=lambda kv: (len(M.split_vanilla(kv[1], J_FAIL[2])), R.jp_width(kv[1])),
    )
    j_en = band(juke_base(), J_FAIL, etext)

    lines = overlap_report("制作機 (ja)", m_ok, m_fail, M_FAIL)
    lines += overlap_report("金ジューク (ja)", j_ok, j_fail, J_FAIL)
    lines += overlap_report("金ジューク (en・3 行 = 高さを決めた最悪ケース)", j_ok, j_en, J_FAIL)

    for c, n in (
        (m_fail, "maker_failed"),
        (m_ok, "maker_ok"),
        (j_fail, "juke_failed"),
        (j_ok, "juke_ok"),
        (j_en, "juke_failed_en"),
    ):
        c.image().save(HERE / f"{n}.png")

    sheet = build(
        [
            (
                f"① 制作機 MusicDiscMakerScreen — imageHeight 166 → {M_H}（帯 2 行 x={M_FAIL[0]} 幅 {M_FAIL[2]}）",
                [
                    (
                        [
                            f"失敗あり（最長 = {mk}）",
                            f"帯 y={M_FAIL[1]} / {M_FAIL[1] + LH}",
                            f"持ち物ラベル y={M_INV_Y}",
                        ],
                        cell(m_fail),
                    ),
                    (["失敗なし（帯は描かない）", "空きは常時 2 行ぶん"], cell(m_ok)),
                ],
            ),
            (
                f"② 金のジュークボックス GoldenJukeboxScreen — imageHeight 224 → {J_H}（帯 3 行 x={J_FAIL[0]} 幅 {J_FAIL[2]}）",
                [
                    (
                        [
                            f"失敗あり（最長 = {jk}）",
                            f"帯 y={J_FAIL[1]} / {J_FAIL[1] + LH} / {J_FAIL[1] + 2 * LH}",
                            f"持ち物ラベル y={J_INV_Y}",
                        ],
                        cell(j_fail),
                    ),
                    (["失敗なし（帯は描かない）", "空きは常時 3 行ぶん"], cell(j_ok)),
                    (
                        [
                            f"英語・3 行（= 高さを決めた最悪ケース: {ek}）",
                            "日本語は 2 行までしか来ない",
                        ],
                        cell(j_en),
                    ),
                ],
            ),
        ]
    )
    p = HERE / "sheet_final.png"
    sheet.save(p)

    print("\n".join(lines))
    print(f"\n最長 制作機 [{mk}] {mtext}")
    print(f"      -> {M.split_vanilla(mtext, M_FAIL[2])}")
    print(f"最長 金ジューク [{jk}] {jtext}")
    print(f"      -> {M.split_vanilla(jtext, J_FAIL[2])}")
    print(f"最長 金ジューク en [{ek}] {etext}")
    print(f"      -> {M.split_vanilla(etext, J_FAIL[2])}")
    print(f"\nwrote {p} {sheet.size}")
    return 1 if any("NG" in ln for ln in lines) else 0


if __name__ == "__main__":
    raise SystemExit(main())
