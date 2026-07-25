#!/usr/bin/env python3
"""ブームボックスの仮アイテムテクスチャ生成器 (機能確認用)。

見た目は確認帯なので、ここでは「インベントリで区別がつく」以上のことをしない。
候補比較の本制作は MineTexture 側で別途行う。

ブームボックスは純アイテム (設置しない) なので、生成するのは item スプライト 1 枚だけ。
正面向きのラジカセ = 取っ手 + ツインコーン + 中央のカセット窓。
"""

import os

from PIL import Image

OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..",
    "common",
    "src",
    "main",
    "resources",
    "assets",
    "music_disc_maker",
    "textures",
    "item",
)

BODY = (48, 46, 52, 255)
BODY_HI = (66, 64, 72, 255)
BODY_SH = (33, 32, 37, 255)
GRILLE = (28, 27, 32, 255)
CONE = (96, 88, 72, 255)
CONE_SH = (68, 62, 52, 255)
FRAME = (22, 21, 26, 255)
WINDOW = (52, 60, 66, 255)
ACCENT = (206, 168, 68, 255)

BODY_LEFT = 1
BODY_RIGHT = 14
BODY_TOP = 5
BODY_BOTTOM = 13


def _cone(px, cx: float, cy: float, r: float) -> None:
    for y in range(16):
        for x in range(16):
            d = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
            if d <= r:
                px[x, y] = CONE_SH if (y + 0.5) > cy else CONE


def boombox() -> Image.Image:
    """正面向きのラジカセ。背景は透過 (item/generated)。"""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()

    # 取っ手 (上辺の帯 + 両肩の立ち上がり)
    for x in range(5, 11):
        px[x, 3] = ACCENT
    px[4, 4] = BODY_HI
    px[11, 4] = BODY_HI

    # 筐体
    for y in range(BODY_TOP, BODY_BOTTOM + 1):
        for x in range(BODY_LEFT, BODY_RIGHT + 1):
            px[x, y] = BODY
    for x in range(BODY_LEFT, BODY_RIGHT + 1):
        px[x, BODY_TOP] = BODY_HI
        px[x, BODY_BOTTOM] = BODY_SH
    for y in range(BODY_TOP, BODY_BOTTOM + 1):
        px[BODY_LEFT, y] = BODY_HI
        px[BODY_RIGHT, y] = BODY_SH

    # グリル面
    for y in range(BODY_TOP + 2, BODY_BOTTOM):
        for x in range(BODY_LEFT + 1, BODY_RIGHT):
            px[x, y] = GRILLE
    for x in range(BODY_LEFT + 1, BODY_RIGHT):
        px[x, BODY_TOP + 1] = FRAME

    # ツインコーン
    _cone(px, 4.0, 9.5, 2.2)
    _cone(px, 12.0, 9.5, 2.2)

    # 中央のカセット窓 (ここで「ラジカセ」と読ませる)
    for y in range(8, 11):
        for x in range(7, 9):
            px[x, y] = WINDOW
    for x in range(7, 9):
        px[x, 8] = FRAME

    return im


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "boombox.png")
    boombox().save(path)
    print("wrote boombox.png ->", os.path.normpath(path))


if __name__ == "__main__":
    main()
