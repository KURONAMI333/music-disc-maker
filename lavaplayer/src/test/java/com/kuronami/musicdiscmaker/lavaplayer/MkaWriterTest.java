package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;
import com.sedmelluq.discord.lavaplayer.natives.opus.OpusEncoder;

/**
 * {@link MkaWriter} が生成する音声専用 MKA を、pin版 LavaPlayer の Matroska reader を
 * 通した実際の境界 ({@code resolveTrustedFile} / {@code openTrustedFile}) で検証する。
 * packet は擬似バイトではなく、同梱 native の {@link OpusEncoder} で実 PCM を encode した
 * 本物を使う — reader が packet を Opus decoder まで実際に流せることを確認するため。
 */
class MkaWriterTest {

    /** 20ms frame (48kHz mono で 960 samples)。 */
    private static final int FRAME_SAMPLES = 960;
    private static final long FRAME_NS = 20_000_000L;

    @TempDir
    Path tempDir;

    @Test
    void writerOutputIsAcceptedWithTheIntendedDurationAndDecodes() throws Exception {
        final int frames = 150; // 3.0 秒
        final Path mka = tempDir.resolve("writer-out");
        MkaWriter.write(mka, encodeTone(frames, 440.0));

        // writer の出力自体が映像 track を持たないこと (生成物と外部入力を区別する検査)。
        assertTrue(MatroskaTrackScanner.isAudioOnly(mka));

        final MusicLoaderImpl loader = new MusicLoaderImpl();
        final TrackInfo info = loader.resolveTrustedFile(mka);
        assertEquals(frames * 20L, info.durationMs(), info.toString());

        final byte[] pcm = decodeAll(loader.openTrustedFile(mka, 0L));
        // mono 48kHz 16bit = 96 bytes/ms → 3.0s ≈ 288,000。codec delay で前後するので幅を取る。
        assertTrue(pcm.length > 200_000, "MKA から十分な PCM が出ていない: " + pcm.length);
        assertTrue(pcm.length < 360_000, "想定より大幅に多い PCM: " + pcm.length);
        assertTrue(hasAudibleSample(pcm), "復号 PCM が全て無音");
    }

    @Test
    void writtenMkaDeliversFramesFromArbitrarySeekOffsets() throws Exception {
        final Path mka = tempDir.resolve("writer-seek");
        MkaWriter.write(mka, encodeTone(200, 440.0)); // 4.0 秒

        final MusicLoaderImpl loader = new MusicLoaderImpl();
        final byte[] whole = decodeAll(loader.openTrustedFile(mka, 0L));
        final byte[] latterHalf = decodeAll(loader.openTrustedFile(mka, 2_000L));
        final byte[] tail = decodeAll(loader.openTrustedFile(mka, 3_500L));

        assertTrue(latterHalf.length > 100_000, "中盤への seek で PCM が出ていない: "
                + latterHalf.length);
        assertTrue(latterHalf.length < whole.length * 0.7,
                "seek 位置が反映されず先頭から復号している: " + latterHalf.length + "/" + whole.length);
        assertTrue(tail.length > 10_000 && tail.length < whole.length * 0.4,
                "末尾手前への seek が先頭や曲中へ戻っている: " + tail.length + "/" + whole.length);

        // Cues を書いていなくても、pin reader の fallback 走査で末尾以降への seek が収束すること。
        final byte[] pastEnd = decodeAll(loader.openTrustedFile(mka, Long.MAX_VALUE));
        assertTrue(pastEnd.length < 8_000, "極端な seek が曲中へ戻っている: " + pastEnd.length);
    }

    @Test
    void writerRejectsAnEmptyPacketList() {
        assertThrows(IllegalArgumentException.class,
                () -> MkaWriter.document(List.of()));
    }

    @Test
    void writerRejectsMalformedPackets() {
        assertThrows(IllegalArgumentException.class,
                () -> new MkaWriter.OpusPacket(new byte[0], FRAME_NS));
        assertThrows(IllegalArgumentException.class,
                () -> new MkaWriter.OpusPacket(new byte[] {1}, 0L));
    }

    /**
     * {@link OpusEncoder} (OPUS_APPLICATION_AUDIO) で 440Hz の正弦波を 20ms ずつ
     * encode し、正確な尺つきの packet 列にする。
     */
    static List<MkaWriter.OpusPacket> encodeTone(int frames, double frequency) {
        final OpusEncoder encoder = new OpusEncoder(48_000, 1, 64_000);
        try {
            final List<MkaWriter.OpusPacket> packets = new ArrayList<>(frames);
            final ShortBuffer pcm = ByteBuffer.allocateDirect(FRAME_SAMPLES * 2)
                    .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            final ByteBuffer encoded = ByteBuffer.allocateDirect(4_000);
            for (int f = 0; f < frames; f++) {
                for (int i = 0; i < FRAME_SAMPLES; i++) {
                    final double t = (f * (double) FRAME_SAMPLES + i) / 48_000.0;
                    pcm.put(i, (short) (8_000.0 * Math.sin(2.0 * Math.PI * frequency * t)));
                }
                final int n = encoder.encode(pcm, FRAME_SAMPLES, encoded);
                if (n <= 0) {
                    throw new IllegalStateException("OpusEncoder returned " + n);
                }
                final byte[] packet = new byte[n];
                encoded.get(packet);
                packets.add(new MkaWriter.OpusPacket(packet, FRAME_NS));
            }
            return packets;
        } finally {
            encoder.close();
        }
    }

    /** pull 型の {@link IAudioSource} を終端まで読み切る (8 秒上限)。 */
    private static byte[] decodeAll(IAudioSource source) throws Exception {
        assertEquals(48_000, source.sampleRate());
        assertEquals(1, source.channels());
        assertEquals(16, source.bitsPerSample());
        assertFalse(source.bigEndian());

        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8_192];
        final long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        try (source) {
            while (System.nanoTime() < deadline) {
                final int read = source.read(buffer, 0, buffer.length);
                if (read < 0) {
                    return pcm.toByteArray();
                }
                if (read == 0) {
                    Thread.sleep(2L);
                } else {
                    pcm.write(buffer, 0, read);
                }
            }
        }
        throw new AssertionError("PCM 終端まで 8 秒以内に到達しなかった");
    }

    private static boolean hasAudibleSample(byte[] pcm) {
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            if (pcm[i] != 0 || pcm[i + 1] != 0) {
                return true;
            }
        }
        return false;
    }
}
