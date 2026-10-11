package com.kuronami.musicdiscmaker.menu;

/**
 * Shared, Minecraft-independent geometry for the Album menus. The Album uses two rows and the
 * Golden Album six; both follow the vanilla chest layout for the same row count.
 */
public final class AlbumMenuLayout {
    public static final int COLUMNS = 9;
    public static final int ROWS = 2;
    public static final int CAPACITY = COLUMNS * ROWS;
    public static final int GOLDEN_ROWS = 6;
    public static final int GOLDEN_CAPACITY = COLUMNS * GOLDEN_ROWS;

    public static final int IMAGE_WIDTH = 176;
    public static final int IMAGE_HEIGHT = imageHeight(ROWS);
    public static final int ALBUM_SLOT_Y = 18;
    public static final int PLAYER_INVENTORY_Y = playerInventoryTop(ROWS);
    public static final int HOTBAR_Y = hotbarY(ROWS);
    public static final int INVENTORY_LABEL_Y = inventoryLabelY(ROWS);
    public static final int CONTAINER_TOP_HEIGHT = containerTopHeight(ROWS);
    public static final int PLAYER_INVENTORY_TEXTURE_Y = 126;
    public static final int PLAYER_INVENTORY_HEIGHT = 96;

    private AlbumMenuLayout() {}

    public static int capacity(int rows) {
        checkRows(rows);
        return COLUMNS * rows;
    }

    public static int imageHeight(int rows) {
        checkRows(rows);
        return 114 + rows * 18;
    }

    /** Height of the generic_54 top part that holds the Album slots. */
    public static int containerTopHeight(int rows) {
        checkRows(rows);
        return rows * 18 + 17;
    }

    public static int inventoryLabelY(int rows) {
        return imageHeight(rows) - 94;
    }

    public static int playerInventoryTop(int rows) {
        checkRows(rows);
        return 103 + (rows - 4) * 18;
    }

    public static int hotbarY(int rows) {
        checkRows(rows);
        return 161 + (rows - 4) * 18;
    }

    public static int albumSlotX(int index) {
        return albumSlotX(ROWS, index);
    }

    public static int albumSlotY(int index) {
        return albumSlotY(ROWS, index);
    }

    public static int albumSlotX(int rows, int index) {
        checkAlbumSlot(rows, index);
        return 8 + (index % COLUMNS) * 18;
    }

    public static int albumSlotY(int rows, int index) {
        checkAlbumSlot(rows, index);
        return ALBUM_SLOT_Y + (index / COLUMNS) * 18;
    }

    /** Main inventory row 0..2; the hotbar has its own {@link #HOTBAR_Y}. */
    public static int playerInventoryY(int row) {
        return playerInventoryY(ROWS, row);
    }

    public static int playerInventoryY(int rows, int row) {
        if (row < 0 || row >= 3) throw new IndexOutOfBoundsException("player inventory row: " + row);
        return playerInventoryTop(rows) + row * 18;
    }

    private static void checkRows(int rows) {
        if (rows < 1 || rows > GOLDEN_ROWS) throw new IllegalArgumentException("album rows: " + rows);
    }

    private static void checkAlbumSlot(int rows, int index) {
        if (index < 0 || index >= capacity(rows)) throw new IndexOutOfBoundsException("album slot: " + index);
    }
}
