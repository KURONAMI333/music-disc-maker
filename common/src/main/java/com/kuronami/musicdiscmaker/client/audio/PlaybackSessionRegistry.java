package com.kuronami.musicdiscmaker.client.audio;

import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;

/** Optional AV boundary. Provider calls, disposal, and fault callbacks never block the sound/render thread. */
public final class PlaybackSessionRegistry {
    private static final System.Logger LOGGER = System.getLogger(PlaybackSessionRegistry.class.getName());
    private static final int MAX_PENDING_DISPOSALS = 8;
    private static final int MAX_PENDING_CALLBACKS = 64;
    private static final int MAX_SESSION_SLOTS = 32;
    private static final CopyOnWriteArrayList<PlaybackSessionProvider> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final ConcurrentHashMap<PlaybackSessionId, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor CLOSER = worker("mdm-av-close", MAX_PENDING_DISPOSALS);
    private static final ThreadPoolExecutor FAULTS = worker("mdm-av-faults", MAX_PENDING_CALLBACKS);
    private static final AtomicBoolean CLOSE_OVERFLOW_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean CLOSE_OVERFLOWED = new AtomicBoolean();
    private static final AtomicBoolean FAULT_OVERFLOW_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean SESSION_LIMIT_LOGGED = new AtomicBoolean();
    private static final AtomicInteger SESSION_SLOTS = new AtomicInteger();

    private PlaybackSessionRegistry() {}

    public static final class RequestCancelledException extends Exception {
        public RequestCancelledException() { super("AV request was superseded before it became active"); }
    }
    public static final class CleanupIncompleteException extends RuntimeException {
        private final CompletionStage<Void> cleanupAcknowledged;
        public CleanupIncompleteException(Throwable cause) {
            this(cause, CompletableFuture.failedFuture(new IllegalStateException("cleanup acknowledgement unavailable", cause)));
        }
        public CleanupIncompleteException(Throwable cause, CompletionStage<Void> cleanupAcknowledged) {
            super("AV session did not acknowledge cleanup; regular audio suppressed", cause);
            this.cleanupAcknowledged = java.util.Objects.requireNonNull(cleanupAcknowledged, "cleanupAcknowledged");
        }
        public CompletionStage<Void> cleanupAcknowledged() { return cleanupAcknowledged; }
    }

