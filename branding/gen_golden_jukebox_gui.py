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

# transport (メインコントロール列)。囲み枠は持たない (kura 高評価の前デザイン = 素の配置)。
PLAY = (8, 46, 20, 20)            # 再生/一時停止 (center y=56)
REPEAT = (148, 46, 20, 20)        # リピート
SEEK = (32, 48, 112, 16)          # シークバー (center y=56)
TIME_Y = 68                        # 経過/総時間

# 設定スライダー (細身・脇役)
VOL = (8, 84, 160, 15)            # 音量
RANGE = (8, 102, 160, 15)         # 可聴範囲

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


# ── transport スプライト ────────────────────────────────────────────────

# 再生/一時停止は kura 高評価の前デザイン (金の実心ディスク + 暗い記号くり抜き) を
# ピクセル完全一致で復元する。放射シェーディングを手描きせず、承認済みの原画を貼る。
_TRANSPORT_SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                              "transport_buttons_src.png")


def _paint_art(art, color, hi):
    """16x16 の art 文字列から glyph を描く。'#'=color, 'o'=hi, '.'=透明。"""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()
    rows = art.strip("\n").split("\n")
    for y, row in enumerate(rows):
        if y >= 16:
            break
        for x, ch in enumerate(row):
            if x >= 16:
                break
            if ch == "#":
                px[x, y] = color
            elif ch == "o":
                px[x, y] = hi
    return im


# リピート = 単一の循環矢印 + 大きな中実三角矢じり 1 つ (矢印は 2 つでなく 1 つ)。
# 円弧はほぼ一周し、右上のギャップ端に「これは矢印」と一目で分かる大三角を置く。

# v1: 円環の右(3-4時)が大きな下向き三角に潜り込み、その下を開けて時計回りの循環を示す。
_ART_V1 = """
................
.....####.......
...##....##.....
..#........##...
.#..........#...
.#.......#####..
#.........###...
#..........#....
#...............
#...............
.#..............
.#.............
..##......##....
....######......
................
................
"""

# v2: 円弧 + 右に大きな右向き三角 (外向き・3 時方向)。
_ART_V2 = """
................
...#####........
..#.....##......
.#........#.....
#.........#.....
#.........#.o...
#.........#.##..
#.........#.###.
#.........#.####
#.........#.###.
#.........#.##..
#.........#.o...
.#........#.....
.#.......#......
..#####.#.......
................
"""

# v3: 太い円弧 + 上部に大きな三角 (最も塊感の強い矢じり)。
_ART_V3 = """
................
.......##.......
......####......
.....######.....
...##########...
..###......###..
.##..........##.
.##..........##.
.##..........#..
.##.........##..
..##.......##...
...##....###....
....#######.....
................
................
................
"""


def _loop_variant(kind, color, hi):
    art = {"v1": _ART_V1, "v2": _ART_V2, "v3": _ART_V3}[kind]
    return _paint_art(art, color, hi)


def sprite_loop(active, kind="v1"):
    color = GOLD if active else GLYPH_OFF
    hi = GOLD_L if active else (150, 150, 150, 255)
    return _loop_variant(kind, color, hi)


def build(loop_kind="v1"):
    img = Image.new("RGBA", (SHEET, SHEET), (0, 0, 0, 0))
    draw_panel(img)
    px = img.load()
    # スロット枠
    draw_slot(px, DISC[0] - 1, DISC[1] - 1)
    for row in range(3):
        for col in range(9):
            draw_slot(px, INV_X - 1 + col * 18, INV_Y0 - 1 + row * 18)
    for col in range(9):
        draw_slot(px, INV_X - 1 + col * 18, HOT_Y - 1)
    # 再生/一時停止 = 承認済み原画を貼る (u=176 play / u=196 pause)。
    src = Image.open(_TRANSPORT_SRC).convert("RGBA")
    img.alpha_composite(src, (SPR_PLAY_U, 0))
    # リピート = 新規の単一循環矢印。
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


