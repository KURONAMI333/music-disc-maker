package com.kuronami.musicdiscmaker.audio.local;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Client cache repair/eviction must never change the preserving world-store default. */
class LocalAudioClientCacheStoreTest {
    @TempDir Path temp;
    private static final byte[] FIRST = {1, 2, 3, 4};
    private static final byte[] SECOND = {5, 6, 7, 8};
    private static final LocalAudioStore.Limits FULL = new LocalAudioStore.Limits(4, 4, 2, 100);
    private final UUID owner = UUID.randomUUID();

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void put(LocalAudioStore store, byte[] bytes, long now) throws Exception {
        String hash = hash(bytes);
        UUID id = store.begin(owner, hash, bytes.length, now);
        store.append(owner, id, 0, bytes, now + 1);
        store.finish(owner, id, now + 2, path -> {});
    }

    @Test void corruptCachedCopyIsDiscardedSoTheSameHashCanBeFetchedAgain() throws Exception {
        String hash = hash(FIRST);
        try (var cache = LocalAudioStore.clientCache(temp.resolve("cache"), FULL)) {
            put(cache, FIRST, 0);
            Path path = cache.verified(hash);
            Files.write(path, SECOND);

            assertNull(cache.verified(hash));
            assertEquals(0, cache.storedBytes());

            var retry = cache.prepare(owner, hash, FIRST.length, 3);
            assertNotNull(retry.transferId());
            assertNull(retry.existing());
            cache.append(owner, retry.transferId(), 0, FIRST, 4);
            Path restored = cache.finish(owner, retry.transferId(), 5, ignored -> {});
            assertArrayEquals(FIRST, Files.readAllBytes(restored));
        }
    }

    @Test void fullClientCacheEvictsOldCopiesButWorldStoreKeepsItsOriginals() throws Exception {
        String firstHash = hash(FIRST);
        String secondHash = hash(SECOND);
        try (var cache = LocalAudioStore.clientCache(temp.resolve("cache"), FULL)) {
            put(cache, FIRST, 0);
            var next = cache.prepare(owner, secondHash, SECOND.length, 3);
            assertNotNull(next.transferId());
            assertNull(next.existing());
            assertNull(cache.verified(firstHash));
            cache.append(owner, next.transferId(), 0, SECOND, 4);
            cache.finish(owner, next.transferId(), 5, ignored -> {});
            assertArrayEquals(SECOND, Files.readAllBytes(cache.verified(secondHash)));
        }

        try (var world = new LocalAudioStore(temp.resolve("world"), FULL)) {
            put(world, FIRST, 0);
            assertThrows(java.io.IOException.class, () -> world.begin(owner, secondHash, SECOND.length, 3));
            assertArrayEquals(FIRST, Files.readAllBytes(world.verified(firstHash)));
        }
    }
}
