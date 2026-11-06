package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

/**
 * MKV 内 FLAC の直 decode 試験。参照 PCM は ffmpeg の s16le
 * ({@code ffmpeg -i <fixture> -f s16le -ac 2 -ar <native rate>}) で、raw は commit せず SHA-256 を持つ。
 * FLAC は可逆なので byte 単位で一致しないと frame の読み方がずれている。
 */
class MatroskaFlacPcmDecoderTest {

    /** 2 秒 / 44.1kHz / 16bit / stereo。 */
    private static final String REF_DEMO16_SHA256 =
            "d38c229223a91b62cbcfe8f0dfe61c5a46e02bf61db6b77b7f5e3c5f34a27c22";
    /** 2 秒 / 48kHz / 24bit を 16bit へ落としたもの。 */
    private static final String REF_DEMO24_SHA256 =
            "5f9770b5865b5b9ef792b01b7004707621caac47b243c8d7058e83b840938b07";

    @Test
    void decodesSixteenBitExactlyLikeFfmpeg() throws Exception {
        final byte[] pcm = decodeNative("demo16.mkv");

        assertEquals(352_800, pcm.length);
        assertEquals(REF_DEMO16_SHA256, sha256(pcm));
    }

    @Test
    void decodesTwentyFourBitToTheSameSixteenBitSamples() throws Exception {
        final byte[] pcm = decodeNative("demo24.mkv");

        assertEquals(384_000, pcm.length);
        assertEquals(REF_DEMO24_SHA256, sha256(pcm));
    }

    /** 映像 track の block は流れず、FLAC だけが出る。 */
    @Test
    void ignoresTheVideoTrack() throws Exception {
        final byte[] pcm = decodeNative("demo16-video.mkv");

        assertEquals(REF_DEMO16_SHA256, sha256(pcm));
    }

    @Test
    void reportsTheFormatAndTheStreamInfoDuration() throws Exception {
        final MatroskaFlacPcmDecoder.Info sixteen = MatroskaFlacPcmDecoder.probe(fixture("demo16.mkv"));
        assertNotNull(sixteen);
        assertEquals(44_100, sixteen.stream().sampleRate);
        assertEquals(2, sixteen.stream().channelCount);
        assertEquals(2_000L, sixteen.durationMs());

        final MatroskaFlacPcmDecoder.Info twentyFour = MatroskaFlacPcmDecoder.probe(fixture("demo24.mkv"));
        assertNotNull(twentyFour);
        assertEquals(48_000, twentyFour.stream().sampleRate);
        assertEquals(2_000L, twentyFour.durationMs());
    }

    /** 尺のフォールバックに使う {@code getDuration()} の単位 (ms) を固定する。 */
    @Test
    void matroskaDurationIsInMilliseconds() throws Exception {
        try (var stream = new com.sedmelluq.discord.lavaplayer.source.local.LocalSeekableInputStream(
                fixture("demo16.mkv").toFile())) {
            final var matroska = new com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaStreamingFile(stream);
            matroska.readFile();
            assertTrue(Math.abs(matroska.getDuration() - 2_000d) < 100d,
                    "getDuration=" + matroska.getDuration());
        }
    }

    /** codecPrivate の 2 つの形 (fLaC + header + 34B / 34B 直置き) の両方を受ける。 */
    @Test
    void parsesBothStreamInfoLayouts() throws Exception {
        final byte[] info = streamInfo(44_100, 2, 16, 88_200L);
        final byte[] withMagic = new byte[4 + 4 + info.length];
        withMagic[0] = 'f';
        withMagic[1] = 'L';
        withMagic[2] = 'a';
        withMagic[3] = 'C';
        withMagic[4] = (byte) 0x80; // last block, STREAMINFO
        withMagic[7] = (byte) info.length;
        System.arraycopy(info, 0, withMagic, 8, info.length);

        for (final byte[] layout : new byte[][] {withMagic, info}) {
            final var parsed = MatroskaFlacPcmDecoder.parseStreamInfo(layout);
            assertEquals(44_100, parsed.sampleRate);
            assertEquals(2, parsed.channelCount);
            assertEquals(16, parsed.bitsPerSample);
            assertEquals(88_200L, parsed.sampleCount);
            assertEquals(4_096, parsed.maximumBlockSize);
        }
    }

