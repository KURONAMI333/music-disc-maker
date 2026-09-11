package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import org.junit.jupiter.api.Test;

class FanoutAudioSourceTest {

    @Test
    void everyBranchReceivesTheSamePcmEvenWhenOneVoiceRunsAhead() {
        final BytesSource upstream = new BytesSource(new byte[] {1, 2, 3, 4, 5, 6});
        final FanoutAudioSource fanout = new FanoutAudioSource(upstream);
        final IAudioSource source = fanout.openMasterBranch();
        final IAudioSource speaker = fanout.openInitialBranch();

        assertArrayEquals(new byte[] {1, 2, 3, 4}, readExactly(source, 4));
        assertArrayEquals(new byte[] {1, 2}, readExactly(speaker, 2));
        assertArrayEquals(new byte[] {3, 4, 5, 6}, readExactly(speaker, 4));
        assertArrayEquals(new byte[] {5, 6}, readExactly(source, 2));
        assertEquals(-1, source.read(new byte[1], 0, 1));
        assertEquals(-1, speaker.read(new byte[1], 0, 1));
    }

    @Test
    void closingOneSpeakerDoesNotCloseTheSharedDecoderUntilTheLastVoiceStops() {
        final BytesSource upstream = new BytesSource(new byte[] {9, 8});
        final FanoutAudioSource fanout = new FanoutAudioSource(upstream);
        final IAudioSource source = fanout.openMasterBranch();
        final IAudioSource speaker = fanout.openInitialBranch();
        speaker.close();

        assertTrue(!upstream.closed);
        assertArrayEquals(new byte[] {9, 8}, readExactly(source, 2));
        source.close();
        assertTrue(upstream.closed);
    }

