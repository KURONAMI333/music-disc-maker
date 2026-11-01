package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 画面が取り除かれた時・cancel が別 menu から呼ばれた時に、保持した取込を中止する条件。 */
class ScreenRemovalDecisionTest {
    private final Object maker = new Object();
    private final Object inventory = new Object();
    private final Object otherMaker = new Object();

    @Test
    void recipeScreenOverMakerKeepsTheImport() {
        // JEI のレシピ画面: 画面は替わるが containerMenu は製作機のまま。
        assertFalse(LocalImportDecisions.cancelsOnScreenRemoved(maker, maker));
    }

    @Test
    void closeDeathOrDisconnectCancels() {
        assertTrue(LocalImportDecisions.cancelsOnScreenRemoved(maker, inventory)); // ESC・サーバー close
        assertTrue(LocalImportDecisions.cancelsOnScreenRemoved(maker, null)); // 未接続・player 無し
    }

    @Test
    void cancelFromOwnMenuAlwaysAborts() {
        assertTrue(LocalImportDecisions.cancelAborts(maker, maker, maker));
        assertTrue(LocalImportDecisions.cancelAborts(maker, maker, inventory));
    }

    @Test
    void cancelFromAnotherMenuAbortsOnlyIfHeldMenuIsGone() {
        assertFalse(LocalImportDecisions.cancelAborts(maker, otherMaker, maker)); // 古い画面は開いたまま
        assertTrue(LocalImportDecisions.cancelAborts(maker, otherMaker, otherMaker)); // 新しい製作機を開いた
        assertTrue(LocalImportDecisions.cancelAborts(maker, otherMaker, null));
    }
}
