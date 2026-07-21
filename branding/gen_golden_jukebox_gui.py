#!/usr/bin/env python3
"""Golden Jukebox 設定 GUI テクスチャの生成器。

このスクリプトが GUI レイアウトの座標の正本。ここで定義した LAYOUT を使って
テクスチャ (パネル・スロット枠・transport 凹み・transport スプライト) を描き、
同じ座標を GoldenJukeboxMenu (addSlot) / GoldenJukeboxScreen (widget) が手写しする。

--table を付けると「addSlot 座標 / 実描画したスロット枠矩形 / widget 矩形」を print する。
これがレイアウト検証の一次証拠 (手書きの表でなく実際に描いた矩形)。
"""
import argparse
import os
from PIL import Image, ImageDraw

# ── レイアウト正本 (panel-local, leftPos/topPos 相対) ─────────────────────
W, H = 176, 224                    # imageWidth / imageHeight
SHEET = 256                        # blit sheet size

# スロット (addSlot 座標 = アイテム左上 16x16。枠は addSlot-1 の 18x18)
DISC = (8, 18)                     # ディスクスロット
INV_X, INV_Y0 = 8, 142            # プレイヤーインベントリ 3 行の起点 (row0)
HOT_Y = 200                        # ホットバー
INV_LABEL = (8, 130)              # "Inventory" ラベル (= H-94)

# transport (メインコントロール列)
TRANSPORT_BOX = (6, 42, 170, 66)  # 凹みパネル (x0,y0,x1,y1)
PLAY = (8, 46, 20, 20)            # 再生/一時停止 (center y=56)
REPEAT = (148, 46, 20, 20)        # リピート
SEEK = (32, 48, 112, 16)          # シークバー (center y=56)
TIME_Y = 68                        # 経過/総時間

# 設定スライダー
VOL = (8, 84, 160, 20)            # 音量
RANGE = (8, 106, 160, 20)         # 可聴範囲

# ヘッダ テキスト (ディスクスロット右・2 行)
TEXT_X = 32
TITLE_Y = 19
AUTHOR_Y = 31

# transport スプライト (sheet 内 uv, v=0)
SPR_PLAY_U, SPR_PAUSE_U = 176, 196   # 20x20
SPR_LOOP_ON_U, SPR_LOOP_OFF_U = 216, 232  # 16x16

# 色
PANEL = (198, 198, 198, 255)
BLACK = (0, 0, 0, 255)
HL = (255, 255, 255, 255)          # 明ハイライト
SH = (55, 55, 55, 255)             # 暗影
SLOT_FILL = (139, 139, 139, 255)
DARK = (85, 85, 85, 255)           # 中間影
GOLD = (206, 168, 68, 255)         # アクセント (kura 高評価)
GOLD_D = (150, 118, 40, 255)       # 金の影
GOLD_L = (232, 200, 110, 255)      # 金のハイライト
GLYPH_OFF = (110, 110, 110, 255)   # 非活性グリフ


def draw_slot(px, fx, fy):
    """バニラ準拠スロット枠。上辺+左列=55, 下辺+右列=255, 内部=139。fx,fy=枠左上。"""
    for i in range(18):
        for j in range(18):
            px[fx + i, fy + j] = SLOT_FILL
    for i in range(18):
        px[fx + i, fy] = SH          # top
        px[fx + i, fy + 17] = HL     # bottom
    for j in range(18):
        px[fx, fy + j] = SH          # left
        px[fx + 17, fy + j] = HL     # right
    px[fx, fy] = SH                  # TL corner
    px[fx + 17, fy + 17] = HL        # BR corner


def draw_panel(img):
    px = img.load()
    # 塗り
    for y in range(H):
        for x in range(W):
            px[x, y] = PANEL
    # バニラ縁: 外周 1px 黒 + 左上 2px 白 + 右下 2px 暗
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


