package com.kuronami.musicdiscmaker.client.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;

/** One decoder feeding positional voices on one shared PCM timeline. */
final class FanoutAudioSource {
    private static final int CHUNK_BYTES = 8192;
    private static final int STARTUP_SECONDS = 4;
    private static final int HISTORY_SECONDS = 2;
    private static final int MAX_RING_BYTES = 2_000_000;

    private final IAudioSource upstream;
    private final List<Branch> branches = new ArrayList<>();
    private final long bytesPerSecond;
    private final int bytesPerFrame;
    private final LongSupplier playbackClockMs;
    private final byte[] ring;
    private boolean closed;
    private boolean ended;
    private PlaybackFault fault;
    /** Absolute byte position immediately after the last decoded PCM byte. */
    private long producedCursor;
    /** Oldest absolute cursor still available from {@link #ring}. */
    private long floorCursor;
    private long playbackStartedAtMs = -1L;

    FanoutAudioSource(IAudioSource upstream) {
        this(upstream, System::currentTimeMillis);
    }

    FanoutAudioSource(IAudioSource upstream, LongSupplier playbackClockMs) {
        this.upstream = Objects.requireNonNull(upstream, "upstream");
        this.playbackClockMs = Objects.requireNonNull(playbackClockMs, "playbackClockMs");
        bytesPerSecond = Math.max(1L, (long) upstream.sampleRate() * upstream.channels()
                * Math.max(1, upstream.bitsPerSample() / 8));
        bytesPerFrame = Math.max(1, upstream.channels() * Math.max(1, upstream.bitsPerSample() / 8));
        ring = new byte[(int) Math.max(CHUNK_BYTES,
                Math.min(MAX_RING_BYTES, bytesPerSecond * (STARTUP_SECONDS + HISTORY_SECONDS)))];
        upstream.onPlaybackFault(this::reportFault);
    }

    /** Opens the Golden/master branch. Its cursor is never evicted by a faster speaker branch. */
    synchronized Branch openMasterBranch() {
        if (!branches.isEmpty()) throw new IllegalStateException("Fanout master must be opened first");
        return openBranchAt(0L, true);
    }

    /** Opens a branch present at the beginning of playback. */
    synchronized Branch openInitialBranch() { return openBranchAt(0L, false); }

    /** Opens a newly-linked speaker at the master's estimated audible position. */
    synchronized Branch openLateBranch() {
        // Placement only creates the voice. Its prebuffer worker may start later, so calculate the cursor at its
        // first PCM pull rather than here; otherwise that preparation delay becomes an audible late-speaker echo.
        return openBranchAt(0L, false, true);
    }

    private void alignLateBranch(Branch branch) {
        if (!branch.latePending) return;
        branch.latePending = false;
        if (playbackStartedAtMs < 0L) return;
        final long elapsedBytes = Math.max(0L, bytesPerSecond
                * Math.max(0L, playbackClockMs.getAsLong() - playbackStartedAtMs) / 1_000L);
        final long audibleCursor = elapsedBytes - elapsedBytes % bytesPerFrame;
        branch.cursor = Math.max(floorCursor, Math.min(producedCursor, audibleCursor));
    }

    /** Records when the Golden master first handed real PCM to SoundEngine (the audible-track epoch). */
    synchronized void markPlaybackStarted(long clockMs) {
        if (playbackStartedAtMs < 0L) playbackStartedAtMs = clockMs;
    }

    private Branch openBranchAt(long cursor, boolean master) { return openBranchAt(cursor, master, false); }

    private Branch openBranchAt(long cursor, boolean master, boolean latePending) {
        // Ended only means no more decoder bytes. The retained tail must remain playable for a speaker linked
        // during the Golden voice's native OpenAL queue, until every branch has actually released it.
        if (closed) throw new IllegalStateException("Cannot open a branch after the shared source closed");
        final Branch branch = new Branch(cursor, master, latePending);
        branches.add(branch);
        return branch;
    }

    private synchronized void reportFault(PlaybackFault next) {
        if (fault != null || next == null) return;
        fault = next;
        for (Branch branch : List.copyOf(branches)) branch.reportFault(next);
    }

