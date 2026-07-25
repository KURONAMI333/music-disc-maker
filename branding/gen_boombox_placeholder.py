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

# 明度ランプは 4 段。影側は寒色へ hue を回す (単純暗色化を避ける = critique の hue_shift)。
BODY_HI = (126, 124, 140, 255)
BODY = (100, 98, 112, 255)
BODY_SH = (66, 66, 94, 255)
BODY_DK = (42, 43, 74, 255)
GRILLE = (60, 58, 76, 255)
CONE_HI = (168, 152, 116, 255)
CONE = (140, 126, 96, 255)
CONE_SH = (92, 86, 80, 255)
FRAME = (38, 36, 48, 255)
WINDOW = (84, 100, 114, 255)
ACCENT = (206, 168, 68, 255)
# item アイコンの暗い輪郭 (critique の outline_rate が必須ゲートにしている)。
OUTLINE = (18, 17, 26, 255)

BODY_LEFT = 1
BODY_RIGHT = 14
BODY_TOP = 5
BODY_BOTTOM = 13


def _cone(px, cx: float, cy: float, r: float) -> None:
    for y in range(16):
        for x in range(16):
            d = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
            if d > r:
                continue
            if d > r - 0.9:
                px[x, y] = CONE_SH
            elif (y + 0.5) > cy:
                px[x, y] = CONE
            else:
                px[x, y] = CONE_HI


def _outline(px) -> None:
    """不透明シルエットの外周 1px を暗い輪郭に置き換える。"""
    opaque = [[px[x, y][3] > 0 for y in range(16)] for x in range(16)]
    edge = []
    for x in range(16):
        for y in range(16):
            if not opaque[x][y]:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if not (0 <= nx < 16 and 0 <= ny < 16) or not opaque[nx][ny]:
                    edge.append((x, y))
                    break
    for x, y in edge:
        px[x, y] = OUTLINE


def boombox() -> Image.Image:
    """正面向きのラジカセ。背景は透過 (item/generated)。"""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()

    # 取っ手。2px 厚にしてあるのは、後段の輪郭処理が 1px の帯を丸ごと輪郭色で
    # 塗り潰してしまう (= 芯が残らない) のと、1px の突起が lint の nub になるため。
    for x in range(4, 12):
        px[x, 2] = BODY_SH
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

    # 筐体の内側に濃影を 1 列入れて階調を 4 段にする (のっぺり回避)。
    for x in range(BODY_LEFT + 1, BODY_RIGHT):
        px[x, BODY_BOTTOM - 1] = BODY_DK

    _outline(px)
    return im


def main() -> None:
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, "boombox.png")
    boombox().save(path)
    print("wrote boombox.png ->", os.path.normpath(path))


if __name__ == "__main__":
    main()