def draw_transport_recess(img):
    """transport 列の凹みパネル。neutral 凹み + 金の 1px 天面ライン (フラットアクセント)。"""
    x0, y0, x1, y1 = TRANSPORT_BOX
    px = img.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            px[x, y] = (188, 188, 188, 255)
    d = ImageDraw.Draw(img)
    # 凹み: 上辺+左辺 暗, 下辺+右辺 明 (スロットと同じ recessed 文法)
    d.line([(x0, y0), (x1 - 1, y0)], fill=SH)
    d.line([(x0, y0), (x0, y1 - 1)], fill=SH)
    d.line([(x0, y1 - 1), (x1 - 1, y1 - 1)], fill=HL)
    d.line([(x1 - 1, y0), (x1 - 1, y1 - 1)], fill=HL)
    # 金の 1px 天面アクセント (メイン扱いの強調・フラット, glow 無し)
    d.line([(x0 + 1, y0 + 1), (x1 - 2, y0 + 1)], fill=GOLD)


# ── transport スプライト ────────────────────────────────────────────────

def sprite_play(active=True):
    """20x20 丸ボタン + 金の再生三角。"""
    im = Image.new("RGBA", (20, 20), (0, 0, 0, 0))
    _round_button(im)
    d = ImageDraw.Draw(im)
    g = GOLD if active else GLYPH_OFF
    gl = GOLD_L if active else GLYPH_OFF
    # 三角 (左詰め視覚重心補正で x=7 起点)
    for i in range(7):
        y0 = 6 + i
        y1 = 14 - i
        if y0 > y1:
            break
        d.line([(7, y0), (7, y1)], fill=g)
    for i in range(7):
        y0 = 6 + i
        y1 = 14 - i
        if y0 > y1:
            break
        d.point((7 + i, (y0 + y1) // 2), fill=gl)
        d.line([(7 + i, y0), (7 + i, y1)], fill=g)
    return im


def sprite_pause(active=True):
    im = Image.new("RGBA", (20, 20), (0, 0, 0, 0))
    _round_button(im)
    d = ImageDraw.Draw(im)
    g = GOLD if active else GLYPH_OFF
    d.rectangle([6, 6, 8, 13], fill=g)
    d.rectangle([11, 6, 13, 13], fill=g)
    return im


def _round_button(im):
    """20x20 の丸いボタン基盤 (neutral・フラット)。"""
    d = ImageDraw.Draw(im)
    d.ellipse([1, 1, 18, 18], fill=(176, 176, 176, 255), outline=SH)
    # 内側 1px 明ハイライト (上半分)
    d.arc([2, 2, 17, 17], start=180, end=360, fill=(214, 214, 214, 255))
    d.arc([2, 2, 17, 17], start=0, end=180, fill=DARK)


def _paint_art(art, color, hi):
    """16x16 の art 文字列から glyph を描く。'#'=color, 'o'=hi, '.'=透明。"""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()
    rows = art.strip("\n").split("\n")
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch == "#":
                px[x, y] = color
            elif ch == "o":
                px[x, y] = hi
    return im


# メディア標準の角丸ループ + 上右/下左の三角矢じり (回転対称)。
_ART_RRECT = """
................
................
....######o.....
...#.......##...
..#.........##..
.##..........#..
###..........#..
.#...........#..
..#...........#o
..#..........###
..#...........#.
..##.........#..
...#.......###..
....######o#....
................
................
"""

# 上下で追いかける双矢印リング (現代的な repeat 🔁)。右=上向き三角/左=下向き三角。
_ART_CIRCLE = """
................
......####......
....##....##....
...#........#...
..#.........o#..
.#..........###.
.#.........#####
##...........#o.
.o#..........#..
#####.........#.
..#o..........#.
..#........##...
...#......##....
....##..###.....
......####......
................
"""

# 太めの角丸ループ (視認性重視)。
_ART_THICK = """
................
................
...#######o.....
..##.....###....
.##........##...
.##.........##..
##.........####.
##...........#..
..#..........#..
.####........#..
..##.........#..
...##.......##..
....##.....##...
.....#######....
................
................
"""


def _loop_variant(kind, color, hi):
    art = {"rrect": _ART_RRECT, "circle": _ART_CIRCLE, "thick": _ART_THICK}[kind]
    return _paint_art(art, color, hi)


def sprite_loop(active, kind="rrect"):
    color = GOLD if active else GLYPH_OFF
    hi = GOLD_L if active else (150, 150, 150, 255)
    return _loop_variant(kind, color, hi)


def build(loop_kind="rrect"):
    img = Image.new("RGBA", (SHEET, SHEET), (0, 0, 0, 0))
    draw_panel(img)
    draw_transport_recess(img)
    px = img.load()
    # スロット枠
    draw_slot(px, DISC[0] - 1, DISC[1] - 1)
    for row in range(3):
        for col in range(9):
            draw_slot(px, INV_X - 1 + col * 18, INV_Y0 - 1 + row * 18)
    for col in range(9):
        draw_slot(px, INV_X - 1 + col * 18, HOT_Y - 1)
    # スプライト
    img.alpha_composite(sprite_play(True), (SPR_PLAY_U, 0))
    img.alpha_composite(sprite_pause(True), (SPR_PAUSE_U, 0))
    img.alpha_composite(sprite_loop(True, loop_kind), (SPR_LOOP_ON_U, 0))
    img.alpha_composite(sprite_loop(False, loop_kind), (SPR_LOOP_OFF_U, 0))
    return img


def slot_rects():
    rects = []
    rects.append(("disc", DISC, (DISC[0] - 1, DISC[1] - 1, 18, 18)))
    for row in range(3):
        for col in range(9):
            a = (INV_X + col * 18, INV_Y0 + row * 18)
            rects.append((f"inv[{row},{col}]", a, (a[0] - 1, a[1] - 1, 18, 18)))
    for col in range(9):
        a = (INV_X + col * 18, HOT_Y)
        rects.append((f"hot[{col}]", a, (a[0] - 1, a[1] - 1, 18, 18)))
    return rects


def print_table():
    print("=== GoldenJukebox GUI layout (source of truth) ===")
    print(f"imageWidth={W}  imageHeight={H}  (vanilla: row0=H-82={H-82}, "
          f"hotbar=H-24={H-24}, invLabelY=H-94={H-94})")
    print("\n-- slots: addSlot(x,y) vs drawn frame rect(x,y,w,h) --")
    for name, add, frame in slot_rects():
        print(f"  {name:10s} addSlot={add}  frame={frame}")
    print("\n-- widgets (leftPos/topPos-relative) --")
    print(f"  play/pause  = {PLAY}")
    print(f"  repeat      = {REPEAT}")
    print(f"  seekbar     = {SEEK}   time_y={TIME_Y}")
    print(f"  volume      = {VOL}")
    print(f"  range       = {RANGE}")
    print(f"  transport   = box{TRANSPORT_BOX}")
    print(f"  header text = x={TEXT_X} title_y={TITLE_Y} author_y={AUTHOR_Y}")
    print(f"  inv label   = {INV_LABEL}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="common/src/main/resources/assets/"
                    "music_disc_maker/textures/gui/golden_jukebox.png")
    ap.add_argument("--loop", default="rrect", choices=["rrect", "circle", "thick"])
    ap.add_argument("--table", action="store_true")
    ap.add_argument("--candidates", metavar="DIR",
                    help="loop アイコン候補と比較シートを DIR に出す")
    args = ap.parse_args()

    img = build(args.loop)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    img.save(args.out)
    print(f"wrote {args.out}  ({img.size})")

    if args.candidates:
        os.makedirs(args.candidates, exist_ok=True)
        variants = ["rrect", "circle", "thick"]
        scaled = []
        for k in variants:
            on = sprite_loop(True, k)
            off = sprite_loop(False, k)
            combo = Image.new("RGBA", (34, 16), (60, 60, 60, 255))
            combo.alpha_composite(on, (1, 0))
            combo.alpha_composite(off, (18, 0))
            combo.save(os.path.join(args.candidates, f"loop_{k}.png"))
            scaled.append((k, combo))
        # 比較シート (12x 拡大, 番号ラベル)
        s = 12
        cell_w = 34 * s + 20
        sheet = Image.new("RGBA", (cell_w, 16 * s * len(variants) + 40),
                          (40, 40, 40, 255))
        d = ImageDraw.Draw(sheet)
        for i, (k, combo) in enumerate(scaled):
            big = combo.resize((34 * s, 16 * s), Image.NEAREST)
            y = 20 + i * (16 * s + 8)
            sheet.alpha_composite(big, (10, y))
            d.text((10, y - 12), f"{i+1}. {k}  (ON | OFF)", fill=(255, 255, 255, 255))
        sp = os.path.join(args.candidates, "loop_candidates_sheet.png")
        sheet.save(sp)
        print(f"wrote candidate sheet {sp}")

    if args.table:
        print()
        print_table()


if __name__ == "__main__":
    main()
