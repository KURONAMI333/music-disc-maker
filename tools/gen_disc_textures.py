#!/usr/bin/env python3
"""Music Disc Maker のテクスチャ一式を MineTexture で生成する。

- custom disc の variant: バニラ music_disc_cat を色相シフト (中央ラベルを曲ごとに12色) し、
  さらに盤面 (グレースケール) を colorize で統一カラー (BODY_HUE) に着色。
- blank disc: 盤面が統一カラーの無地ディスク。
- ブロック本体: バニラ jukebox を BODY_HUE 寄りに hue-shift (ディスク機械の見た目)。

variant 選択は描画時に TrackKey.variantIndex (曲名+アーティスト正規化ハッシュ % VARIANTS)。
VARIANTS をここと TrackKey.java で一致させること。

実行: python tools/gen_disc_textures.py
"""

from __future__ import annotations

import json
import os
import sys

from PIL import Image

BASE_DISC = "music_disc_cat"

# 盤面の統一カラー (ブルー/azure)。label の色相回転とは独立。
BODY_HUE = 210.0
BODY_SAT = 0.80
BODY_GAIN = 2.4

# 中央ラベルの 12 色。機械的な 30° 刻みだと (a) 緑系/ピンク系に複数が潰れて知覚的に偏り、
# (b) 青帯 (≈190-240°) に入った variant が盤面の青に同化して消える。
# そこで「人が別の色名として区別できる」12 色を、盤面の青 (210°) を避けて手動配置する。
VARIANT_HUES = [5, 30, 52, 70, 95, 135, 165, 250, 275, 300, 322, 344]
# variant 数。TrackKey.VARIANTS と一致させること。
VARIANTS = len(VARIANT_HUES)
# バニラ music_disc_cat の中央ラベルの代表 hue (実測)。各 variant はここからの回転で目標色へ。
BASE_CENTER_HUE = 102.0

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MINETEX = os.path.join(ROOT, "..", "_tools", "MineTexture")
VANILLA_ITEM = os.path.join(MINETEX, "tool_data", "cache", "vanilla_textures", "item")
VANILLA_BLOCK = os.path.join(MINETEX, "tool_data", "cache", "vanilla_textures", "block")

ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "music_disc_maker")
TEX_ITEM = os.path.join(ASSETS, "textures", "item")
TEX_BLOCK = os.path.join(ASSETS, "textures", "block")
MODEL_ITEM = os.path.join(ASSETS, "models", "item")

sys.path.insert(0, MINETEX)
from minetexture.core.color_shift import colorize, shift_hue  # noqa: E402
from minetexture.core.painter import Palette, grid_paint  # noqa: E402


def _open(path: str) -> Image.Image:
    if not os.path.exists(path):
        raise SystemExit(
            f"texture not found: {path} (MineTexture の vanilla cache を確認)"
        )
    return Image.open(path).convert("RGBA")


def write_model(name: str, texture: str) -> None:
    with open(os.path.join(MODEL_ITEM, name + ".json"), "w", encoding="utf-8") as f:
        json.dump(
            {
                "parent": "minecraft:item/generated",
                "textures": {"layer0": "music_disc_maker:item/" + texture},
            },
            f,
            indent=2,
        )
        f.write("\n")


def tint_body(label_img: Image.Image) -> Image.Image:
    """ラベル着色済み画像の盤面 (グレースケール) だけを BODY_HUE に着色。"""
    return colorize(
        label_img,
        hue_deg=BODY_HUE,
        saturation=BODY_SAT,
        value_gain=BODY_GAIN,
        only_grayscale=True,
    )


