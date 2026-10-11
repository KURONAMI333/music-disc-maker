package com.kuronami.musicdiscmaker.menu;

/** Dependency-free geometry checks; run with javac/java. */
public final class AlbumMenuLayoutTest {
    private static final int SLOT_FRAME = 16;

    public static void main(String[] args) {
        verifiesNineSlotFirstRowAndTenToEighteenSlotCoordinates();
        verifiesAllFramesFitTheContainerTopAndImage();
        verifiesPlayerInventoryRowsAndHotbarStayBelowAlbumSlots();
        rejectsIndicesOutsideTheVisibleMenu();
        goldenAlbumMatchesTheVanillaLargeChest();
        System.out.println("AlbumMenuLayoutTest: 5 scenarios passed");
    }

    private static void verifiesNineSlotFirstRowAndTenToEighteenSlotCoordinates() {
        equal(9, AlbumMenuLayout.COLUMNS, "columns");
        equal(2, AlbumMenuLayout.ROWS, "rows");
        equal(18, AlbumMenuLayout.CAPACITY, "capacity");
        for (int i = 0; i < 9; i++) {
            equal(8 + i * 18, AlbumMenuLayout.albumSlotX(i), "first row x " + i);
            equal(18, AlbumMenuLayout.albumSlotY(i), "first row y " + i);
        }
        equal(8, AlbumMenuLayout.albumSlotX(9), "tenth slot x");
        equal(36, AlbumMenuLayout.albumSlotY(9), "tenth slot y");
        equal(152, AlbumMenuLayout.albumSlotX(17), "eighteenth slot x");
        equal(36, AlbumMenuLayout.albumSlotY(17), "eighteenth slot y");
    }

    private static void verifiesAllFramesFitTheContainerTopAndImage() {
        equal(176, AlbumMenuLayout.IMAGE_WIDTH, "image width");
        equal(150, AlbumMenuLayout.IMAGE_HEIGHT, "image height");
        for (int i = 0; i < AlbumMenuLayout.CAPACITY; i++) {
            int x = AlbumMenuLayout.albumSlotX(i);
            int y = AlbumMenuLayout.albumSlotY(i);
            require(x >= 0 && x + SLOT_FRAME <= AlbumMenuLayout.IMAGE_WIDTH, "album x outside image: " + i);
            require(y >= 0 && y + SLOT_FRAME <= AlbumMenuLayout.CONTAINER_TOP_HEIGHT,
                    "album frame outside top background: " + i);
        }
        equal(53, AlbumMenuLayout.CONTAINER_TOP_HEIGHT, "top background height");
        equal(149, AlbumMenuLayout.CONTAINER_TOP_HEIGHT + AlbumMenuLayout.PLAYER_INVENTORY_HEIGHT,
                "bottom background end");
        require(AlbumMenuLayout.CONTAINER_TOP_HEIGHT + AlbumMenuLayout.PLAYER_INVENTORY_HEIGHT
                        <= AlbumMenuLayout.IMAGE_HEIGHT,
                "inventory background outside image");
        require(AlbumMenuLayout.PLAYER_INVENTORY_TEXTURE_Y + AlbumMenuLayout.PLAYER_INVENTORY_HEIGHT <= 256,
                "inventory UV outside generic_54 texture");
    }