def emit_java(path):
    """レイアウト正本 (LAYOUT) を Java 定数クラスとして書き出す。

    テクスチャを描いた同じ座標を Menu (addSlot) / Screen (widget) がここから参照する。
    これで「テクスチャとスロット座標の別管理ズレ」が構造的に起きない (単一座標源)。
    手で編集しない。座標を変えるときはこのスクリプトを編集して再生成する。
    """
    pkg = "com.kuronami.musicdiscmaker.client"
    lines = [
        "package " + pkg + ";",
        "",
        "/**",
        " * Golden Jukebox GUI レイアウトの単一座標源。",
        " *",
        " * <p>branding/gen_golden_jukebox_gui.py (テクスチャ生成器) が自動生成する。",
        " * テクスチャのスロット枠・transport 凹み・スプライトと同じ座標をここに出力し、",
        " * {@code GoldenJukeboxMenu} (addSlot) と {@code GoldenJukeboxScreen} (widget)",
        " * がこの定数を参照する。手で編集しないこと。座標変更はスクリプトを直して再生成する。",
        " */",
        "public final class GoldenJukeboxLayout {",
        "",
        "    private GoldenJukeboxLayout() {",
        "    }",
        "",
        "    // パネル寸法 (imageWidth / imageHeight)。",
        f"    public static final int IMAGE_W = {W};",
        f"    public static final int IMAGE_H = {H};",
        "",
        "    // スロット (addSlot 座標 = アイテム左上 16x16。テクスチャ枠は addSlot-1 の 18x18)。",
        f"    public static final int DISC_X = {DISC[0]};",
        f"    public static final int DISC_Y = {DISC[1]};",
        f"    public static final int INV_X = {INV_X};",
        f"    public static final int INV_Y0 = {INV_Y0};",
        f"    public static final int HOT_Y = {HOT_Y};",
        f"    public static final int INV_LABEL_X = {INV_LABEL[0]};",
        f"    public static final int INV_LABEL_Y = {INV_LABEL[1]};",
        "",
        "    // ヘッダ テキスト (ディスクスロット右・2 行)。",
        f"    public static final int TRACK_TEXT_X = {TEXT_X};",
        f"    public static final int TITLE_Y = {TITLE_Y};",
        f"    public static final int AUTHOR_Y = {AUTHOR_Y};",
        "",
        "    // transport (メインコントロール列): 再生/一時停止・シーク・リピート。",
        f"    public static final int PLAY_X = {PLAY[0]};",
        f"    public static final int REPEAT_X = {REPEAT[0]};",
        f"    public static final int TRANSPORT_Y = {PLAY[1]};",
        f"    public static final int PLAY_SPRITE = {PLAY[2]};",
        f"    public static final int SEEK_X = {SEEK[0]};",
        f"    public static final int SEEK_Y = {SEEK[1]};",
        f"    public static final int SEEK_W = {SEEK[2]};",
        f"    public static final int SEEK_H = {SEEK[3]};",
        f"    public static final int TIME_Y = {TIME_Y};",
        "",
        "    // 設定スライダー (音量・範囲)。",
        f"    public static final int VOLUME_Y = {VOL[1]};",
        f"    public static final int RANGE_Y = {RANGE[1]};",
        f"    public static final int SLIDER_W = {VOL[2]};",
        f"    public static final int SLIDER_H = {VOL[3]};",
        "}",
        "",
    ]
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))
    print(f"wrote {path}")


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
    print(f"  header text = x={TEXT_X} title_y={TITLE_Y} author_y={AUTHOR_Y}")
    print(f"  inv label   = {INV_LABEL}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="common/src/main/resources/assets/"
                    "music_disc_maker/textures/gui/golden_jukebox.png")
    ap.add_argument("--loop", default="v1", choices=["v1", "v2", "v3"])
    ap.add_argument("--table", action="store_true")
    ap.add_argument("--candidates", metavar="DIR",
                    help="loop アイコン候補と比較シートを DIR に出す")
    ap.add_argument("--java", default="common/src/main/java/com/kuronami/"
                    "musicdiscmaker/client/GoldenJukeboxLayout.java",
                    help="レイアウト座標を書き出す Java 定数クラスのパス")
    args = ap.parse_args()

    img = build(args.loop)
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    img.save(args.out)
    print(f"wrote {args.out}  ({img.size})")

    # テクスチャと同じ座標源から Java 定数を出力する (Menu/Screen がこれを参照 = 単一座標源)。
    emit_java(args.java)

    if args.candidates:
        os.makedirs(args.candidates, exist_ok=True)
        variants = ["v1", "v2", "v3"]
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
