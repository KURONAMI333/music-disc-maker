package com.kuronami.musicdiscmaker.audio.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class LocalAudioQuotaTest {
    @TempDir Path root;
    final UUID player = UUID.randomUUID();
    final String first = "a".repeat(64), second = "b".repeat(64);
    @Test void ownershipSurvivesRestartAndDoesNotDoubleCount() throws Exception {
        var quota = new LocalAudioQuota(root, 100);
        quota.record(player, first, 100);
        var reopened = new LocalAudioQuota(root, 100);
        reopened.record(player, first, 100);
        reopened.check(player, first, 100);
        assertThrows(IOException.class, () -> reopened.check(player, second, 1));
    }
    @Test void sharedBytesAreChargedToEachOwner() throws Exception {
        var quota = new LocalAudioQuota(root, 100);
        UUID other = UUID.randomUUID();
        quota.record(player, first, 70);
        quota.record(other, first, 70);
        assertThrows(IOException.class, () -> quota.check(other, second, 31));
        quota.record(other, second, 30);
        quota.check(player, second, 30);
    }
    @Test void corruptLedgerFailsClosed() throws Exception {
        Files.writeString(root.resolve(player + ".txt"), first + " -100\n");
        var quota = new LocalAudioQuota(root, 100);
        assertThrows(IOException.class, () -> quota.check(player, second, 1));
    }
    @Test void differentSizeAndTraversalAreRejected() throws Exception {
        var quota = new LocalAudioQuota(root, 100);
        quota.record(player, first, 50);
        assertThrows(IOException.class, () -> quota.record(player, first, 1));
        assertThrows(IOException.class, () -> quota.record(player, "../escape", 1));
        assertFalse(Files.exists(root.resolve("quota-.part")));
    }
}
