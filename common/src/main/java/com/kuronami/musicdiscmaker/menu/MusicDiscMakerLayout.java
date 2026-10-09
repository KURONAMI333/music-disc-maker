package com.kuronami.musicdiscmaker.menu;

/**
 * Music Disc Maker の画面とメニューで共有するスロット座標。
 *
 * <p>幅はバニラの作業台・かまどと同じ 176 (2026-10-09 本人指示: URL 欄の右がアイコンボタン 3 つに
 * なって横幅に余裕ができたので、インベントリの幅まで狭める)。
 */
public final class MusicDiscMakerLayout {

    public static final int PANEL_WIDTH = 176;
    public static final int PANEL_HEIGHT = 182;

    /** 空ディスクから完成ディスクへ進む中央の主工程。 */
    public static final int INPUT_X = 50;
    public static final int INPUT_Y = 47;
    public static final int OUTPUT_X = 102;
    public static final int OUTPUT_Y = 47;

    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 98;
    public static final int HOTBAR_Y = 156;
    public static final int INVENTORY_LABEL_Y = 88;

    /** URL 入力欄 (画面側 EditBox)。 */
    public static final int URL_X = 11;
    public static final int URL_Y = 25;
    public static final int URL_W = 91;
    public static final int URL_H = 12;

    /** URL 欄の右上へ並ぶ3連ボタン (貼り付け・ファイル選択・消去/取消)。正方形寄りで右端揃え。 */
    public static final int TOOL_Y = 20;
    public static final int TOOL_SIZE = 18;
    public static final int TOOL_GAP = 2;
    public static final int TOOL_RIGHT = 170;
    public static final int TOOL_PASTE_X = TOOL_RIGHT - TOOL_SIZE * 3 - TOOL_GAP * 2;
    public static final int TOOL_FOLDER_X = TOOL_PASTE_X + TOOL_SIZE + TOOL_GAP;
    public static final int TOOL_CLEAR_X = TOOL_FOLDER_X + TOOL_SIZE + TOOL_GAP;

    /**
     * ローカル取込の状態と URL の取得中表示。入出力スロットと在庫ラベルの間の帯に、
     * 1 行なら {@link #STATUS_Y}、折り返したら {@link #STATUS_TWO_LINE_Y} から最大 2 行で出す。
     */
    public static final int STATUS_X = 8;
    public static final int STATUS_Y = 72;
    public static final int STATUS_TWO_LINE_Y = 68;
    public static final int STATUS_MAX_W = 160;
    public static final int STATUS_MAX_LINES = 2;

    /** URL解決失敗の小さな警告印。URL 欄の右端、入力文字の後ろに置く。 */
    public static final int WARNING_X = 104;
    public static final int WARNING_Y = 27;
    public static final int WARNING_SIZE = 8;

    private MusicDiscMakerLayout() {
    }
}
