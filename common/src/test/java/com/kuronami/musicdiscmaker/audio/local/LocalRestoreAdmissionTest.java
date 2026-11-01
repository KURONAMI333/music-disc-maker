package com.kuronami.musicdiscmaker.audio.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 上げ直しの受け入れ順。store の枠を開けなかった時・容量が無い時に、
 * 「原本が無い」と答えた記録を消費しないこと (消費すると client は次の再生まで上げ直せない)。
 */
class LocalRestoreAdmissionTest {

    @TempDir Path temp;
    private final UUID owner = UUID.randomUUID();
    private static final String HASH = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0fedcba9876543210";
    private static final long SIZE = 4L;

    private LocalAudioStore store(int transfers) throws IOException {
        return new LocalAudioStore(temp.resolve("store"),
                new LocalAudioStore.Limits(32, 64, transfers, 100));
    }

    private LocalAudioQuota quota(long maximum) throws IOException {
        return new LocalAudioQuota(temp.resolve("quota"), maximum);
    }

    /** 記録が無ければ枠を閉じて受け入れない (開きっぱなしにしない)。 */
    @Test
    void closesTheSlotWhenThereIsNoNotice() throws Exception {
        try (var store = store(2)) {
            final UUID opened = LocalRestoreAdmission.open(store, quota(64), owner, HASH, SIZE, 0, () -> false);
            assertNull(opened, "記録が無いのに受け入れた");
            assertEquals(0L, store.reservedBytes(), "記録が無い時に枠が開いたまま");
        }
    }

    /** 記録があれば枠を開いて受け入れる。 */
    @Test
    void opensTheSlotWhenTheNoticeIsConsumed() throws Exception {
        try (var store = store(2)) {
            final UUID opened = LocalRestoreAdmission.open(store, quota(64), owner, HASH, SIZE, 0, () -> true);
            assertNotNull(opened);
            assertTrue(store.reservedBytes() >= SIZE, "枠が確保されていない");
            store.cancel(owner, opened);
        }
    }

    /**
     * 枠が埋まっていて store が断った時は、記録を消費しない。
     * ここで消費すると、通知だけが消えて client は次の再生まで上げ直せない。
     */
    @Test
    void keepsTheNoticeWhenTheStoreCannotOpenASlot() throws Exception {
        final AtomicBoolean consumed = new AtomicBoolean();
        try (var store = store(1)) {
            // 同じ player の転送を 1 本走らせておく (2 本目は Transfer limit reached)。
            store.begin(owner, HASH, SIZE, 0);
            assertThrows(IOException.class, () -> LocalRestoreAdmission.open(
                    store, quota(64), owner, HASH, SIZE, 1, () -> consumed.getAndSet(true)));
            assertTrue(!consumed.get(), "store が断ったのに「原本が無い」の記録を消費した");
        }
    }

    /** 容量が無い時も同じ。quota の検査は store の枠より先に走る。 */
    @Test
    void keepsTheNoticeWhenTheQuotaIsFull() throws Exception {
        final AtomicBoolean consumed = new AtomicBoolean();
        try (var store = store(2)) {
            assertThrows(IOException.class, () -> LocalRestoreAdmission.open(
                    store, quota(1), owner, HASH, SIZE, 0, () -> consumed.getAndSet(true)));
            assertTrue(!consumed.get(), "容量不足で断ったのに「原本が無い」の記録を消費した");
            assertEquals(0L, store.reservedBytes(), "断ったのに枠が開いたまま");
        }
    }

    /** 上げ直しが済んだら、同じ hash で missing と答えた他の player だけを、1 回ずつ取り出す。 */
    @Test
    void takesOnlyFreshWaitersForTheHash() {
        final UUID fresh = UUID.randomUUID(), stale = UUID.randomUUID(), other = UUID.randomUUID();
        final var missing = new java.util.HashMap<UUID, java.util.Map<String, Long>>();
        missing.put(fresh, new java.util.HashMap<>(java.util.Map.of(HASH, 900L)));
        missing.put(stale, new java.util.HashMap<>(java.util.Map.of(HASH, 0L)));
        missing.put(other, new java.util.HashMap<>(java.util.Map.of("f".repeat(64), 900L)));
        final var taken = LocalRestoreAdmission.takeWaiting(missing, HASH, 1000L, 500L);
        assertEquals(java.util.List.of(fresh), taken);
        assertTrue(LocalRestoreAdmission.takeWaiting(missing, HASH, 1000L, 500L).isEmpty(), "通知が 2 回出る");
        assertTrue(missing.get(other).containsKey("f".repeat(64)), "別の hash の記録を消した");
    }
}