    private synchronized int read(Branch branch, byte[] dst, int off, int len) {
        if (branch.closed || branch.desynchronised || len == 0) return branch.closed || branch.desynchronised ? -1 : 0;
        alignLateBranch(branch);
        int copied = 0;
        while (copied < len) {
            if (branch.cursor < floorCursor) { branch.desynchronise(); return copied > 0 ? copied : -1; }
            if (branch.cursor == producedCursor && !ended && !closed) {
                final int requested = Math.min(Math.max(1, len - copied), CHUNK_BYTES);
                // A speaker cannot run farther than retained jitter history ahead of the master. Letting it decode
                // farther would evict Golden PCM, so rebuild that speaker instead of damaging the master timeline.
                if (!branch.master && producedCursor + requested - masterCursor() > ring.length) {
                    branch.desynchronise();
                    return copied > 0 ? copied : -1;
                }
                final int filled = fill(requested);
                // A zero from upstream is a real temporary underrun. Only this case may reach LavaPlayer as a
                // short read before playback begins; cold prefill keeps its own 20ms retry behaviour. Once the
                // Golden master is audible, write missing PCM once into the shared timeline so every positional
                // voice advances over identical silence instead of inventing different local gaps.
                if (filled == 0 && playbackStartedAtMs >= 0L) {
                    appendSilence(requested);
                } else if (filled <= 0) {
                    break;
                }
                continue;
            }
            final long available = producedCursor - branch.cursor;
            if (available <= 0) break;
            final int count = (int) Math.min((long) (len - copied), available);
            copyFromRing(branch.cursor, dst, off + copied, count);
            branch.cursor += count;
            copied += count;
        }
        return copied > 0 ? copied : ended || closed || branch.desynchronised ? -1 : 0;
    }

    private int fill(int wanted) {
        final byte[] bytes = new byte[Math.min(Math.max(1, wanted), CHUNK_BYTES)];
        final int count = upstream.read(bytes, 0, bytes.length);
        if (count < 0) { ended = true; return -1; }
        if (count == 0) return 0;
        append(bytes, count);
        return count;
    }

    private void appendSilence(int count) {
        for (int i = 0; i < count; i++) ring[(int) ((producedCursor + i) % ring.length)] = 0;
        advanceProduced(count);
    }

    private void append(byte[] bytes, int count) {
        for (int i = 0; i < count; i++) ring[(int) ((producedCursor + i) % ring.length)] = bytes[i];
        advanceProduced(count);
    }

    private void advanceProduced(int count) {
        producedCursor += count;
        floorCursor = Math.max(0L, producedCursor - ring.length);
        for (Branch branch : List.copyOf(branches)) {
            if (!branch.master && !branch.closed && branch.cursor < floorCursor) branch.desynchronise();
        }
    }

    private long masterCursor() {
        for (Branch branch : branches) if (branch.master) return branch.cursor;
        return producedCursor;
    }

    private void copyFromRing(long cursor, byte[] dst, int off, int count) {
        final int source = (int) (cursor % ring.length);
        final int first = Math.min(count, ring.length - source);
        System.arraycopy(ring, source, dst, off, first);
        if (first < count) System.arraycopy(ring, 0, dst, off + first, count - first);
    }

    private synchronized void close(Branch branch) {
        if (branch.closed) return;
        branch.closed = true;
        branches.remove(branch);
        if (branches.isEmpty() && !closed) { closed = true; upstream.close(); }
    }

    final class Branch implements IAudioSource {
        private long cursor;
        private final boolean master;
        private boolean closed;
        private boolean desynchronised;
        private Consumer<PlaybackFault> faultSink;
        private Runnable desyncSink;

        private boolean latePending;
        private Branch(long cursor, boolean master, boolean latePending) {
            this.cursor = cursor; this.master = master; this.latePending = latePending;
        }
        @Override public int sampleRate() { return upstream.sampleRate(); }
        @Override public int channels() { return upstream.channels(); }
        @Override public int bitsPerSample() { return upstream.bitsPerSample(); }
        @Override public boolean bigEndian() { return upstream.bigEndian(); }
        @Override public int read(byte[] dst, int off, int len) { return FanoutAudioSource.this.read(this, dst, off, len); }
        @Override public PlaybackFault playbackFault() { synchronized (FanoutAudioSource.this) { return fault; } }
        @Override public void onPlaybackFault(Consumer<PlaybackFault> sink) {
            final PlaybackFault current;
            synchronized (FanoutAudioSource.this) { faultSink = sink; current = fault; }
            if (current != null && sink != null) sink.accept(current);
        }
        @Override public void close() { FanoutAudioSource.this.close(this); }
        boolean desynchronised() { return desynchronised; }
        void onDesynchronised(Runnable sink) { desyncSink = sink; }
        private void desynchronise() {
            if (desynchronised) return;
            desynchronised = true;
            if (desyncSink != null) desyncSink.run();
        }
        private void reportFault(PlaybackFault next) { if (faultSink != null) faultSink.accept(next); }
    }
}
