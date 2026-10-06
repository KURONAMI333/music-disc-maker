package com.kuronami.musicdiscmaker.menu;

/** Music Disc Maker の画面とメニューで共有するスロット座標。 */
public final class MusicDiscMakerLayout {

    public static final int PANEL_WIDTH = 200;
    public static final int PANEL_HEIGHT = 182;

    /** 空ディスクから完成ディスクへ進む中央の主工程。 */
    public static final int INPUT_X = 62;
    public static final int INPUT_Y = 47;
    public static final int OUTPUT_X = 114;
    public static final int OUTPUT_Y = 47;

    public static final int INVENTORY_X = 19;
    public static final int INVENTORY_Y = 98;
    public static final int HOTBAR_Y = 156;
    public static final int INVENTORY_LABEL_Y = 88;

    /** URL 入力欄 (画面側 EditBox)。 */
    public static final int URL_X = 11;
    public static final int URL_Y = 25;
    public static final int URL_W = 98;
    public static final int URL_H = 12;

    /** URL 欄の右上へ並ぶ3連ボタン (貼り付け・ファイル選択・消去/取消)。正方形寄りで右端揃え。 */
    public static final int TOOL_Y = 20;
    public static final int TOOL_SIZE = 18;
    public static final int TOOL_GAP = 4;
    public static final int TOOL_RIGHT = 194;
    public static final int TOOL_PASTE_X = TOOL_RIGHT - TOOL_SIZE * 3 - TOOL_GAP * 2;
    public static final int TOOL_FOLDER_X = TOOL_PASTE_X + TOOL_SIZE + TOOL_GAP;
    public static final int TOOL_CLEAR_X = TOOL_FOLDER_X + TOOL_SIZE + TOOL_GAP;

    /** ローカル取込の状態行。入出力スロットと在庫ラベルの間の帯を使う。 */
    public static final int STATUS_X = 11;
    public static final int STATUS_Y = 72;
    public static final int STATUS_MAX_W = 171;

    /** URL解決失敗の小さな警告印。状態行の右端へ置き、状態文とは接しない。 */
    public static final int WARNING_X = 186;
    public static final int WARNING_Y = 72;
    public static final int WARNING_SIZE = 8;

    private MusicDiscMakerLayout() {
    }
}
