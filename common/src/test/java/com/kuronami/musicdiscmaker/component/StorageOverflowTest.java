package com.kuronami.musicdiscmaker.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntPredicate;

import org.junit.jupiter.api.Test;

/**
 * 収納上限を超えた時の規則を固定する。開く時は保存順の先頭から最小件数だけ外へ出し、
 * 挿入は入らないだけ。どちらもプレイヤーへ文を出さない (容量超過の failureKey は null)。
 */
class StorageOverflowTest {

    private static final IntPredicate ANY = index -> true;

    @Test
    void splitDropsTheSmallestPrefixThatFits() {
        // 末尾2件まで縮めれば収まる → 先頭2件だけを返す。
        final IntPredicate fitsLastTwo = kept -> kept <= 2;
        final StorageOverflow.Split split = StorageOverflow.split(4, fitsLastTwo, ANY);
        assertNotNull(split);
        assertEquals(2, split.kept());
        assertEquals(2, split.returned());
        assertEquals(4, split.total());
    }

    @Test
    void splitDropsOneWhenOnlyOneNeedsToGo() {
        final StorageOverflow.Split split = StorageOverflow.split(5, kept -> kept <= 4, ANY);
        assertNotNull(split);
        assertEquals(4, split.kept());
        assertEquals(1, split.returned());
    }

    @Test
    void splitReturnsEverythingWhenOnlyTheEmptyMachineFits() {
        final StorageOverflow.Split split = StorageOverflow.split(3, kept -> kept == 0, ANY);
        assertNotNull(split);
        assertEquals(0, split.kept());
        assertEquals(3, split.returned());
        assertEquals(3, split.total());
    }

    @Test
    void splitIsNullWhenEvenTheEmptyMachineDoesNotFit() {
        assertNull(StorageOverflow.split(3, kept -> false, ANY));
    }

    @Test
    void splitIsNullWhenAnEntryToDropCannotStandAlone() {
        // 2件外さないと収まらないが、2件目を単独で渡せない → 途中で諦めて null。
        final IntPredicate droppable = index -> index != 1;
        assertNull(StorageOverflow.split(4, kept -> kept <= 2, droppable));
    }

    @Test
    void splitNeverChangesTheEntryCount() {
        for (int entries = 0; entries <= 8; entries++) {
            for (int threshold = -1; threshold <= entries; threshold++) {
                final int limit = threshold;
                final StorageOverflow.Split split = StorageOverflow.split(entries, kept -> kept <= limit, ANY);
                if (threshold < 0) {
                    assertNull(split, "entries=" + entries);
                    continue;
                }
                assertNotNull(split, "entries=" + entries + " threshold=" + threshold);
                assertEquals(entries, split.total(), "entries=" + entries + " threshold=" + threshold);
                // 外へ出すのは先頭から最小件数。
                assertEquals(entries - Math.min(entries, threshold), split.returned(),
                        "entries=" + entries + " threshold=" + threshold);
            }
        }
    }

    @Test
    void acceptsInsertStopsAtTheEntryLimit() {
        assertTrue(StorageOverflow.acceptsInsert(255, 256));
        assertFalse(StorageOverflow.acceptsInsert(256, 256));
        assertFalse(StorageOverflow.acceptsInsert(257, 256));
        assertTrue(StorageOverflow.acceptsInsert(0, 1));
    }

    @Test
    void capacityOverflowHasNoPlayerFacingText() {
        assertNull(StorageOverflow.failureKey(true));
        assertEquals(AlbumStorageBudget.SYNC_FAILED_KEY, StorageOverflow.failureKey(false));
    }
}
