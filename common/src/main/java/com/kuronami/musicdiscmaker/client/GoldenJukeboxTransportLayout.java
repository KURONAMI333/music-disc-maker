package com.kuronami.musicdiscmaker.client;

/** Golden Jukebox の確定 B2 transport 配置。Minecraft の描画型へ依存しない座標源。 */
public final class GoldenJukeboxTransportLayout {

    public static final int PANEL_WIDTH = 176;
    public static final int BUTTON_SIZE = 20;
    public static final int BUTTON_Y = 60;
    public static final int BUTTON_CENTER_STEP = 24;

    public static final int SHUFFLE = 0;
    public static final int PREVIOUS = 1;
    public static final int PLAY_PAUSE = 2;
    public static final int NEXT = 3;
    public static final int REPEAT = 4;
    public static final int BUTTON_COUNT = 5;

    public static final int SEEK_Y = 45;
    public static final int SEEK_HEIGHT = 12;
    public static final int TIME_X = 8;
    public static final int TIME_RIGHT = 168;
    public static final int TIME_Y = 48;
    public static final int TIME_GAP = 4;

    private static final int FIRST_CENTER = PANEL_WIDTH / 2 - BUTTON_CENTER_STEP * (BUTTON_COUNT - 1) / 2;

    private GoldenJukeboxTransportLayout() {
    }

    /** 左から shuffle / previous / play-pause / next / repeat の widget 左端を返す。 */
    public static int buttonX(int index) {
        if (index < 0 || index >= BUTTON_COUNT) {
            throw new IllegalArgumentException("transport button index out of range: " + index);
        }
        return FIRST_CENTER + index * BUTTON_CENTER_STEP - BUTTON_SIZE / 2;
    }

    /** 表示中の左右時刻から4pxずつ離し、残った中央領域をseek barへ割り当てる。 */
    public static SeekBounds seekBounds(int leftTextWidth, int rightTextWidth) {
        if (leftTextWidth < 0 || rightTextWidth < 0) {
            throw new IllegalArgumentException("time text width must not be negative");
        }
        final int preferredLeft = TIME_X + leftTextWidth + TIME_GAP;
        final int preferredRight = TIME_RIGHT - rightTextWidth - TIME_GAP;
        final int left = Math.min(preferredLeft, TIME_RIGHT - 1);
        final int right = Math.max(left, preferredRight);
        return new SeekBounds(left, right - left);
    }

    public record SeekBounds(int x, int width) {
    }
}

