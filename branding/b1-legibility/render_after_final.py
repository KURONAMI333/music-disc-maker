#!/usr/bin/env python3
"""実装後 (MusicDiscMakerScreen / GoldenJukeboxScreen) の座標・色で最終確認用に描き直す。

render_legibility.py の Canvas/Font/maker_base/juke_base/draw_jp を再利用し、
実装で使った座標・色 (板なし・配置B・色4 相当) をそのままここに書き写す。
render_legibility.py 自体 (候補シート生成器) は変更しない。
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image

import render_legibility as R

HERE = Path(__file__).resolve().parent
SCALE = 4

# ── 実装値 (MusicDiscMakerScreen.java) ──────────────────────────
MAKER_TEXT_X = 12  # FAILED_TEXT_X
MAKER_TEXT_Y = 51
MAKER_COLOR = 0xB02020

# ── 実装値 (GoldenJukeboxScreen.java) ───────────────────────────
JUKE_FAIL_X = R.FAIL_X  # 8
JUKE_FAIL_Y = R.FAIL_Y  # 119
JUKE_COLOR = 0xB02020


def maker_final(label: str) -> R.Canvas:
    c = R.maker_base()
    R.draw_jp(c, MAKER_TEXT_X, MAKER_TEXT_Y, label, MAKER_COLOR, shadow=True)
    return c


def juke_final(label: str) -> R.Canvas:
    c = R.juke_base()
    R.draw_jp(c, JUKE_FAIL_X, JUKE_FAIL_Y, label, JUKE_COLOR, shadow=True)
    return c


def up(canvas: R.Canvas) -> Image.Image:
    img = canvas.image()
    return img.resize((img.width * SCALE, img.height * SCALE), Image.NEAREST)


def stack(im_long: Image.Image, im_short: Image.Image, gap: int = 16) -> Image.Image:
    w = max(im_long.width, im_short.width)
    h = im_long.height + gap + im_short.height
    out = Image.new("RGB", (w, h), (232, 232, 232))
    out.paste(im_long, (0, 0))
    out.paste(im_short, (0, im_long.height + gap))
    return out


def main() -> None:
    m_long = up(maker_final(R.MAKER_LONG[1]))
    m_short = up(maker_final(R.MAKER_SHORT[1]))
    out_m = stack(m_long, m_short)
    out_m.save(HERE / "after_final_maker.png")
    print("wrote", HERE / "after_final_maker.png", out_m.size)
    print(
        "  long:",
        R.MAKER_LONG[1],
        R.MAKER_LONG[2],
        "px / short:",
        R.MAKER_SHORT[1],
        R.MAKER_SHORT[2],
        "px",
    )

    j_long = up(juke_final(R.JUKE_LONG[1]))
    j_short = up(juke_final(R.JUKE_SHORT[1]))
    out_j = stack(j_long, j_short)
    out_j.save(HERE / "after_final_jukebox.png")
    print("wrote", HERE / "after_final_jukebox.png", out_j.size)
    print(
        "  long:",
        R.JUKE_LONG[1],
        R.JUKE_LONG[2],
        "px / short:",
        R.JUKE_SHORT[1],
        R.JUKE_SHORT[2],
        "px",
    )


if __name__ == "__main__":
    main()
