#!/usr/bin/env python3
"""スピーカー設定 GUI テクスチャの生成器。

このスクリプトが GUI レイアウトの座標の正本。パネル (バニラ準拠の縁) だけを描き、スライダーは
SpeakerScreen が自前描画する。同じ座標を SpeakerScreen (widget) が手写しする。

--table を付けると widget 矩形を print する (レイアウト検証の一次証拠)。
"""

import argparse
import os

from PIL import Image, ImageDraw

# ── レイアウト正本 (panel-local, leftPos/topPos 相対) ─────────────────────
W, H = 176, 76  # imageWidth / imageHeight
SHEET = 256  # blit sheet size

TITLE = (8, 6)  # titleLabelX / titleLabelY (バニラ既定)
VOLUME = (8, 20, 160, 15)  # 音量スライダー
RANGE = (8, 38, 160, 15)  # 可聴範囲スライダー
STATUS_Y = 58  # リンク状態のテキスト

# 色 (golden_jukebox GUI と同じバニラ準拠パレット)
PANEL = (198, 198, 198, 255)
BLACK = (0, 0, 0, 255)
HL = (255, 255, 255, 255)
SH = (55, 55, 55, 255)


def draw_panel(img):
    px = img.load()
    for y in range(H):
        for x in range(W):
            px[x, y] = PANEL
    d = ImageDraw.Draw(img)
    # 左上ハイライト (内側 2px)
    for t in range(1, 3):
        d.line([(t, t), (W - 1 - t, t)], fill=HL)
        d.line([(t, t), (t, H - 1 - t)], fill=HL)
    # 右下暗影 (内側 2px)
    for t in range(1, 3):
        d.line([(t, H - 1 - t), (W - 1 - t, H - 1 - t)], fill=SH)
        d.line([(W - 1 - t, t), (W - 1 - t, H - 1 - t)], fill=SH)
    # 外周 1px 黒
    d.rectangle([0, 0, W - 1, H - 1], outline=BLACK)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--table", action="store_true")
    args = parser.parse_args()

    img = Image.new("RGBA", (SHEET, SHEET), (0, 0, 0, 0))
    draw_panel(img)

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
        "speaker.png",
    )
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out)
    print("wrote ->", os.path.normpath(out))

    if args.table:
        print(f"panel      = 0,0 {W}x{H}")
        print(f"title      = {TITLE}")
        print(f"volume     = {VOLUME}")
        print(f"range      = {RANGE}")
        print(f"status y   = {STATUS_Y}")


if __name__ == "__main__":
    main()
