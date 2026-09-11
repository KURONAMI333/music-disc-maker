package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.menu.BoomboxMenu;

/**
 * ブームボックス画面の確定座標。盤 1 枠とプレイヤー在庫は
 * {@code BoomboxMenu} の {@code addSlot} と同じ値を使う。
 */
public final class BoomboxScreenLayout {

    public static final int PANEL_WIDTH = 176;
    public static final int PANEL_HEIGHT = 224;

    public static final int DISC_X = BoomboxMenu.MEDIA_SLOT_X;
    public static final int DISC_Y = BoomboxMenu.MEDIA_SLOT_Y;
    public static final int TRACK_TEXT_X = 32;
    public static final int TRACK_TEXT_WIDTH = 136;
    public static final int TITLE_Y = 19;
    public static final int AUTHOR_Y = 31;

    public static final int INVENTORY_X = 8;
    public static final int INVENTORY_Y = 142;
    public static final int HOTBAR_Y = 200;
    public static final int INVENTORY_LABEL_Y = 130;
    public static final int SLOT_SIZE = 18;

    public static final int VOLUME_X = 8;
    public static final int VOLUME_Y = 106;
    public static final int VOLUME_WIDTH = 160;
    public static final int SLIDER_HEIGHT = 15;

    /** ラジオの表示窓の下に時間と操作キーを配置する。 */
    public static final int SEEK_Y = 52;
    public static final int SEEK_HEIGHT = GoldenJukeboxTransportLayout.SEEK_HEIGHT;
    public static final int TIME_X = GoldenJukeboxTransportLayout.TIME_X;
    public static final int TIME_RIGHT = GoldenJukeboxTransportLayout.TIME_RIGHT;
    public static final int TIME_Y = 55;
    public static final int TRANSPORT_Y = 78;

    public static final int MACHINE_HEIGHT = 128;

    private BoomboxScreenLayout() {
    }
}
