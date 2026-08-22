#!/usr/bin/env python3
"""制作機 GUI テクスチャを、失敗の文の帯のぶんだけ縦に伸ばす。

金ジュークと違って制作機のテクスチャには生成器が無い (kura が合格と裁定した実物の PNG が正本)。
なので描き直さず、<b>スロット枠の下の無地の行を複製して差し込む</b>。こうすると

  - 上半分 (URL 欄・ボタン・矢印・スロット枠) は 1px も動かない
  - 下半分 (持ち物ラベル・在庫枠・ホットバー枠) がまるごと DELTA だけ下がる
  - パネル下辺の余白が変わらない

の 3 つが同時に保たれる。バニラ標準式 (row0 = H-82) へ寄せる誘惑があるが、この画面の在庫は
元から標準より 2px 詰まっており、寄せると下辺の余白が 9px → 7px に痩せる = 合格済みの見た目を
別件のついでに動かすことになる。ここでは間隔を保ったまま平行移動する。

    python branding/b1-final/grow_maker_gui.py [--check]

--check は書き換えず、差し込み位置が本当に無地かだけを確かめる。
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image

REPO = Path(__file__).resolve().parents[2]
TEX = (
    REPO
    / "common/src/main/resources/assets/music_disc_maker/textures/gui/music_disc_maker.png"
)

W, H0 = 200, 166  # 元の imageWidth / imageHeight
DELTA = 16  # 失敗の文 2 行ぶん (行送り 9 + 文字高 8 - 元からある空き 1)
INSERT_Y = 70  # スロット枠 (…63) と持ち物ラベル (72…) の間の無地帯のまん中
BLANK_BAND = (64, 81)  # 無地であることを確かめる範囲 [start, end)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

    img = Image.open(TEX).convert("RGBA")
    a = np.array(img)
    sheet_h, sheet_w = a.shape[0], a.shape[1]

    row = a[INSERT_Y : INSERT_Y + 1, :W]
    for y in range(*BLANK_BAND):
        if not np.array_equal(a[y, :W], row[0]):
            print(f"NG: y={y} が差し込み元の行と違う (無地帯ではない)")
            return 1
    print(f"OK: y={BLANK_BAND[0]}..{BLANK_BAND[1] - 1} は同一の無地行")

    if args.check:
        return 0

    out = np.concatenate(
        [
            a[:INSERT_Y],
            np.repeat(a[INSERT_Y : INSERT_Y + 1], DELTA, axis=0),
            a[INSERT_Y:],
        ],
        axis=0,
    )[
        :sheet_h
    ]  # シートは 256 のまま (下端の透明行が DELTA 行ぶん落ちる)
    assert out.shape == (sheet_h, sheet_w, 4)

    Image.fromarray(out, "RGBA").save(TEX)
    print(f"wrote {TEX}  imageHeight {H0} -> {H0 + DELTA}  (sheet {sheet_w}x{sheet_h})")
    print(
        f"  持ち物ラベル 72 -> {72 + DELTA} / 在庫 row0 82 -> {82 + DELTA} / "
        f"ホットバー 140 -> {140 + DELTA}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
