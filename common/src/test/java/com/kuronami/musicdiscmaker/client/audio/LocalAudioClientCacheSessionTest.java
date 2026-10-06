package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.kuronami.musicdiscmaker.network.LocalAudioPayload;

/** Connection-scoped receive ownership and the cache-hit availability handshake are headless rules. */
class LocalAudioClientCacheSessionTest {
    private static final String HASH = "a".repeat(64);

    @Test void cacheHitRequestsWorldAvailabilityWithoutStartingAnotherFileTransfer() throws Exception {
        LocalAudioClient.Session session = new LocalAudioClient.Session();
        Path cached = Path.of("cached-audio");
        var cacheCheck = session.acquireReceive(HASH, session.connectionGeneration, cached);
        assertTrue(cacheCheck.created());
        assertSame(cached, cacheCheck.receive().cachedPath);
        assertEquals(LocalAudioPayload.CHECK, cacheCheck.receive().requestOperation());

        var joined = session.acquireReceive(HASH, session.connectionGeneration, null);
        assertFalse(joined.created());
        assertSame(cacheCheck.receive(), joined.receive());

        var download = session.acquireReceive("b".repeat(64), session.connectionGeneration, null);
        assertEquals(LocalAudioPayload.FETCH, download.receive().requestOperation());
    }

    @Test void sameHashCanStartFreshAfterDisconnectAndOldWorkCannotRepopulateSession() throws Exception {
        LocalAudioClient.Session session = new LocalAudioClient.Session();
        long oldConnection = session.connectionGeneration;
        var previous = session.acquireReceive(HASH, oldConnection, null).receive();
        UUID oldTransfer = UUID.randomUUID();
        assertTrue(previous.acceptData(oldTransfer, previous.request.getLeastSignificantBits(), 0, oldConnection));

        assertEquals(1, session.disconnectReceives().size());
        assertNull(session.receive(HASH));
        assertThrows(IOException.class, () -> session.acquireReceive(HASH, oldConnection, null));

        var current = session.acquireReceive(HASH, session.connectionGeneration, null);
        assertTrue(current.created());
        assertNotSame(previous, current.receive());
        assertSame(current.receive(), session.receive(HASH));
        // Old network chunks and timeout cleanup from the previous connection cannot touch a retry.
        assertFalse(previous.acceptData(oldTransfer, previous.request.getLeastSignificantBits(), 0, session.connectionGeneration));
        assertFalse(session.removeReceive(HASH, previous));
        assertSame(current.receive(), session.receive(HASH));
    }

    @Test void receivePinsOneTransferIdAndRejectsLateOrMismatchedChunks() throws Exception {
        LocalAudioClient.Session session = new LocalAudioClient.Session();
        var receive = session.acquireReceive(HASH, session.connectionGeneration, null).receive();
        long generation = receive.request.getLeastSignificantBits();
        UUID transfer = UUID.randomUUID();

        assertFalse(receive.acceptData(transfer, generation + 1, 0, session.connectionGeneration));
        assertFalse(receive.acceptData(transfer, generation, 0, session.connectionGeneration + 1));
        assertFalse(receive.acceptData(transfer, generation, 1, session.connectionGeneration));
        assertTrue(receive.acceptData(transfer, generation, 0, session.connectionGeneration));
        // While its chunk is being written, a duplicate cannot claim the same receive slot.
        assertFalse(receive.acceptData(transfer, generation, 0, session.connectionGeneration));

        receive.busy = false; receive.offset = 16_384; // mirrors append completion and ACK scheduling
        assertFalse(receive.acceptData(transfer, generation, 0, session.connectionGeneration));
        assertFalse(receive.acceptData(UUID.randomUUID(), generation, 16_384, session.connectionGeneration));
        assertTrue(receive.acceptData(transfer, generation, 16_384, session.connectionGeneration));
    }

    @Test void lateTimeoutCleanupCannotRemoveRetryForSameHash() throws Exception {
        LocalAudioClient.Session session = new LocalAudioClient.Session();
        var timedOut = session.acquireReceive(HASH, session.connectionGeneration, null).receive();
        assertTrue(session.timeoutReceive(HASH, timedOut, new java.util.concurrent.TimeoutException()));

        var retry = session.acquireReceive(HASH, session.connectionGeneration, null).receive();
        assertNotSame(timedOut, retry);
        assertFalse(session.timeoutReceive(HASH, timedOut, new java.util.concurrent.TimeoutException()));
        assertSame(retry, session.receive(HASH));
    }

    @Test void timeoutErrorMustMatchRequestTransferAndConnectionGeneration() throws Exception {
        LocalAudioClient.Session session = new LocalAudioClient.Session();
        long oldConnection = session.connectionGeneration;
        var receive = session.acquireReceive(HASH, oldConnection, null).receive();
        UUID transfer = UUID.randomUUID();
        assertTrue(receive.acceptData(transfer, receive.request.getLeastSignificantBits(), 0, oldConnection));

        assertNull(session.failReceive(HASH, UUID.randomUUID(), receive.request.getLeastSignificantBits(), oldConnection));
        assertNull(session.failReceive(HASH, transfer, receive.request.getLeastSignificantBits() + 1, oldConnection));
        assertNull(session.failReceive("b".repeat(64), receive.request,
                receive.request.getLeastSignificantBits(), oldConnection));
        assertSame(receive, session.receive(HASH));
        assertSame(receive, session.failReceive(HASH, transfer, receive.request.getLeastSignificantBits(), oldConnection));
        assertNull(session.receive(HASH));

        session.disconnectReceives();
        var retry = session.acquireReceive(HASH, session.connectionGeneration, null).receive();
        assertNull(session.failReceive(HASH, receive.request, receive.request.getLeastSignificantBits(), oldConnection));
        assertSame(retry, session.receive(HASH));
    }

    @Test void timedOutOwnerCanFailEveryCallerSharingItsReceiveFuture() throws Exception {
        LocalAudioClient.Session session = new LocalAudioClient.Session();
        var owner = session.acquireReceive(HASH, session.connectionGeneration, null).receive();
        var joined = session.acquireReceive(HASH, session.connectionGeneration, null).receive();
        assertSame(owner, joined);

        assertTrue(session.timeoutReceive(HASH, owner,
                new java.util.concurrent.TimeoutException("download timeout")));

        assertTrue(owner.future.isCompletedExceptionally());
        assertTrue(joined.future.isCompletedExceptionally());
    }

    @Test void disconnectedReceiveCannotPublishAfterSameHashRetryStarts() throws Exception {
        LocalAudioClient.Session session = new LocalAudioClient.Session();
        long oldConnection = session.connectionGeneration;
        var oldReceive = session.acquireReceive(HASH, oldConnection, null).receive();
        session.disconnectReceives();
        var retry = session.acquireReceive(HASH, session.connectionGeneration, null).receive();

        assertFalse(session.completeReceive(HASH, oldReceive, Path.of("stale-audio")));
        assertSame(retry, session.receive(HASH));
        assertFalse(retry.future.isDone());
    }
}
