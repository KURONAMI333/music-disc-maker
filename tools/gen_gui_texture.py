#!/usr/bin/env python3
"""Music Disc Maker の GUI 背景テクスチャを生成する。

バニラ container 風グレーパネル + 凹みスロット枠に、**バニラの実物スプライト**を合成:
- URL 入力欄 = 金床の名前入力欄 (anvil/text_field) → MC ネイティブな見た目
- 入力→出力の矢印 = かまどの調理矢印 (furnace/burn_progress)
スロット位置は MusicDiscMakerMenu / Screen と一致させること。

レイアウト 200x166。256x256 シートの左上に描く。実行: python tools/gen_gui_texture.py
"""

from __future__ import annotations

import os

import numpy as np
from PIL import Image, ImageDraw

W, H, SHEET = 200, 166, 256
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MINETEX = os.path.join(ROOT, "..", "_tools", "MineTexture")
VSPRITE = os.path.join(
    MINETEX, "tool_data", "vanilla_textures", "gui", "sprites", "container"
)
VCONTAINER = os.path.join(MINETEX, "tool_data", "vanilla_textures", "gui", "container")
OUT = os.path.join(
    ROOT,
    "src",
    "main",
    "resources",
    "assets",
    "music_disc_maker",
    "textures",
    "gui",
    "music_disc_maker.png",
)

PANEL = (198, 198, 198, 255)
LIGHT = (255, 255, 255, 255)
DARK = (85, 85, 85, 255)
SLOT = (139, 139, 139, 255)
SLOT_BLUE = (120, 140, 162, 255)
SLOT_DARK = (55, 55, 55, 255)

# 枠左上 (= 中身座標 -1)。Menu と一致させること。
INPUT = (61, 46)
OUTPUT = (113, 46)
INV_X0, INV_Y0 = 18, 81
HOTBAR_Y = 139
FIELD_POS = (6, 21)  # anvil text_field (110x16)
ARROW_POS = (80, 44)  # 金床の灰色矢印を 入力→出力 の間に (やや上げてスロット中央へ)


def slot(d: ImageDraw.ImageDraw, sx: int, sy: int, inner=SLOT) -> None:
    d.rectangle([sx, sy, sx + 17, sy + 17], fill=inner)
    d.line([(sx, sy), (sx + 17, sy)], fill=SLOT_DARK)
    d.line([(sx, sy), (sx, sy + 17)], fill=SLOT_DARK)
    d.line([(sx, sy + 17), (sx + 17, sy + 17)], fill=LIGHT)
    d.line([(sx + 17, sy), (sx + 17, sy + 17)], fill=LIGHT)


def main() -> None:
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    img = Image.new("RGBA", (SHEET, SHEET), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # パネル + 隆起ベベル
    d.rectangle([0, 0, W - 1, H - 1], fill=PANEL)
    for i in range(2):
        d.line([(i, i), (W - 1 - i, i)], fill=LIGHT)
        d.line([(i, i), (i, H - 1 - i)], fill=LIGHT)
        d.line([(i, H - 1 - i), (W - 1 - i, H - 1 - i)], fill=DARK)
        d.line([(W - 1 - i, i), (W - 1 - i, H - 1 - i)], fill=DARK)

    # バニラ実物を合成: URL欄 = 金床の名前入力欄、矢印 = 金床の灰色矢印 (完全に同じ見た目)
    field = Image.open(os.path.join(VSPRITE, "anvil", "text_field.png")).convert("RGBA")
    img.alpha_composite(field, FIELD_POS)

    # 金床テクスチャから矢印部分 (x100-129,y48-61) を切り出し。明るい灰(パネル背景)は
    # 透明化して、暗い矢印だけをパネルに重ねる。
    anvil = Image.open(os.path.join(VCONTAINER, "anvil.png")).convert("RGBA")
    arrow = np.array(
        anvil.crop((98, 45, 130, 62))
    )  # 32x17 (下端まで含めてクリップ回避)
    bg = (arrow[..., 0] > 175) & (arrow[..., 1] > 175) & (arrow[..., 2] > 175)
    arrow[bg, 3] = 0
    img.alpha_composite(Image.fromarray(arrow, "RGBA"), ARROW_POS)

    # スロット (maker も普通のグレー = 他GUIと同じ)
    slot(d, *INPUT)
    slot(d, *OUTPUT)
    for r in range(3):
        for c in range(9):
            slot(d, INV_X0 + c * 18, INV_Y0 + r * 18)
    for c in range(9):
        slot(d, INV_X0 + c * 18, HOTBAR_Y)

    img.save(OUT)
    print(f"generated GUI texture ({W}x{H}, anvil field + furnace arrow) -> {OUT}")


if __name__ == "__main__":
    main()