    @Test
    void ordinaryBurstPrefetchKeepsTheWholeStreamForTheLaterVoice() {
        final byte[] pcm = new byte[480_000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
        final FanoutAudioSource fanout = new FanoutAudioSource(new BytesSource(pcm));
        final IAudioSource master = fanout.openMasterBranch();
        final IAudioSource speaker = fanout.openInitialBranch();

        // SoundEngine is allowed to prebuffer the master before asking the next OpenAL voice.
        assertArrayEquals(java.util.Arrays.copyOfRange(pcm, 0, 384_000), readExactly(master, 384_000));
        assertArrayEquals(pcm, readExactly(speaker, pcm.length));
    }

    @Test
    void speakerMayRefillFirstWithoutChangingTheGoldenTimeline() {
        final byte[] pcm = new byte[] {1, 2, 3, 4, 5, 6};
        final FanoutAudioSource fanout = new FanoutAudioSource(new BytesSource(pcm));
        final IAudioSource master = fanout.openMasterBranch();
        final IAudioSource speaker = fanout.openInitialBranch();

        assertArrayEquals(pcm, readExactly(speaker, pcm.length));
        assertArrayEquals(pcm, readExactly(master, pcm.length));
    }

    @Test
    void cachedTailIsToppedUpBeforeReturningToTheSilencePaddingStream() {
        final byte[] pcm = new byte[9_000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
        final FanoutAudioSource fanout = new FanoutAudioSource(new BytesSource(pcm));
        final IAudioSource master = fanout.openMasterBranch();
        final IAudioSource speaker = fanout.openInitialBranch();

        // Leave a 1,000-byte cache tail, then ask the other stream for one full LavaPlayer scratch buffer.
        assertArrayEquals(java.util.Arrays.copyOfRange(pcm, 0, 1_000), readExactly(master, 1_000));
        assertArrayEquals(java.util.Arrays.copyOfRange(pcm, 0, 8_192), readExactly(speaker, 8_192));
    }

    @Test
    void postStartUnderrunIsOneSharedTimelineGapRatherThanPerVoiceSilence() {
        final FanoutAudioSource fanout = new FanoutAudioSource(new OneChunkThenEmptySource());
        final IAudioSource master = fanout.openMasterBranch();
        final IAudioSource speaker = fanout.openInitialBranch();
        fanout.markPlaybackStarted(0L);

        assertArrayEquals(new byte[] {1, 2, 3, 4, 0, 0, 0, 0}, readExactly(master, 8));
        assertArrayEquals(new byte[] {1, 2, 3, 4, 0, 0, 0, 0}, readExactly(speaker, 8));
    }

    @Test
    void shortPositiveUpstreamReadsAreJoinedBeforeTheyReachLavaPlayer() {
        final byte[] pcm = new byte[8_192];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
        final FanoutAudioSource fanout = new FanoutAudioSource(new ScriptedSource(pcm, 1_000, 1_000, 1_000, 1_000,
                1_000, 1_000, 1_000, 1_000, 192));
        final IAudioSource master = fanout.openMasterBranch();
        final IAudioSource speaker = fanout.openInitialBranch();
        assertArrayEquals(pcm, readExactly(master, pcm.length));
        assertArrayEquals(pcm, readExactly(speaker, pcm.length));
    }

    @Test
    void preStartEmptyReadDoesNotInventTimelineSilence() {
        final FanoutAudioSource fanout = new FanoutAudioSource(new ScriptedSource(new byte[] {7, 8}, 0, 2));
        final IAudioSource master = fanout.openMasterBranch();
        assertEquals(0, master.read(new byte[2], 0, 2));
        assertArrayEquals(new byte[] {7, 8}, readExactly(master, 2));
    }

    @Test
    void sharedUnderrunKeepsTheFollowingRealPcmAligned() {
        final FanoutAudioSource fanout = new FanoutAudioSource(
                new ScriptedSource(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, 4, 0, 4));
        final IAudioSource master = fanout.openMasterBranch();
        final IAudioSource speaker = fanout.openInitialBranch();
        fanout.markPlaybackStarted(0L);
        assertArrayEquals(new byte[] {1, 2, 3, 4, 0, 0, 0, 0}, readExactly(master, 8));
        assertArrayEquals(new byte[] {1, 2, 3, 4, 0, 0, 0, 0}, readExactly(speaker, 8));
        assertArrayEquals(new byte[] {5, 6, 7, 8}, readExactly(master, 4));
        assertArrayEquals(new byte[] {5, 6, 7, 8}, readExactly(speaker, 4));
    }

    @Test
    void lateSpeakerUsesTheAudibleClockInsteadOfRefillTiming() {
        final byte[] pcm = new byte[10];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
        final long[] now = {0L};
        final FanoutAudioSource fanout = new FanoutAudioSource(new BytesSource(pcm, 1, 1, 8), () -> now[0]);
        final IAudioSource master = fanout.openMasterBranch();

        assertArrayEquals(pcm, readExactly(master, pcm.length));
        fanout.markPlaybackStarted(0L);
        final IAudioSource speaker = fanout.openLateBranch();
        // The async prebuffer worker can begin after the placement action; its first pull must use that later time.
        now[0] = 6_000L;
        assertArrayEquals(java.util.Arrays.copyOfRange(pcm, 6, 10), readExactly(speaker, 4));
    }

    @Test
    void branchOutsideTheRetainedRingIsMarkedForRecreationWithoutEvictingMaster() {
        final byte[] pcm = new byte[9_000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
        final FanoutAudioSource fanout = new FanoutAudioSource(new BytesSource(pcm, 1, 1, 8));
        final IAudioSource master = fanout.openMasterBranch();
        final FanoutAudioSource.Branch speaker = fanout.openInitialBranch();
        final boolean[] notified = {false};
        speaker.onDesynchronised(() -> notified[0] = true);

        assertArrayEquals(pcm, readExactly(master, pcm.length));
        assertTrue(notified[0]);
        assertEquals(-1, speaker.read(new byte[1], 0, 1));
        assertTrue(speaker.desynchronised());
    }

    @Test
    void lateSpeakerCanDrainRetainedTailAfterDecoderEof() {
        final long[] now = {0L};
        final FanoutAudioSource fanout = new FanoutAudioSource(
                new BytesSource(new byte[] {1, 2, 3, 4, 5, 6}, 1, 1, 8), () -> now[0]);
        final IAudioSource master = fanout.openMasterBranch();
        readExactly(master, 6);
        assertEquals(-1, master.read(new byte[1], 0, 1));
        fanout.markPlaybackStarted(0L);
        now[0] = 4_000L;
        final IAudioSource speaker = fanout.openLateBranch();
        assertArrayEquals(new byte[] {5, 6}, readExactly(speaker, 2));
        assertEquals(-1, speaker.read(new byte[1], 0, 1));
    }

    @Test
    void RunawaySpeakerCannotEvictUnreadMasterPcm() {
        final byte[] pcm = new byte[9_000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) i;
        final FanoutAudioSource fanout = new FanoutAudioSource(new BytesSource(pcm, 1, 1, 8));
        final IAudioSource master = fanout.openMasterBranch();
        final FanoutAudioSource.Branch speaker = fanout.openInitialBranch();
        readExactly(speaker, 8_192);
        assertEquals(-1, speaker.read(new byte[1], 0, 1));
        assertTrue(speaker.desynchronised());
        assertArrayEquals(pcm, readExactly(master, pcm.length));
    }

    private static byte[] readExactly(IAudioSource source, int length) {
        final byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            final int count = source.read(result, offset, length - offset);
            if (count < 0) throw new AssertionError("source ended early");
            offset += count;
        }
        return result;
    }

    private static final class BytesSource implements IAudioSource {
        private final byte[] bytes;
        private final int sampleRate;
        private final int channels;
        private final int bits;
        private int position;
        boolean closed;
        BytesSource(byte[] bytes) { this(bytes, 48_000, 1, 16); }
        BytesSource(byte[] bytes, int sampleRate, int channels, int bits) {
            this.bytes = bytes; this.sampleRate = sampleRate; this.channels = channels; this.bits = bits;
        }
        @Override public int sampleRate() { return sampleRate; }
        @Override public int channels() { return channels; }
        @Override public int bitsPerSample() { return bits; }
        @Override public boolean bigEndian() { return false; }
        @Override public int read(byte[] dst, int off, int len) {
            if (position == bytes.length) return -1;
            final int count = Math.min(len, bytes.length - position);
            System.arraycopy(bytes, position, dst, off, count);
            position += count;
            return count;
        }
        @Override public void close() { closed = true; }
    }

    private static final class OneChunkThenEmptySource implements IAudioSource {
        private boolean first = true;
        @Override public int sampleRate() { return 48_000; }
        @Override public int channels() { return 1; }
        @Override public int bitsPerSample() { return 16; }
        @Override public boolean bigEndian() { return false; }
        @Override public int read(byte[] dst, int off, int len) {
            if (!first) return 0;
            first = false;
            System.arraycopy(new byte[] {1, 2, 3, 4}, 0, dst, off, 4);
            return 4;
        }
        @Override public void close() {}
    }

    private static final class ScriptedSource implements IAudioSource {
        private final byte[] bytes; private final int[] results; private int cursor; private int step;
        ScriptedSource(byte[] bytes, int... results) { this.bytes = bytes; this.results = results; }
        @Override public int sampleRate() { return 48_000; }
        @Override public int channels() { return 1; }
        @Override public int bitsPerSample() { return 16; }
        @Override public boolean bigEndian() { return false; }
        @Override public int read(byte[] dst, int off, int len) {
            if (step >= results.length) return -1;
            final int scripted = results[step++];
            if (scripted <= 0) return scripted;
            final int count = Math.min(Math.min(scripted, len), bytes.length - cursor);
            System.arraycopy(bytes, cursor, dst, off, count); cursor += count; return count;
        }
        @Override public void close() {}
    }
}
