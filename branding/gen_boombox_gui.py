#!/usr/bin/env python3
"""ブームボックス専用 GUI テクスチャの生成器。

このスクリプトが GUI レイアウトの座標の正本。同じ座標を BoomboxScreen が手写しする。

強化版ジュークボックスの GUI は<b>流用しない</b> (kura 裁定)。あちらは transport 一式 +
シークバー + 2 本のスライダーを持つ大きい画面で、携帯プレイヤーには重い。こちらは部品 3 つだけ:
ディスクスロット / 音量スライダー / 指向性トグル。パネルの縁とスロット枠・指向性グリフだけは
バニラ準拠の共通描画を共有する (様式を割らないため)。

--table を付けると widget 矩形を print する (レイアウト検証の一次証拠)。
"""

import argparse
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from gen_golden_jukebox_gui import draw_slot, sprite_directional  # noqa: E402

# ── レイアウト正本 (panel-local, leftPos/topPos 相対) ─────────────────────
W, H = 176, 166  # imageWidth / imageHeight
SHEET = 256  # blit sheet size

TITLE = (8, 6)  # titleLabelX / titleLabelY (バニラ既定)
DISC = (8, 18)  # ディスクスロット (menu slot 座標)
TEXT_X = 30  # 曲名テキストの左端 (スロットの右)
TEXT_Y = 23
VOLUME = (8, 42, 140, 15)  # 音量スライダー
DIRECTIONAL = (152, 41, 16, 16)  # 指向性トグル (音量バーの横・枠つき)
INV_LABEL = (8, 72)  # "Inventory" ラベル
INV_X, INV_Y0 = 8, 84  # プレイヤーインベントリ 3 行の起点
HOT_Y = 142  # ホットバー

# 指向性スプライト (sheet 内 uv)。パネルは 176x166 なので x>=176 の帯は空いている。
SPR_DIR_V = 0
SPR_DIR_ON_U, SPR_DIR_OFF_U = 176, 192

# 色 (golden_jukebox / speaker GUI と同じバニラ準拠パレット)
PANEL = (198, 198, 198, 255)
BLACK = (0, 0, 0, 255)
HL = (255, 255, 255, 255)
SH = (55, 55, 55, 255)


def draw_panel(img: "Image.Image") -> None:
    px = img.load()
    for y in range(H):
        for x in range(W):
            px[x, y] = PANEL
    d = ImageDraw.Draw(img)
    for t in range(1, 3):
        d.line([(t, t), (W - 1 - t, t)], fill=HL)
        d.line([(t, t), (t, H - 1 - t)], fill=HL)
    for t in range(1, 3):
        d.line([(t, H - 1 - t), (W - 1 - t, H - 1 - t)], fill=SH)
        d.line([(W - 1 - t, t), (W - 1 - t, H - 1 - t)], fill=SH)
    d.rectangle([0, 0, W - 1, H - 1], outline=BLACK)


def build() -> "Image.Image":
    img = Image.new("RGBA", (SHEET, SHEET), (0, 0, 0, 0))
    draw_panel(img)
    px = img.load()
    # スロット枠 (枠は slot 座標の 1px 外側)
    draw_slot(px, DISC[0] - 1, DISC[1] - 1)
    for row in range(3):
        for col in range(9):
            draw_slot(px, INV_X - 1 + col * 18, INV_Y0 - 1 + row * 18)
    for col in range(9):
        draw_slot(px, INV_X - 1 + col * 18, HOT_Y - 1)
    # 指向性トグルのグリフ (ON = 片側だけに広がる波 / OFF = 左右対称)
    img.alpha_composite(sprite_directional(True), (SPR_DIR_ON_U, SPR_DIR_V))
    img.alpha_composite(sprite_directional(False), (SPR_DIR_OFF_U, SPR_DIR_V))
    return img


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--table", action="store_true")
    args = parser.parse_args()

    img = build()
    out = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..",
        "common",
        "src",
        "main",
        "resources",
        "assets",
        "music_disc_maker",
        "textures",
        "gui",
        "boombox.png",
    )
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out)
    print("wrote ->", os.path.normpath(out))

    if args.table:
        print(f"panel       = 0,0 {W}x{H}")
        print(f"title       = {TITLE}")
        print(f"disc slot   = {DISC}")
        print(f"text        = x{TEXT_X} y{TEXT_Y}")
        print(f"volume      = {VOLUME}")
        print(
            f"directional = {DIRECTIONAL}  sprites u={SPR_DIR_ON_U}/{SPR_DIR_OFF_U} v={SPR_DIR_V}"
        )
        print(f"inv label   = {INV_LABEL}")
        print(f"inv rows    = x{INV_X} y{INV_Y0} / hotbar y{HOT_Y}")


if __name__ == "__main__":
    main()
