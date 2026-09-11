package com.kuronami.musicdiscmaker.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.menu.BoomboxMenu;

class BoomboxScreenLayoutTest {

    @Test
    void panelAndInventoryMatchTheOneSlotMenuContract() {
        assertEquals(176, BoomboxScreenLayout.PANEL_WIDTH);
        assertEquals(224, BoomboxScreenLayout.PANEL_HEIGHT);
        assertEquals(10, BoomboxScreenLayout.DISC_X);
        assertEquals(18, BoomboxScreenLayout.DISC_Y);
        assertEquals(BoomboxMenu.MEDIA_SLOT_X, BoomboxScreenLayout.DISC_X);
        assertEquals(BoomboxMenu.MEDIA_SLOT_Y, BoomboxScreenLayout.DISC_Y);
        assertEquals(2, BoomboxScreenLayout.DISC_X - BoomboxScreenLayout.INVENTORY_X);
        assertEquals(142, BoomboxScreenLayout.INVENTORY_Y);
        assertEquals(200, BoomboxScreenLayout.HOTBAR_Y);
        assertEquals(130, BoomboxScreenLayout.INVENTORY_LABEL_Y);
        assertEquals(128, BoomboxScreenLayout.MACHINE_HEIGHT);
    }

    @Test
    void radioControlsFitAboveVanillaInventory() {
        org.junit.jupiter.api.Assertions.assertTrue(BoomboxScreenLayout.SEEK_Y + BoomboxScreenLayout.SEEK_HEIGHT
                < BoomboxScreenLayout.TRANSPORT_Y);
        org.junit.jupiter.api.Assertions.assertTrue(BoomboxScreenLayout.TRANSPORT_Y + GoldenJukeboxTransportLayout.BUTTON_SIZE
                < BoomboxScreenLayout.VOLUME_Y);
        org.junit.jupiter.api.Assertions.assertTrue(BoomboxScreenLayout.VOLUME_Y + BoomboxScreenLayout.SLIDER_HEIGHT
                < BoomboxScreenLayout.MACHINE_HEIGHT);
        assertEquals(30, GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.SHUFFLE));
        assertEquals(126, GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.REPEAT));
    }
}
