package com.kuronami.musicdiscmaker.menu;

/** Shared, Minecraft-independent geometry for the two-row Album menu. */
public final class AlbumMenuLayout {
    public static final int COLUMNS = 9;
    public static final int ROWS = 2;
    public static final int CAPACITY = COLUMNS * ROWS;

    public static final int IMAGE_WIDTH = 176;
    public static final int IMAGE_HEIGHT = 150;
    public static final int ALBUM_SLOT_Y = 18;
    public static final int PLAYER_INVENTORY_Y = 67;
    public static final int HOTBAR_Y = 125;
    public static final int INVENTORY_LABEL_Y = 56;
    public static final int CONTAINER_TOP_HEIGHT = 53;
    public static final int PLAYER_INVENTORY_TEXTURE_Y = 126;
    public static final int PLAYER_INVENTORY_HEIGHT = 96;

    private AlbumMenuLayout() {}

    public static int albumSlotX(int index) {
        checkAlbumSlot(index);
        return 8 + (index % COLUMNS) * 18;
    }

    public static int albumSlotY(int index) {
        checkAlbumSlot(index);
        return ALBUM_SLOT_Y + (index / COLUMNS) * 18;
    }

    /** Main inventory row 0..2; the hotbar has its own {@link #HOTBAR_Y}. */
    public static int playerInventoryY(int row) {
        if (row < 0 || row >= 3) throw new IndexOutOfBoundsException("player inventory row: " + row);
        return PLAYER_INVENTORY_Y + row * 18;
    }

    private static void checkAlbumSlot(int index) {
        if (index < 0 || index >= CAPACITY) throw new IndexOutOfBoundsException("album slot: " + index);
    }
}
