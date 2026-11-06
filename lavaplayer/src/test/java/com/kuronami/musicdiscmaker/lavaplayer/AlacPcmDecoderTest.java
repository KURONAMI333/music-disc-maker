package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/**
 * ALAC (M4A) の直 decode 試験。
 *
 * <p>fixture は 2 秒の 440Hz 正弦波で、参照 PCM は ffmpeg が返す native rate の s16le
 * ({@code ffmpeg -i <fixture> -f s16le -ac <ch> -ar <rate>})。raw は commit せず、
 * その SHA-256 をここに持つ。ALAC は可逆なので、byte 単位で一致しないと読み方
 * (16bit の並び・24bit の切り落とし) がどこかでずれている。
 */
class AlacPcmDecoderTest {

    /** ffmpeg の s16le と一致する参照 (2 秒 / 44.1kHz / 16bit / stereo)。 */
    private static final String REF_A16_SHA256 =
            "d38c229223a91b62cbcfe8f0dfe61c5a46e02bf61db6b77b7f5e3c5f34a27c22";
    /** 同上 (2 秒 / 48kHz / 24bit / stereo を 16bit へ落としたもの)。 */
    private static final String REF_A24_SHA256 =
            "5f9770b5865b5b9ef792b01b7004707621caac47b243c8d7058e83b840938b07";
    /** 同上 (2 秒 / 44.1kHz / 16bit / mono)。 */
    private static final String REF_MONO16_SHA256 =
            "79021609cd43f147ee2b2fa700acf03317a3940e1883766e25f55a23ba4256fe";

    @Test
    void decodesSixteenBitStereoExactlyLikeFfmpeg() throws Exception {
        final byte[] pcm = decodeNative("a16.m4a", 2);

        assertEquals(352_800, pcm.length, "2 秒 × 44.1kHz × stereo × 2 byte");
        assertEquals(REF_A16_SHA256, sha256(pcm), "ffmpeg の s16le と byte 単位で一致する");
    }

    @Test
    void decodesTwentyFourBitStereoToTheSameSixteenBitSamples() throws Exception {
        final byte[] pcm = decodeNative("a24.m4a", 2);

        assertEquals(384_000, pcm.length, "2 秒 × 48kHz × stereo × 2 byte");
        assertEquals(REF_A24_SHA256, sha256(pcm), "24bit の上位 16bit が ffmpeg と一致する");
    }

    @Test
    void decodesMonoExactlyLikeFfmpeg() throws Exception {
        final byte[] pcm = decodeNative("mono16.m4a", 1);

        assertEquals(176_400, pcm.length, "2 秒 × 44.1kHz × mono × 2 byte");
        assertEquals(REF_MONO16_SHA256, sha256(pcm), "mono の並びが ffmpeg と一致する");
    }

    /** probe が形式を返し、尺が frame 境界へ切り上げた概算になる (encoder のレート選択用)。 */
    @Test
    void reportsTheFormatAndTheRoundedUpDuration() throws Exception {
        final AlacPcmDecoder.Info sixteen = AlacPcmDecoder.probe(fixture("a16.m4a"));
        assertEquals(44_100, sixteen.sampleRate());
        assertEquals(2, sixteen.channels());
        assertEquals(16, sixteen.bitsPerSample());
        assertBetween(2_000L, 2_100L, sixteen.durationMs());

        final AlacPcmDecoder.Info twentyFour = AlacPcmDecoder.probe(fixture("a24.m4a"));
        assertEquals(48_000, twentyFour.sampleRate());
        assertEquals(2, twentyFour.channels());
        assertEquals(24, twentyFour.bitsPerSample());
        assertBetween(2_000L, 2_100L, twentyFour.durationMs());

        final AlacPcmDecoder.Info mono = AlacPcmDecoder.probe(fixture("mono16.m4a"));
        assertEquals(1, mono.channels());
        assertBetween(2_000L, 2_100L, mono.durationMs());
    }

    /**
     * AAC だけの MP4 は ALAC として開けない。ここで {@link IOException} を返すので、
     * 呼び出し側は通常経路の失敗理由をそのまま使える。
     */
    @Test
    void rejectsAnAacOnlyMp4() throws Exception {
        final Path aac = fixture("aac.m4a");

        final IOException failure = assertThrows(IOException.class, () -> AlacPcmDecoder.probe(aac));
        assertTrue(failure.getMessage() != null && !failure.getMessage().isBlank(),
                "短い失敗理由を持つ: " + failure);
    }

    /** 途中で切れた M4A は開けない (壊れた入力を音として通さない)。 */
    @Test
    void rejectsATruncatedFile() throws Exception {
        final Path truncated = fixture("a24-trunc.m4a");

        assertThrows(IOException.class, () -> AlacPcmDecoder.probe(truncated));
    }

    private static byte[] decodeNative(String name, int expectedChannels) throws Exception {
        final Path file = fixture(name);
        final AlacPcmDecoder.Info info = AlacPcmDecoder.probe(file);
        assertEquals(expectedChannels, info.channels());
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        new AlacPcmDecoder(file, info).decode((samples, frames) -> {
            final byte[] bytes = new byte[frames * expectedChannels * 2];
            int offset = 0;
            for (int i = 0; i < frames * expectedChannels; i++) {
                bytes[offset++] = (byte) samples[i];
                bytes[offset++] = (byte) (samples[i] >> 8);
            }
            out.write(bytes, 0, offset);
        });
        return out.toByteArray();
    }

    private static Path fixture(String name) throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                AlacPcmDecoderTest.class.getResource("/local-import/" + name),
                "missing fixture " + name).toURI());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void assertBetween(long low, long high, long actual) {
        assertTrue(actual >= low && actual <= high, actual + " は " + low + "〜" + high + " の外");
    }
}
