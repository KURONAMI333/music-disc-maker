#!/usr/bin/env python3
"""11 文 × 14 ロケール × 両幅 の行数掃引 (vanilla StringSplitter 相当)。

予算 (パネル高さがこれで決まる):
  制作機   幅 180 / 2 行 — 到達する 8 キー (+ 汎用 failed) が対象
  金ジューク 幅 160 / 3 行 — 11 キー全部が対象
超えたら訳を詰める。パネルを伸ばす前にここを見る。
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "b1-legibility"))
sys.path.insert(0, str(HERE.parent / "b1-sentence"))
sys.path.insert(0, str(HERE))
import render_legibility as R  # noqa: E402
import metrics as M  # noqa: E402
import sentences as SEN  # noqa: E402

# ハングルは unihex の size_override が 16 グリッド = advance 8px。
# render_legibility.is_fullwidth はここを見ていないので足す (ASCII 面にも無く、
# 既定の "?" 幅 6 で測ると ko_kr だけ 25% 短く出る)。
_base_fullwidth = R.is_fullwidth


def is_fullwidth(ch: str) -> bool:
    cp = ord(ch)
    if 0x1100 <= cp <= 0x11FF or 0x3130 <= cp <= 0x318F or 0xAC00 <= cp <= 0xD7A3:
        return True
    return _base_fullwidth(ch)


R.is_fullwidth = is_fullwidth

ASSETS = HERE.parent.parent / "common/src/main/resources/assets/music_disc_maker"
LANGDIR = ASSETS / "lang"

W_MAKER, MAX_MAKER = 180, 2
W_JUKE, MAX_JUKE = 160, 3

# 制作機に到達するのは FailureReason 9 種。muted / limit / generic は再生時のみ。
MAKER_KEYS = [
    "botcheck",
    "private",
    "region",
    "age",
    "refused",
    "connection",
    "unsupported",
    "blocked",
]
JUKE_KEYS = SEN.KEYS


def rows(texts: dict[str, str], name: str):
    """(NG 一覧, 最大行数 maker, 最大行数 juke)。"""
    bad, mx_m, mx_j = [], 0, 0
    for key in JUKE_KEYS:
        txt = texts.get(key)
        if txt is None:
            bad.append(f"{name}: {key} が無い")
            continue
        nj = len(M.split_vanilla(txt, W_JUKE))
        mx_j = max(mx_j, nj)
        if nj > MAX_JUKE:
            bad.append(f"{name} juke {key}: {nj} 行 (w={R.jp_width(txt)}) {txt}")
        if key in MAKER_KEYS:
            nm = len(M.split_vanilla(txt, W_MAKER))
            mx_m = max(mx_m, nm)
            if nm > MAX_MAKER:
                bad.append(f"{name} maker {key}: {nm} 行 (w={R.jp_width(txt)}) {txt}")
    return bad, mx_m, mx_j


def main():
    verbose = "-v" in sys.argv
    all_bad, worst_m, worst_j = [], 0, 0
    for loc in sorted(SEN.S):
        bad, mm, mj = rows(SEN.S[loc], loc)
        all_bad += bad
        worst_m, worst_j = max(worst_m, mm), max(worst_j, mj)
        print(f"{loc}: maker最大 {mm} 行 / juke最大 {mj} 行")
        if verbose:
            for key in JUKE_KEYS:
                print(f"    {key:<12}", M.split_vanilla(SEN.S[loc][key], W_JUKE))

    # 制作機の汎用ラベル (FailureReason.UNKNOWN) は 11 文の外。実 lang から見る。
    for f in sorted(LANGDIR.glob("*.json")):
        lang = json.loads(f.read_text(encoding="utf-8"))
        txt = lang.get("gui.music_disc_maker.failed")
        if txt and len(M.split_vanilla(txt, W_MAKER)) > MAX_MAKER:
            all_bad.append(f"{f.name} maker 汎用: {txt}")

    print()
    print(
        f"maker 最大 {worst_m} 行 (予算 {MAX_MAKER}) / juke 最大 {worst_j} 行 (予算 {MAX_JUKE})"
    )
    if all_bad:
        print("--- 予算超過 ---")
        for b in all_bad:
            print(" ", b)
        return 1
    print("OK: 全ロケール・両幅で予算内")
    return 0


if __name__ == "__main__":
    sys.exit(main())