    private static ThreadPoolExecutor worker(String name, int queueCapacity) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), runnable -> {
                    Thread thread = new Thread(runnable, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    private static boolean reserveSessionSlot() {
        while (true) {
            int current = SESSION_SLOTS.get();
            if (current >= MAX_SESSION_SLOTS) return false;
            if (SESSION_SLOTS.compareAndSet(current, current + 1)) return true;
        }
    }
    private static void releaseSessionSlot() { SESSION_SLOTS.decrementAndGet(); }
    private static void releaseSlotOnce(AtomicBoolean released) {
        if (released.compareAndSet(false, true)) releaseSessionSlot();
    }
    private static void releaseWhenAcknowledged(CompletionStage<Void> acknowledgement, AtomicBoolean released) {
        acknowledgement.whenComplete((ignored, failure) -> { if (failure == null) releaseSlotOnce(released); });
    }

    public static AutoCloseable register(PlaybackSessionProvider provider) {
        if (!PROVIDERS.addIfAbsent(java.util.Objects.requireNonNull(provider, "provider")))
            throw new IllegalStateException("playback provider already registered");
        AtomicBoolean removed = new AtomicBoolean();
        return () -> { if (removed.compareAndSet(false, true)) PROVIDERS.remove(provider); };
    }

    /** Evaluate once on the caller and carry this immutable choice into prefetch/load work. */
    public static PlaybackSelection select(PlaybackSourceContext source, CustomTrackData track) {
        java.util.Objects.requireNonNull(source, "source");
        if (track == null || track.isEmpty() || track.isLocal())
            return PlaybackSelection.none(source, track == null ? null : track.sourceKey());
        for (PlaybackSessionProvider provider : PROVIDERS) {
            try {
                if (provider.selects(source, track)) return new PlaybackSelection(provider, source, track.sourceKey());
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.log(System.Logger.Level.WARNING, "Optional provider selection failed; trying remaining providers", failure);
            }
        }
        return PlaybackSelection.none(source, track.sourceKey());
    }

    public static Optional<IAudioSource> tryOpen(PlaybackSessionRequest request, PlaybackSelection selection,
            Consumer<Throwable> failureSink, BooleanSupplier stillCurrent) throws Exception {
        if (request == null || selection == null || !selection.selected()
                || !selection.matches(request.source(), request.track())) return Optional.empty();
        if (!stillCurrent.getAsBoolean()) throw new RequestCancelledException();
        if (CLOSE_OVERFLOWED.get()) return Optional.empty();
        if (!reserveSessionSlot()) {
            if (SESSION_LIMIT_LOGGED.compareAndSet(false, true))
                LOGGER.log(System.Logger.Level.WARNING, "Optional AV session limit reached; suppressing new AV opens until acknowledged cleanup completes");
            return Optional.empty();
        }
        AtomicBoolean slotReleased = new AtomicBoolean();
        PlaybackMediaSession session;
        try {
            session = selection.provider().open(request);
        } catch (CleanupIncompleteException cleanupFailure) {
            releaseWhenAcknowledged(cleanupFailure.cleanupAcknowledged(), slotReleased);
            throw cleanupFailure;
        } catch (Exception | LinkageError openFailure) {
            releaseSlotOnce(slotReleased);
            LOGGER.log(System.Logger.Level.WARNING, "Optional provider failed before returning a session; using regular MDM audio", openFailure);
            return Optional.empty();
        }
        if (session == null) {
            releaseSlotOnce(slotReleased);
            if (!stillCurrent.getAsBoolean()) throw new RequestCancelledException();
            return Optional.empty();
        }
        Entry ownedEntry = null;
        try {
            if (!stillCurrent.getAsBoolean()) {
                awaitUnregisteredCleanup(session);
                releaseSlotOnce(slotReleased);
                throw new RequestCancelledException();
            }
            IAudioSource audio = session.audioSource();
            if (audio == null) throw new IllegalStateException("provider returned no PCM source");
            int rate = audio.sampleRate(), channels = audio.channels(), bits = audio.bitsPerSample();
            boolean bigEndian = audio.bigEndian();
            if (rate < 8_000 || rate > 192_000 || (channels != 1 && channels != 2)
                    || (bits != 8 && bits != 16 && bits != 24 && bits != 32))
                throw new IllegalArgumentException("provider returned an unsupported PCM format");
            Entry entry = new Entry(request.id(), session, audio, failureSink, rate, channels, bits, bigEndian, slotReleased);
            Entry previous = ENTRIES.putIfAbsent(request.id(), entry);
            if (previous != null) throw new IllegalStateException("session identity is already owned");
            ownedEntry = entry;
            if (!stillCurrent.getAsBoolean()) {
                awaitDisposal(close(entry.id));
                throw new RequestCancelledException();
            }
            return Optional.of(new SessionAudioSource(entry));
        } catch (CleanupIncompleteException cleanupFailure) {
            // A failed close is not a recoverable provider/open failure. In particular, do not
            // retry close here: an idempotent second close may falsely acknowledge leaked native state.
            releaseWhenAcknowledged(cleanupFailure.cleanupAcknowledged(), slotReleased);
            throw cleanupFailure;
        } catch (RequestCancelledException cancelled) {
            throw cancelled;
        } catch (Exception | LinkageError failure) {
            try {
                if (ownedEntry != null) {
                    awaitDisposal(close(ownedEntry.id));
                } else {
                    awaitUnregisteredCleanup(session);
                    releaseSlotOnce(slotReleased);
                }
            }
            catch (CleanupIncompleteException cleanupFailure) {
                releaseWhenAcknowledged(cleanupFailure.cleanupAcknowledged(), slotReleased);
                throw cleanupFailure;
            }
            LOGGER.log(System.Logger.Level.WARNING, "Optional provider failed and cleanup completed; using regular MDM audio", failure);
            return Optional.empty();
        }
    }

    public static Optional<PlaybackSessionId> sessionIdFor(IAudioSource source) {
        return source instanceof SessionAudioSource wrapped ? Optional.of(wrapped.entry.id) : Optional.empty();
    }
    public static boolean hasActiveSessions() { return ENTRIES.values().stream().anyMatch(e -> !e.terminal.get()); }
    public static boolean isActive(PlaybackSessionId id) {
        Entry entry = ENTRIES.get(id);
        return entry != null && !entry.terminal.get();
    }

    /** Render-side only: returns a frame snapshot or terminalizes this session on provider failure. */
    public static PlaybackVideoFrame latestVideoFrame(PlaybackSessionId id) {
        Entry entry = ENTRIES.get(id);
        if (entry == null || !entry.beginOperation()) return null;
        try {
            PlaybackVideoFrame frame = entry.session.latestVideoFrame();
            return entry.terminal.get() ? null : frame;
        } catch (RuntimeException | LinkageError failure) { entry.fail(failure, null); return null; }
        finally { entry.endOperation(); }
    }

    /** Safe fallback clock read. -1 means unavailable; provider faults share the session's one-shot failure. */
    public static long presentationTimeUs(PlaybackSessionId id) {
        Entry entry = ENTRIES.get(id);
        if (entry == null) return -1L;
        if (!entry.beginOperation()) return entry.lastPresentationTimeUs.get();
        try {
            long pts = entry.session.presentationTimeUs();
            if (entry.terminal.get()) return entry.lastPresentationTimeUs.get();
            if (pts >= 0L) entry.lastPresentationTimeUs.set(pts);
            return pts;
        } catch (RuntimeException | LinkageError failure) { entry.fail(failure, null); return entry.lastPresentationTimeUs.get(); }
        finally { entry.endOperation(); }
    }

    /** Compatible manager callsite; cached audible PTS remains readable after the one-shot fault terminalizes. */
    public static long presentationTimeUs(PlaybackSessionId id, Consumer<Throwable> failureSink) {
        Entry entry = ENTRIES.get(id);
        if (entry == null) return -1L;
        if (!entry.beginOperation()) return entry.lastPresentationTimeUs.get();
        try {
            long pts = entry.session.presentationTimeUs();
            if (entry.terminal.get()) return entry.lastPresentationTimeUs.get();
            if (pts >= 0L) entry.lastPresentationTimeUs.set(pts);
            return pts;
        } catch (RuntimeException | LinkageError failure) {
            entry.fail(failure, null);
            return entry.lastPresentationTimeUs.get();
        } finally { entry.endOperation(); }
    }

    /** Immediately seals PCM/frame/cursor access, then asynchronously disposes once. Completion means close returned. */
    public static CompletableFuture<Void> close(PlaybackSessionId id) {
        Entry entry = ENTRIES.get(id);
        if (entry == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> completion = entry.quiesce();
        completion.whenComplete((ignored, failure) -> {
            // Keep a failed terminal entry and its bounded slot as a tombstone. Removing it here
            // would make a later close(id) return an already-successful empty future, hiding the
            // first failed acknowledgement from a stop/reload path that did not capture it yet.
            // Entry.quiesce() is single-flight, so every later caller observes this same future.
            if (failure == null && ENTRIES.remove(id, entry)) entry.releaseSessionSlot();
        });
        return completion;
    }
    public static void closeAll() {
        ENTRIES.values().forEach(entry -> close(entry.id));
    }

    private static CompletableFuture<Void> closeUnregistered(PlaybackMediaSession session) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            CLOSER.execute(() -> {
                try { session.close(); result.complete(null); }
                catch (Exception | LinkageError failure) {
                    CompletionStage<Void> acknowledgement = failure instanceof CleanupIncompleteException cleanup
                            ? cleanup.cleanupAcknowledged() : session.cleanupAcknowledged();
                    acknowledgement.whenComplete((ignored, cleanupFailure) -> {
                        if (cleanupFailure == null) result.complete(null);
                        else result.completeExceptionally(cleanupFailure);
                    });
                    LOGGER.log(System.Logger.Level.WARNING, "Unregistered provider session close failed", failure);
                }
            });
        } catch (RejectedExecutionException rejected) {
            CLOSE_OVERFLOWED.set(true);
            result.completeExceptionally(rejected);
            if (CLOSE_OVERFLOW_LOGGED.compareAndSet(false, true))
                LOGGER.log(System.Logger.Level.ERROR, "Bounded AV close queue is full; provider resource was not disposed and fallback is unsafe");
        }
        return result;
    }

    private static void awaitUnregisteredCleanup(PlaybackMediaSession session) throws CleanupIncompleteException {
        awaitDisposal(closeUnregistered(session));
    }

    private static void awaitDisposal(CompletableFuture<Void> disposed) throws CleanupIncompleteException {
        try { disposed.get(10, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new CleanupIncompleteException(failure, disposed); }
    }

    private static final class Entry {
        final PlaybackSessionId id;
        final PlaybackMediaSession session;
        final IAudioSource audio;
        final Consumer<Throwable> failureSink;
        final int sampleRate, channels, bits;
        final boolean bigEndian;
        final AtomicBoolean terminal = new AtomicBoolean();
        final Object operationLock = new Object();
        int operationsInFlight;
        final AtomicBoolean disposeSubmitted = new AtomicBoolean();
        final AtomicBoolean failureSubmitted = new AtomicBoolean();
        final AtomicBoolean playbackFaultDelivered = new AtomicBoolean();
        final AtomicBoolean failureCallbackDropped = new AtomicBoolean();
        final AtomicBoolean sessionSlotReleased;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<PlaybackFault> playbackFault = new AtomicReference<>();
        final AtomicReference<Consumer<PlaybackFault>> playbackFaultSink = new AtomicReference<>();
        final AtomicLong lastPresentationTimeUs = new AtomicLong(-1L);
        final CompletableFuture<Void> disposed = new CompletableFuture<>();

        Entry(PlaybackSessionId id, PlaybackMediaSession session, IAudioSource audio, Consumer<Throwable> failureSink,
                int sampleRate, int channels, int bits, boolean bigEndian, AtomicBoolean sessionSlotReleased) {
            this.id=id; this.session=session; this.audio=audio; this.failureSink=failureSink;
            this.sampleRate=sampleRate; this.channels=channels; this.bits=bits; this.bigEndian=bigEndian;
            this.sessionSlotReleased=sessionSlotReleased;
        }

        void releaseSessionSlot() {
            if (sessionSlotReleased.compareAndSet(false, true)) PlaybackSessionRegistry.releaseSessionSlot();
        }

        CompletableFuture<Void> quiesce() {
            synchronized (operationLock) { terminal.set(true); }
            if (!disposeSubmitted.compareAndSet(false, true)) return disposed;
            try {
                CLOSER.execute(() -> {
                    try { awaitOperations(); session.close(); disposed.complete(null); }
                    catch (Exception | LinkageError closeFailure) {
                        CompletionStage<Void> acknowledgement = closeFailure instanceof CleanupIncompleteException cleanup
                                ? cleanup.cleanupAcknowledged() : session.cleanupAcknowledged();
                        acknowledgement.whenComplete((ignored, cleanupFailure) -> {
                            if (cleanupFailure == null) disposed.complete(null);
                            else disposed.completeExceptionally(cleanupFailure);
                        });
                        LOGGER.log(System.Logger.Level.ERROR, "Optional provider close failed; regular fallback remains suppressed", closeFailure);
                    }
                });
            } catch (RejectedExecutionException rejected) {
                CLOSE_OVERFLOWED.set(true);
                disposed.completeExceptionally(rejected);
                if (CLOSE_OVERFLOW_LOGGED.compareAndSet(false, true))
                    LOGGER.log(System.Logger.Level.ERROR, "Bounded AV close queue is full; regular fallback remains suppressed");
            }
            return disposed;
        }

        boolean beginOperation() {
            synchronized (operationLock) {
                if (terminal.get()) return false;
                operationsInFlight++;
                return true;
            }
        }
        void endOperation() {
            synchronized (operationLock) {
                operationsInFlight--;
                if (operationsInFlight == 0) operationLock.notifyAll();
            }
        }
        void awaitOperations() throws InterruptedException {
            synchronized (operationLock) {
                while (operationsInFlight != 0) operationLock.wait();
            }
        }

        void fail(Throwable cause, PlaybackFault fault) {
            if (!terminal.compareAndSet(false, true)) return;
            failure.compareAndSet(null, cause);
            if (fault != null) playbackFault.set(fault);
            if (!failureSubmitted.compareAndSet(false, true)) return;
            try {
                FAULTS.execute(() -> {
                    try {
                        if (failureSink != null) failureSink.accept(cause);
                        Consumer<PlaybackFault> listener = playbackFaultSink.get();
                        PlaybackFault saved = playbackFault.get();
                        if (listener != null && saved != null && playbackFaultDelivered.compareAndSet(false, true)) listener.accept(saved);
                    } catch (RuntimeException | LinkageError callbackFailure) {
                        LOGGER.log(System.Logger.Level.WARNING, "Optional provider failure callback failed", callbackFailure);
                    }
                });
            } catch (RejectedExecutionException rejected) {
                failureCallbackDropped.set(true);
                if (FAULT_OVERFLOW_LOGGED.compareAndSet(false, true))
                    LOGGER.log(System.Logger.Level.ERROR, "Bounded AV callback queue is full; sessions terminalize but some fallback callbacks are dropped");
            } finally {
                CompletableFuture<Void> disposal = quiesce();
                if (failureCallbackDropped.get()) disposal.whenComplete((ignored, closeFailure) -> ENTRIES.remove(id, this));
            }
        }

        void registerPlaybackFaultSink(Consumer<PlaybackFault> sink) {
            playbackFaultSink.set(sink);
            PlaybackFault saved = playbackFault.get();
            if (saved != null && sink != null && playbackFaultDelivered.compareAndSet(false, true)) {
                try { FAULTS.execute(() -> sink.accept(saved)); }
                catch (RejectedExecutionException rejected) {
                    if (FAULT_OVERFLOW_LOGGED.compareAndSet(false, true))
                        LOGGER.log(System.Logger.Level.ERROR, "Bounded AV callback queue is full; a playback fault listener was dropped");
                }
            }
        }
    }

    private static final class SessionAudioSource implements IAudioSource {
        private final Entry entry;
        SessionAudioSource(Entry entry) { this.entry=entry; }
        @Override public int sampleRate() { return entry.sampleRate; }
        @Override public int channels() { return entry.channels; }
        @Override public int bitsPerSample() { return entry.bits; }
        @Override public boolean bigEndian() { return entry.bigEndian; }
        @Override public int read(byte[] dst, int off, int len) {
            if (!entry.beginOperation()) return -1;
            try {
                int n = entry.audio.read(dst, off, len);
                if (entry.terminal.get()) return -1;
                return n;
            } catch (RuntimeException | LinkageError failure) { entry.fail(failure, null); return -1; }
            finally { entry.endOperation(); }
        }
        @Override public PlaybackFault playbackFault() {
            if (entry.terminal.get()) return entry.playbackFault.get();
            try {
                PlaybackFault fault = entry.audio.playbackFault();
                if (fault != null) entry.fail(new IllegalStateException("AV provider audio fault"), fault);
                return fault;
            } catch (RuntimeException | LinkageError failure) { entry.fail(failure, null); return null; }
        }
        @Override public void onPlaybackFault(Consumer<PlaybackFault> sink) {
            entry.registerPlaybackFaultSink(sink);
            try { entry.audio.onPlaybackFault(fault -> entry.fail(
                    new IllegalStateException("AV provider reported audio fault"), fault)); }
            catch (RuntimeException | LinkageError failure) { entry.fail(failure, null); }
        }
        @Override public void reportAudioOutputPosition(long playedFrames, long sampleRate, long sampledAtNanos) {
            if (!entry.beginOperation()) return;
            try {
                entry.session.reportAudioOutputPosition(playedFrames, sampleRate, sampledAtNanos);
                long pts = entry.session.presentationTimeUs();
                if (pts >= 0L) entry.lastPresentationTimeUs.set(pts);
            }
            catch (RuntimeException | LinkageError failure) { entry.fail(failure, null); }
            finally { entry.endOperation(); }
        }
        @Override public void close() {
            // Keep a failed entry addressable until the queued fallback callback can obtain its exact close ack.
            // If the bounded callback queue rejected the callback, fail() removes it after disposal instead.
            if (entry.failure.get() == null || entry.failureCallbackDropped.get()) PlaybackSessionRegistry.close(entry.id);
        }
    }
}
