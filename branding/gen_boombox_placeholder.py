#!/usr/bin/env python3
"""ブームボックスの仮ブロックテクスチャ生成器 (機能確認用)。

見た目は確認帯なので、ここでは「設置して区別がつく」以上のことをしない。
候補比較の本制作は MineTexture 側で別途行う。

正面 = ツインコーン + 中央のカセット窓 / 側面 = 素の筐体 + 取っ手の帯 / 上面 = 操作パネル。
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

BODY = (48, 46, 52, 255)
BODY_HI = (66, 64, 72, 255)
BODY_SH = (33, 32, 37, 255)
GRILLE = (28, 27, 32, 255)
CONE = (96, 88, 72, 255)
CONE_SH = (68, 62, 52, 255)
FRAME = (22, 21, 26, 255)
WINDOW = (52, 60, 66, 255)
ACCENT = (206, 168, 68, 255)


def base(px: "Image.Image") -> None:
    for y in range(16):
        for x in range(16):
            px[x, y] = BODY
    for x in range(16):
        px[x, 0] = BODY_HI
        px[x, 15] = BODY_SH
    for y in range(16):
        px[0, y] = BODY_HI
        px[15, y] = BODY_SH


def _cone(px, cx: float, cy: float, r: float) -> None:
    for y in range(16):
        for x in range(16):
            d = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
            if d <= r:
                px[x, y] = CONE_SH if (y + 0.5) > cy else CONE


def front() -> Image.Image:
    """正面: 左右のコーン + 中央のカセット窓。"""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    base(px)
    for y in range(4, 13):
        for x in range(1, 15):
            px[x, y] = GRILLE
    for x in range(1, 15):
        px[x, 3] = FRAME
        px[x, 13] = FRAME
    _cone(px, 3.5, 8.5, 2.6)
    _cone(px, 12.5, 8.5, 2.6)
    # 中央のカセット窓 (ここで「ラジカセ」と読ませる)
    for y in range(6, 11):
        for x in range(6, 10):
            px[x, y] = WINDOW
    for x in range(6, 10):
        px[x, 6] = FRAME
        px[x, 10] = FRAME
    return im


def side() -> Image.Image:
    """側面: 素の筐体 + 取っ手の帯。"""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    base(px)
    for x in range(3, 13):
        px[x, 2] = BODY_SH
        px[x, 3] = ACCENT
    for y in range(6, 13):
        for x in range(3, 13):
            px[x, y] = BODY_SH if (x + y) % 4 == 0 else BODY
    return im


def top() -> Image.Image:
    """上面: 操作パネル (ボタン列)。"""
    im = Image.new("RGBA", (16, 16))
    px = im.load()
    base(px)
    for y in range(5, 11):
        for x in range(2, 14):
            px[x, y] = BODY_SH
    for i in range(4):
        bx = 3 + i * 3
        for y in range(7, 9):
            for x in range(bx, bx + 2):
                px[x, y] = ACCENT if i == 0 else BODY_HI
    return im


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    front().save(os.path.join(OUT, "boombox_front.png"))
    side().save(os.path.join(OUT, "boombox_side.png"))
    top().save(os.path.join(OUT, "boombox_top.png"))
    print(
        "wrote boombox_front.png / boombox_side.png / boombox_top.png ->",
        os.path.normpath(OUT),
    )


if __name__ == "__main__":
    main()