    private static void verifiesPlayerInventoryRowsAndHotbarStayBelowAlbumSlots() {
        int albumBottom = AlbumMenuLayout.albumSlotY(AlbumMenuLayout.CAPACITY - 1) + SLOT_FRAME;
        for (int row = 0; row < 3; row++) {
            int y = AlbumMenuLayout.playerInventoryY(row);
            require(y >= AlbumMenuLayout.PLAYER_INVENTORY_Y, "inventory row begins too high: " + row);
            require(y >= albumBottom, "inventory overlaps album: " + row);
            require(y + SLOT_FRAME <= AlbumMenuLayout.IMAGE_HEIGHT, "inventory row outside image: " + row);
        }
        equal(67, AlbumMenuLayout.PLAYER_INVENTORY_Y, "inventory top");
        equal(56, AlbumMenuLayout.INVENTORY_LABEL_Y, "inventory label");
        require(AlbumMenuLayout.INVENTORY_LABEL_Y < AlbumMenuLayout.PLAYER_INVENTORY_Y,
                "inventory label overlaps inventory slots");
        equal(125, AlbumMenuLayout.HOTBAR_Y, "hotbar top");
        require(AlbumMenuLayout.HOTBAR_Y >= AlbumMenuLayout.playerInventoryY(2) + SLOT_FRAME,
                "hotbar overlaps main inventory");
        require(AlbumMenuLayout.HOTBAR_Y + SLOT_FRAME <= AlbumMenuLayout.IMAGE_HEIGHT,
                "hotbar outside image");
    }

    private static void rejectsIndicesOutsideTheVisibleMenu() {
        throwsIndexOutOfBounds(() -> AlbumMenuLayout.albumSlotX(-1), "negative slot x");
        throwsIndexOutOfBounds(() -> AlbumMenuLayout.albumSlotY(18), "past-capacity slot y");
        throwsIndexOutOfBounds(() -> AlbumMenuLayout.playerInventoryY(-1), "negative inventory row");
        throwsIndexOutOfBounds(() -> AlbumMenuLayout.playerInventoryY(3), "past-last inventory row");
    }

    /** Values from vanilla ContainerScreen/ChestMenu for six rows. */
    private static void goldenAlbumMatchesTheVanillaLargeChest() {
        final int rows = AlbumMenuLayout.GOLDEN_ROWS;
        equal(54, AlbumMenuLayout.GOLDEN_CAPACITY, "golden capacity");
        equal(54, AlbumMenuLayout.capacity(rows), "golden capacity from rows");
        equal(222, AlbumMenuLayout.imageHeight(rows), "golden image height");
        equal(125, AlbumMenuLayout.containerTopHeight(rows), "golden top background height");
        equal(128, AlbumMenuLayout.inventoryLabelY(rows), "golden inventory label");
        equal(139, AlbumMenuLayout.playerInventoryY(rows, 0), "golden inventory top");
        equal(197, AlbumMenuLayout.hotbarY(rows), "golden hotbar top");
        equal(8, AlbumMenuLayout.albumSlotX(rows, 45), "golden last row x");
        equal(108, AlbumMenuLayout.albumSlotY(rows, 45), "golden last row y");
        equal(152, AlbumMenuLayout.albumSlotX(rows, 53), "golden last slot x");
        int albumBottom = AlbumMenuLayout.albumSlotY(rows, 53) + SLOT_FRAME;
        require(albumBottom <= AlbumMenuLayout.containerTopHeight(rows), "golden slots outside top background");
        require(AlbumMenuLayout.inventoryLabelY(rows) >= albumBottom, "golden label overlaps album slots");
        require(AlbumMenuLayout.containerTopHeight(rows) + AlbumMenuLayout.PLAYER_INVENTORY_HEIGHT
                        <= AlbumMenuLayout.imageHeight(rows), "golden inventory background outside image");
        require(AlbumMenuLayout.hotbarY(rows) + SLOT_FRAME <= AlbumMenuLayout.imageHeight(rows),
                "golden hotbar outside image");
        throwsIndexOutOfBounds(() -> AlbumMenuLayout.albumSlotY(rows, 54), "past golden capacity");
        try {
            AlbumMenuLayout.capacity(7);
            throw new AssertionError("seven rows accepted");
        } catch (IllegalArgumentException expected) {
            // generic_54 has six rows.
        }
    }

    private static void throwsIndexOutOfBounds(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message + " did not reject index");
        } catch (IndexOutOfBoundsException expected) {
            // Expected range check.
        }
    }

    private static void equal(int expected, int actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