def gen_discs() -> None:
    base = _open(os.path.join(VANILLA_ITEM, BASE_DISC + ".png"))
    for i in range(VARIANTS):
        # 中央ラベルを base から VARIANT_HUES[i] へ回転 (preserve_grayscale で盤面は触らない)
        label = shift_hue(
            base,
            hue_shift_deg=VARIANT_HUES[i] - BASE_CENTER_HUE,
            preserve_grayscale=True,
        )
        tint_body(label).save(os.path.join(TEX_ITEM, f"custom_music_disc_{i}.png"))
        write_model(f"custom_music_disc_{i}", f"custom_music_disc_{i}")

    tint_body(
        shift_hue(
            base,
            hue_shift_deg=VARIANT_HUES[0] - BASE_CENTER_HUE,
            preserve_grayscale=True,
        )
    ).save(
        os.path.join(TEX_ITEM, "custom_music_disc.png")
    )  # base fallback = variant 0
    tint_body(shift_hue(base, saturation_factor=0.0)).save(
        os.path.join(TEX_ITEM, "blank_disc.png")
    )  # 無地ディスク (中央色なし)

    overrides = [
        {
            "predicate": {"music_disc_maker:variant": round(i / VARIANTS, 5)},
            "model": f"music_disc_maker:item/custom_music_disc_{i}",
        }
        for i in range(VARIANTS)
    ]
    with open(
        os.path.join(MODEL_ITEM, "custom_music_disc.json"), "w", encoding="utf-8"
    ) as f:
        json.dump(
            {
                "parent": "minecraft:item/generated",
                "textures": {"layer0": "music_disc_maker:item/custom_music_disc"},
                "overrides": overrides,
            },
            f,
            indent=2,
        )
        f.write("\n")


def _machine_panel(vanilla: str) -> Image.Image:
    """グレーの機械パネル (blast furnace) を BODY_HUE の青メタルに着色。"""
    return colorize(
        _open(os.path.join(VANILLA_BLOCK, vanilla + ".png")),
        hue_deg=BODY_HUE,
        saturation=0.6,
        value_gain=1.05,
    )


def gen_block() -> None:
    """向き付き機械ブロックのテクスチャ。正面=操作パネル(レコード+音符)、上面=投入口、側面=パネル。"""
    os.makedirs(TEX_BLOCK, exist_ok=True)

    pal = Palette(
        {".": None, "D": "#15151e", "C": "#4fd6ff", "N": "#d8efff", "h": "#0c0c12"}
    )
    disc = grid_paint(
        """
..DDDDDD..
.DDDDDDDD.
DDDDDDDDDD
DDDDCCDDDD
DDDCChCDDD
DDDCCCCDDD
DDDDCCDDDD
DDDDDDDDDD
.DDDDDDDD.
..DDDDDD..
""",
        pal,
    )
    note = grid_paint(
        """
...N
...N
...N
.NNN
NNNN
NNN.
""",
        pal,
    )

    # 正面: 青機械パネル + 中央にレコード + 左上に音符
    front = _machine_panel("blast_furnace_front")
    front.alpha_composite(disc, (3, 4))
    front.alpha_composite(note, (2, 1))
    front.save(os.path.join(TEX_BLOCK, "music_disc_maker_front.png"))

    # 側面: 青機械パネル (リベット)
    _machine_panel("blast_furnace_side").save(
        os.path.join(TEX_BLOCK, "music_disc_maker_side.png")
    )

    # 上面: jukebox スロットを青く = ディスク投入口。
    # orientable で正面に対しスロットが 90° ずれて見えるので回して合わせる。
    top = shift_hue(
        _open(os.path.join(VANILLA_BLOCK, "jukebox_top.png")),
        hue_shift_deg=180.0,
        saturation_factor=1.3,
        value_factor=1.12,
        preserve_grayscale=False,
    )
    top = top.transpose(Image.ROTATE_90)
    top.save(os.path.join(TEX_BLOCK, "music_disc_maker_top.png"))


def main() -> None:
    os.makedirs(TEX_ITEM, exist_ok=True)
    os.makedirs(MODEL_ITEM, exist_ok=True)
    gen_discs()
    gen_block()
    print(f"generated {VARIANTS} variants (body hue {BODY_HUE:.0f}) + blank + block")


if __name__ == "__main__":
    main()
