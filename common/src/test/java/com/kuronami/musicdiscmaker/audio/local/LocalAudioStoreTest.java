package com.kuronami.musicdiscmaker.audio.local;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 転送相手を信頼しない保存境界。MP3自体のデコードは別の実音源probeで検査する。 */
class LocalAudioStoreTest {
    @TempDir Path temp;
    private final UUID owner = UUID.randomUUID();
    private static final byte[] DATA = new byte[] {1, 2, 3, 4};
    private static final LocalAudioStore.Limits LIMITS = new LocalAudioStore.Limits(32, 64, 2, 100);

    private static String hash(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private void put(LocalAudioStore store) throws Exception {
        UUID id = store.begin(owner, hash(DATA), DATA.length, 0);
        store.append(owner, id, 0, DATA, 1);
        store.finish(owner, id, 2, path -> {});
    }

    private void put(LocalAudioStore store, UUID sender, byte[] data, long now) throws Exception {
        UUID id = store.begin(sender, hash(data), data.length, now);
        store.append(sender, id, 0, data, now + 1);
        store.finish(sender, id, now + 2, path -> {});
    }

    @Test void restartAndDedupPreserveOriginal() throws Exception {
        try (var store = new LocalAudioStore(temp, LIMITS)) {
            put(store); put(store);
            assertEquals(DATA.length, store.storedBytes());
            assertEquals(0, store.reservedBytes());
        }
        try (var store = new LocalAudioStore(temp, LIMITS)) {
            assertArrayEquals(DATA, Files.readAllBytes(store.verified(hash(DATA))));
            assertEquals(DATA.length, store.storedBytes());
        }
    }

    @Test void otherPlayerCannotAppendCancelOrFinishTransfer() throws Exception {
        try (var store = new LocalAudioStore(temp, LIMITS)) {
            UUID id = store.begin(owner, hash(DATA), DATA.length, 0);
            UUID other = UUID.randomUUID();
            assertThrows(IOException.class, () -> store.append(other, id, 0, DATA, 1));
            assertThrows(IOException.class, () -> store.cancel(other, id));
            assertThrows(IOException.class, () -> store.finish(other, id, 1, p -> {}));
            store.append(owner, id, 0, DATA, 2);
            store.finish(owner, id, 3, p -> {});
        }
    }

    @Test void corruptOrIncompleteTransferNeverPublishes() throws Exception {
        try (var store = new LocalAudioStore(temp, LIMITS)) {
            UUID id = store.begin(owner, hash(DATA), DATA.length, 0);
            store.append(owner, id, 0, new byte[] {4, 3, 2, 1}, 1);
            assertThrows(IOException.class, () -> store.finish(owner, id, 2, p -> fail("Invalid hash reached validator")));
            assertNull(store.verified(hash(DATA)));
            UUID second = store.begin(owner, hash(DATA), DATA.length, 3);
            assertThrows(IOException.class, () -> store.finish(owner, second, 4, p -> {}));
            assertEquals(0, store.reservedBytes());
        }
    }

    @Test void decoderRejectionReleasesReservation() throws Exception {
        try (var store = new LocalAudioStore(temp, LIMITS)) {
            UUID id = store.begin(owner, hash(DATA), DATA.length, 0);
            store.append(owner, id, 0, DATA, 1);
            assertThrows(IOException.class, () -> store.finish(owner, id, 2, p -> { throw new IOException("Not audio"); }));
            assertNull(store.verified(hash(DATA)));
            assertEquals(0, store.reservedBytes());
            try (var parts = Files.list(temp.resolve("incoming"))) { assertEquals(0, parts.count()); }
        }
    }

    @Test void outOfOrderOrOversizedChunksCancel() throws Exception {
        try (var store = new LocalAudioStore(temp, LIMITS)) {
            UUID id = store.begin(owner, hash(DATA), DATA.length, 0);
            assertThrows(IOException.class, () -> store.append(owner, id, 1, DATA, 1));
            assertEquals(0, store.reservedBytes());
            UUID second = store.begin(owner, hash(DATA), DATA.length, 2);
            assertThrows(IOException.class, () -> store.append(owner, second, 0, new byte[5], 3));
            assertEquals(0, store.reservedBytes());
        }
    }

    @Test void fileAndConcurrentQuotaIncludesReservations() throws Exception {
        try (var store = new LocalAudioStore(temp, LIMITS)) {
            assertThrows(IOException.class, () -> store.begin(owner, hash(DATA), 33, 0));
            UUID first = store.begin(owner, hash(DATA), 32, 0);
            assertThrows(IOException.class, () -> store.begin(owner, hash(DATA), 4, 0));
            store.begin(UUID.randomUUID(), hash(DATA), 32, 0);
            assertThrows(IOException.class, () -> store.begin(UUID.randomUUID(), hash(DATA), 4, 0));
            store.cancel(owner, first);
            assertEquals(32, store.reservedBytes());
        }
    }

    @Test void committedBytesAlsoCountTowardQuota() throws Exception {
        try (var store = new LocalAudioStore(temp, new LocalAudioStore.Limits(4, 6, 2, 100))) {
            put(store);
            assertThrows(IOException.class, () -> store.begin(owner, hash(DATA), 4, 0));
        }
    }

    @Test void fullStoreCanReuseVerifiedExistingAudioWithoutUpload() throws Exception {
        try (var store = new LocalAudioStore(temp, new LocalAudioStore.Limits(4, 4, 2, 100))) {
            put(store);
            var prepared = store.prepare(owner, hash(DATA), 4, 3);
            assertNull(prepared.transferId());
            assertArrayEquals(DATA, Files.readAllBytes(prepared.existing()));
            assertEquals(0, store.reservedBytes());
        }
    }

    @Test void fullDisposableCacheEvictsOnlyOldestEntryToAcceptAnotherAudio() throws Exception {
        byte[] first = new byte[] {1, 2, 3, 4};
        byte[] second = new byte[] {5, 6, 7, 8};
        byte[] third = new byte[] {9, 10, 11, 12};
        String firstHash = hash(first);
        String secondHash = hash(second);
        String thirdHash = hash(third);
        try (var cache = LocalAudioStore.clientCache(temp, new LocalAudioStore.Limits(4, 8, 2, 100))) {
            put(cache, owner, first, 0);
            Files.setLastModifiedTime(temp.resolve(firstHash), FileTime.fromMillis(1_000));
            put(cache, UUID.randomUUID(), second, 3);
            Files.setLastModifiedTime(temp.resolve(secondHash), FileTime.fromMillis(2_000));

            put(cache, UUID.randomUUID(), third, 6);

            assertNull(cache.verified(firstHash));
            assertArrayEquals(second, Files.readAllBytes(cache.verified(secondHash)));
            assertArrayEquals(third, Files.readAllBytes(cache.verified(thirdHash)));
            assertEquals(8, cache.storedBytes());
            assertEquals(0, cache.reservedBytes());
        }
    }

    @Test void corruptDisposableCacheEntryIsRemovedAndCanBeRefetched() throws Exception {
        try (var cache = LocalAudioStore.clientCache(temp, new LocalAudioStore.Limits(4, 4, 2, 100))) {
            put(cache, owner, DATA, 0);
            Path cached = cache.verified(hash(DATA));
            Files.write(cached, new byte[] {9, 9, 9, 9});

            var prepared = cache.prepare(owner, hash(DATA), DATA.length, 3);

            assertNull(prepared.existing());
            assertNotNull(prepared.transferId());
            assertEquals(0, cache.storedBytes());
            cache.append(owner, prepared.transferId(), 0, DATA, 4);
            Path repaired = cache.finish(owner, prepared.transferId(), 5, path -> {});

            assertArrayEquals(DATA, Files.readAllBytes(repaired));
            assertEquals(DATA.length, cache.storedBytes());
            assertEquals(0, cache.reservedBytes());
        }
    }

    @Test void deletionFailureKeepsReservationUntilCleanupSucceeds() throws Exception {
        try (var store = new LocalAudioStore(temp, LIMITS)) {
            UUID id = store.begin(owner, hash(DATA), 4, 0);
            Path part;
            try (var parts = Files.list(temp.resolve("incoming"))) { part = parts.findFirst().orElseThrow(); }
            Path obstacle = part.resolve("locked");
            store.append(owner, id, 0, DATA, 1);
            // ストリームが閉じた検査時に置換し、Windowsでもdelete失敗を再現する。
            assertThrows(IOException.class, () -> store.finish(owner, id, 2, path -> {
                Files.delete(path);
                Files.createDirectory(path);
                Files.write(obstacle, DATA);
                throw new IOException("Rejected audio");
            }));
            assertEquals(4, store.reservedBytes());
            assertThrows(IOException.class, () -> store.append(owner, id, 0, DATA, 3));
            Files.delete(obstacle);
            store.expire(4);
            assertEquals(0, store.reservedBytes());
            assertFalse(Files.exists(part));
        }
    }

    @Test void expiryAndCloseReleaseIncompleteFiles() throws Exception {
        try (var store = new LocalAudioStore(temp, LIMITS)) {
            UUID id = store.begin(owner, hash(DATA), 4, 0);
            store.expire(100);
            assertThrows(IOException.class, () -> store.append(owner, id, 0, DATA, 101));
            store.begin(owner, hash(DATA), 4, 101);
        }
        try (var parts = Files.list(temp.resolve("incoming"))) { assertEquals(0, parts.count()); }
    }

    @Test void pathTraversalAndSymlinkAreRejected() throws Exception {
        try (var store = new LocalAudioStore(temp, LIMITS)) {
            assertThrows(IOException.class, () -> store.begin(owner, "../outside", 4, 0));
            assertThrows(IOException.class, () -> store.verified("../outside"));
            Path outside = Files.write(temp.resolve("outside"), DATA);
            // hash 名(拡張子なし)と旧 hash.mp3 名のどちらのsymlinkも弾く。
            Files.createSymbolicLink(temp.resolve(hash(DATA)), outside);
            assertThrows(IOException.class, () -> store.verified(hash(DATA)));
            String other = hash(new byte[] {9});
            Files.createSymbolicLink(temp.resolve(other + ".mp3"), outside);
            assertThrows(IOException.class, () -> store.verified(other));
        }
    }

    @Test void corruptionIsNotSilentlyPlayed() throws Exception {
        try (var store = new LocalAudioStore(temp, LIMITS)) {
            put(store);
            Files.write(store.verified(hash(DATA)), new byte[] {0, 0, 0, 0});
            assertThrows(IOException.class, () -> store.verified(hash(DATA)));
        }
    }

    @Test void legacyMp3NamedAudioRemainsReadableAndReusable() throws Exception {
        // MP3専用期の hash.mp3 原本は、新しい hash 名(拡張子なし)と同一のものとして扱う。
        Files.write(temp.resolve(hash(DATA) + ".mp3"), DATA);
        try (var store = new LocalAudioStore(temp, LIMITS)) {
            assertArrayEquals(DATA, Files.readAllBytes(store.verified(hash(DATA))));
            assertEquals(DATA.length, store.storedBytes());
            var prepared = store.prepare(owner, hash(DATA), DATA.length, 0);
            assertNull(prepared.transferId());
            assertNotNull(prepared.existing());
            assertTrue(prepared.existing().getFileName().toString().endsWith(".mp3"));
        }
    }

    @Test void exclusiveOwnerAndCrashRemainders() throws Exception {
        Files.createDirectories(temp.resolve("incoming"));
        Files.write(temp.resolve("incoming/upload-crashed.part"), DATA);
        try (var store = new LocalAudioStore(temp, LIMITS)) {
            assertFalse(Files.exists(temp.resolve("incoming/upload-crashed.part")));
            assertThrows(IOException.class, () -> new LocalAudioStore(temp, LIMITS));
            put(store);
        }
        try (var reopened = new LocalAudioStore(temp, LIMITS)) { assertNotNull(reopened.verified(hash(DATA))); }
    }
}
