package com.kuronami.musicdiscmaker.client.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
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
    /** sound engine thread 専用。OpenAL が現在再生している絶対 PCM byte 位置。 */
    private LongSupplier nativePlaybackCursor;
    private final CompletableFuture<Void> masterAttached = new CompletableFuture<>();
    private final byte[] ring;
    private boolean closed;
    private boolean ended;
    private PlaybackFault fault;
    /** Absolute byte position immediately after the last decoded PCM byte. */
    private long producedCursor;
    /** Oldest absolute cursor still available from {@link #ring}. */
    private long floorCursor;
    private boolean pcmStarted;

    FanoutAudioSource(IAudioSource upstream) {
        this.upstream = Objects.requireNonNull(upstream, "upstream");
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

    /** 子の位置は worker で推定せず、native channel に接続する時に決める。 */
    synchronized Branch openInitialBranch() { return openBranchAt(0L, false); }

    synchronized Branch openLateBranch() { return openBranchAt(0L, false); }

    /** PCM 供給開始の印。出音位置ではなく、共有 underrun padding の開始条件だけに使う。 */
    synchronized void markPcmStarted() { pcmStarted = true; }

    /**
     * Golden の {@link LavaPlayerAudioStream} が出力 queue を無音で埋めた分を共有時間軸へ足す。
     *
     * <p>OpenAL cursor はこの無音を含む。ここで ring と master cursor も同じ量だけ
     * 進めないと、途中参加 Speaker の native cursor が {@link #producedCursor} を超える。
     * 無音は decoder PCM ではないので、この処理は実 PCM 開始の印を変えない。
     */
    private synchronized void shareOutputPadding(Branch branch, int count) {
        if (count <= 0 || closed || branch.closed || !branch.master) return;
        // LavaPlayerAudioStream が pad するのは branch.read が現在の先端で短く返った時だけ。
        if (branch.cursor != producedCursor) return;
        appendSilence(count);
        branch.cursor += count;
    }

    private Branch openBranchAt(long cursor, boolean master) {
        // Ended only means no more decoder bytes. The retained tail must remain playable for a speaker linked
        // during the Golden voice's native OpenAL queue, until every branch has actually released it.
        if (closed) throw new IllegalStateException("Cannot open a branch after the shared source closed");
        final Branch branch = new Branch(cursor, master);
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
        branch.nativePending = false;
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
                if (filled == 0 && pcmStarted) {
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
            if (!branch.master && !branch.closed && !branch.nativePending && branch.cursor < floorCursor) branch.desynchronise();
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
        if (branch.master) {
            nativePlaybackCursor = null;
            masterAttached.complete(null);
        }
        if (branches.isEmpty() && !closed) { closed = true; upstream.close(); }
    }

    final class Branch implements IAudioSource {
        private long cursor;
        private final boolean master;
        private boolean closed;
        private boolean desynchronised;
        private boolean nativePending;
        private Consumer<PlaybackFault> faultSink;
        private Runnable desyncSink;

        private Branch(long cursor, boolean master) {
            this.cursor = cursor; this.master = master; this.nativePending = !master;
        }
        boolean isMaster() { return master; }
        CompletableFuture<Void> masterAttached() { return masterAttached; }
        void shareOutputPadding(int count) { FanoutAudioSource.this.shareOutputPadding(this, count); }

        /** Sound engine thread の attachBufferStream でのみ呼ぶ。OpenAL を worker から読まない。 */
        long attachNativePlayback(LongSupplier ownCursor) {
            synchronized (FanoutAudioSource.this) {
                if (master) {
                    nativePlaybackCursor = ownCursor;
                    masterAttached.complete(null);
                    return 0L;
                } else {
                    nativePending = false;
                    final long audible = nativePlaybackCursor == null ? -1L : nativePlaybackCursor.getAsLong();
                    if (audible < floorCursor || audible > producedCursor) {
                        desynchronise();
                        return -1L;
                    }
                    cursor = audible - audible % bytesPerFrame;
                }
                return cursor;
            }
        }

        /** attach 後の buffer 準備中にも master は進む。play 直前に native queue 内を補正する。 */
        long playbackOffsetBytes(long queueStart, long queuedBytes) {
            synchronized (FanoutAudioSource.this) {
                if (master) return 0L;
                final long audible = nativePlaybackCursor == null ? -1L : nativePlaybackCursor.getAsLong();
                final long offset = audible - queueStart;
                if (audible < 0L || offset < 0L || offset >= queuedBytes) {
                    desynchronise();
                    return -1L;
                }
                return offset - offset % bytesPerFrame;
            }
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
