package com.kuronami.musicdiscmaker.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.menu.MusicDiscMakerLayout;

/**
 * URL 欄右上の3連ボタン・状態行・警告印の座標契約。screen/menu が共有する
 * {@link MusicDiscMakerLayout} の定数を直接検査し、URL 欄・入出力スロット・
 * 在庫ラベルとの衝突を座標で防ぐ。
 */
class MusicDiscMakerLayoutTest {

    @Test
    void toolButtonsAreSquareAndOrderedLeftToRight() {
        assertEquals(MusicDiscMakerLayout.TOOL_RIGHT - MusicDiscMakerLayout.TOOL_SIZE * 3
                - MusicDiscMakerLayout.TOOL_GAP * 2, MusicDiscMakerLayout.TOOL_PASTE_X);
        assertEquals(MusicDiscMakerLayout.TOOL_PASTE_X + MusicDiscMakerLayout.TOOL_SIZE
                + MusicDiscMakerLayout.TOOL_GAP, MusicDiscMakerLayout.TOOL_FOLDER_X);
        assertEquals(MusicDiscMakerLayout.TOOL_FOLDER_X + MusicDiscMakerLayout.TOOL_SIZE
                + MusicDiscMakerLayout.TOOL_GAP, MusicDiscMakerLayout.TOOL_CLEAR_X);
        assertEquals(MusicDiscMakerLayout.TOOL_RIGHT,
                MusicDiscMakerLayout.TOOL_CLEAR_X + MusicDiscMakerLayout.TOOL_SIZE);
    }

    @Test
    void toolButtonsStayInsidePanelAndClearOfUrlField() {
        // 1px 枠を含めても URL 欄の右端へ触れない (URL は x=URL_X..+URL_W)。
        assertTrue(MusicDiscMakerLayout.TOOL_PASTE_X - 1
                >= MusicDiscMakerLayout.URL_X + MusicDiscMakerLayout.URL_W);
        // パネル右端の 6px 内側に収まる。
        assertTrue(MusicDiscMakerLayout.TOOL_CLEAR_X + MusicDiscMakerLayout.TOOL_SIZE + 1
                <= MusicDiscMakerLayout.PANEL_WIDTH - 5);
        assertTrue(MusicDiscMakerLayout.TOOL_Y - 1 >= 0);
    }

    @Test
    void toolButtonsDoNotOverlapSlotsOrInventory() {
        // 入出力スロット行 (47..65) より上に収まる。
        assertTrue(MusicDiscMakerLayout.TOOL_Y + MusicDiscMakerLayout.TOOL_SIZE + 1
                < MusicDiscMakerLayout.INPUT_Y);
        assertTrue(MusicDiscMakerLayout.TOOL_Y + MusicDiscMakerLayout.TOOL_SIZE + 1
                < MusicDiscMakerLayout.OUTPUT_Y);
        // 在庫ラベル (88) より上。
        assertTrue(MusicDiscMakerLayout.TOOL_Y + MusicDiscMakerLayout.TOOL_SIZE
                < MusicDiscMakerLayout.INVENTORY_LABEL_Y);
    }

    @Test
    void statusRowAndWarningSitBelowSlotsWithoutColliding() {
        // 状態行は旧ボタン帯 (y≈69-85) を再利用するが、スロット下端 (47+18=65) より下。
        assertTrue(MusicDiscMakerLayout.STATUS_Y >= MusicDiscMakerLayout.INPUT_Y + 18);
        // 1 行分の高さ (lineHeight=9) で在庫ラベルへ入らない。
        assertTrue(MusicDiscMakerLayout.STATUS_Y + 9 < MusicDiscMakerLayout.INVENTORY_LABEL_Y);
        // 警告印は状態文の右。切り詰め後の状態文と接しない 4px 以上の隙間を保つ。
        assertTrue(MusicDiscMakerLayout.WARNING_X
                >= MusicDiscMakerLayout.STATUS_X + MusicDiscMakerLayout.STATUS_MAX_W + 4);
        assertTrue(MusicDiscMakerLayout.WARNING_Y == MusicDiscMakerLayout.STATUS_Y);
        assertTrue(MusicDiscMakerLayout.WARNING_X + MusicDiscMakerLayout.WARNING_SIZE
                <= MusicDiscMakerLayout.PANEL_WIDTH);
        // 警告印はボタン行より下 (旧座標 (112,72) で起きた重なりの再発防止)。
        assertTrue(MusicDiscMakerLayout.WARNING_Y
                > MusicDiscMakerLayout.TOOL_Y + MusicDiscMakerLayout.TOOL_SIZE);
    }
}
