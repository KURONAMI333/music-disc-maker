#!/usr/bin/env python3
"""スピーカーの仮ブロックテクスチャ生成器 (機能確認用)。

見た目は確認帯なので、ここでは「設置して区別がつく」以上のことをしない。
候補比較の本制作は MineTexture 側で別途行う。
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
    "block",
)

BODY = (58, 58, 62, 255)
BODY_HI = (76, 76, 82, 255)
BODY_SH = (40, 40, 44, 255)
GRILLE = (34, 34, 38, 255)
CONE = (92, 84, 70, 255)
CONE_SH = (66, 60, 50, 255)
FRAME = (28, 28, 32, 255)


def base(px):
    for y in range(16):
        for x in range(16):
            px[x, y] = BODY
    for x in range(16):
        px[x, 0] = BODY_HI
        px[x, 15] = BODY_SH
    for y in range(16):
        px[0, y] = BODY_HI
        px[15, y] = BODY_SH


def side():
    """正面/側面: グリル地に丸いコーン 1 つ。"""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    base(px)
    # グリル (中央 12x12 の凹み)
    for y in range(2, 14):
        for x in range(2, 14):
            px[x, y] = GRILLE
    for x in range(2, 14):
        px[x, 2] = FRAME
        px[x, 13] = FRAME
    for y in range(2, 14):
        px[2, y] = FRAME
        px[13, y] = FRAME
    # コーン (半径 4 の円 + 下側だけ影)
    cx, cy, r = 7.5, 7.5, 4.0
    for y in range(16):
        for x in range(16):
            d = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
            if d <= r:
                px[x, y] = CONE_SH if (y + 0.5) > cy else CONE
    return im


def top():
    """上面/底面: 素の筐体。"""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    base(px)
    for x in range(3, 13):
        px[x, 4] = BODY_SH
        px[x, 11] = BODY_HI
    return im


def main():
    os.makedirs(OUT, exist_ok=True)
    side().save(os.path.join(OUT, "speaker_side.png"))
    top().save(os.path.join(OUT, "speaker_top.png"))
    print("wrote speaker_side.png / speaker_top.png ->", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
