package com.kuronami.musicdiscmaker.client;

/**
 * Golden Jukebox GUI レイアウトの単一座標源。
 *
 * <p>branding/gen_golden_jukebox_gui.py (テクスチャ生成器) が自動生成する。
 * テクスチャのスロット枠・transport 凹み・スプライトと同じ座標をここに出力し、
 * {@code GoldenJukeboxMenu} (addSlot) と {@code GoldenJukeboxScreen} (widget)
 * がこの定数を参照する。手で編集しないこと。座標変更はスクリプトを直して再生成する。
 */
public final class GoldenJukeboxLayout {

    private GoldenJukeboxLayout() {
    }

    // パネル寸法 (imageWidth / imageHeight)。
    public static final int IMAGE_W = 176;
    public static final int IMAGE_H = 224;

    // スロット (addSlot 座標 = アイテム左上 16x16。テクスチャ枠は addSlot-1 の 18x18)。
    public static final int DISC_X = 8;
    public static final int DISC_Y = 18;
    public static final int INV_X = 8;
    public static final int INV_Y0 = 142;
    public static final int HOT_Y = 200;
    public static final int INV_LABEL_X = 8;
    public static final int INV_LABEL_Y = 130;

    // ヘッダ テキスト (ディスクスロット右・2 行)。
    public static final int TRACK_TEXT_X = 32;
    public static final int TITLE_Y = 19;
    public static final int AUTHOR_Y = 31;

    // transport (メインコントロール列): 再生/一時停止・シーク・リピート。
    public static final int PLAY_X = 8;
    public static final int REPEAT_X = 148;
    public static final int TRANSPORT_Y = 46;
    public static final int PLAY_SPRITE = 20;
    public static final int SEEK_X = 32;
    public static final int SEEK_Y = 48;
    public static final int SEEK_W = 112;
    public static final int SEEK_H = 16;
    public static final int TIME_Y = 68;

    // 設定スライダー (音量・範囲)。
    public static final int VOLUME_Y = 84;
    public static final int RANGE_Y = 102;
    public static final int SLIDER_W = 160;
    public static final int SLIDER_H = 15;
}