    @Test
    void rejectsMissingOrShortStreamInfo() {
        assertThrows(IOException.class, () -> MatroskaFlacPcmDecoder.parseStreamInfo(null));
        assertThrows(IOException.class, () -> MatroskaFlacPcmDecoder.parseStreamInfo(new byte[10]));
        assertThrows(IOException.class,
                () -> MatroskaFlacPcmDecoder.parseStreamInfo(new byte[] {'f', 'L', 'a', 'C', 0, 0, 0, 34}));
    }

    /** 48kHz mono への直しを通した長さと、途中 close で補助スレッドも止まること。 */
    @Test
    void feedsThePipelineAndStopsOnClose() throws Exception {
        final DirectPcmInput input = input("demo16.mkv");
        final IAudioSource whole = input.open();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            final byte[] buffer = new byte[8_192];
            final long deadline = System.nanoTime() + 60_000_000_000L;
            while (true) {
                final int read = whole.read(buffer, 0, buffer.length);
                if (read < 0) {
                    break;
                }
                if (read > 0) {
                    out.write(buffer, 0, read);
                } else {
                    assertTrue(System.nanoTime() < deadline, "decode が終わらない");
                    Thread.sleep(1L);
                }
            }
            assertNull(whole.playbackFault());
        } finally {
            whole.close();
        }
        assertTrue(DirectPcmInputTest.isResampledLength(out.size()), "長さ " + out.size());

        final IAudioSource early = input.open();
        final byte[] buffer = new byte[512];
        int got = 0;
        while (got < 16_000) {
            final int read = early.read(buffer, 0, buffer.length);
            if (read > 0) {
                got += read;
            } else {
                Thread.sleep(1L);
            }
        }
        early.close();
        final long deadline = System.nanoTime() + 1_000_000_000L;
        while (anyThreadAlive("mdm-local-") && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertFalse(anyThreadAlive("mdm-local-"), "producer と補助スレッドが終わる");
    }

    private static boolean anyThreadAlive(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> thread.isAlive() && thread.getName().startsWith(prefix));
    }

    private static DirectPcmInput input(String name) throws Exception {
        final Path file = fixture(name);
        final MatroskaFlacPcmDecoder.Info info = MatroskaFlacPcmDecoder.probe(file);
        return new DirectPcmInput(new MatroskaFlacPcmDecoder(file, info), info.durationMs(),
                PcmInput.Origin.MATROSKA_FLAC, name);
    }

    private static byte[] decodeNative(String name) throws Exception {
        final Path file = fixture(name);
        final MatroskaFlacPcmDecoder.Info info = MatroskaFlacPcmDecoder.probe(file);
        assertNotNull(info);
        final int channels = info.stream().channelCount;
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        new MatroskaFlacPcmDecoder(file, info).decode((samples, frames) -> {
            for (int i = 0; i < frames * channels; i++) {
                out.write(samples[i] & 0xFF);
                out.write((samples[i] >> 8) & 0xFF);
            }
        });
        return out.toByteArray();
    }

    /** 34 byte の FLAC STREAMINFO (block 4096、md5 は 0)。 */
    private static byte[] streamInfo(int rate, int channels, int bits, long samples) {
        final byte[] b = new byte[34];
        b[0] = 0x10;
        b[1] = 0x00; // min block 4096
        b[2] = 0x10;
        b[3] = 0x00; // max block 4096
        final long packed = ((long) rate << 44) | ((long) (channels - 1) << 41)
                | ((long) (bits - 1) << 36) | samples;
        for (int i = 0; i < 8; i++) {
            b[10 + i] = (byte) (packed >>> (56 - 8 * i));
        }
        return b;
    }

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                MatroskaFlacPcmDecoderTest.class.getResource("/local-import/" + name),
                "missing fixture " + name).toURI());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
