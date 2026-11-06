package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;
import com.sedmelluq.discord.lavaplayer.natives.opus.OpusEncoder;

/**
 * 通常の probe が扱えない入力 (ALAC の M4A・FLAC 音声を持つ MKV) を normalize の入口から通す試験と、
 * magic の振り分け。fixture は 2 秒の 440Hz 正弦波。
 */
class LocalFileNormalizerDirectInputTest {

    @TempDir
    Path tempDir;

    // ==== 直 decode で通る入力 ====

    @Test
    void normalizesAlacSixteenBit() throws Exception {
        assertNormalizes("a16.m4a");
    }

    @Test
    void normalizesAlacTwentyFourBit() throws Exception {
        assertNormalizes("a24.m4a");
    }

    @Test
    void normalizesMonoAlac() throws Exception {
        assertNormalizes("mono16.m4a");
    }

    @Test
    void normalizesFlacInMkv() throws Exception {
        assertNormalizes("demo16.mkv");
        assertNormalizes("demo24.mkv");
    }

    @Test
    void normalizesFlacInMkvWithVideo() throws Exception {
        assertNormalizes("demo16-video.mkv");
    }

    // ==== 失敗は既存と同じ形に畳み、output も temp も残さない ====

    @Test
    void failsForATruncatedAlacWithoutLeavingFiles() throws Exception {
        final Path input = copy("a24-trunc.m4a");
        final Path output = tempDir.resolve("out.mka");

        final ResolveException failure = assertThrows(ResolveException.class,
                () -> new MusicLoaderImpl().normalizeLocalFile(input, output));

        assertEquals(com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason.UNKNOWN, failure.reason());
        assertFalse(Files.exists(output));
        assertNoTempLeft();
    }

    /** 小さい出力上限: 48kHz では入らず、下限レートへ落としても入らないので尺の上限として失敗する。 */
    @Test
    void aTinyOutputCapEndsInTheDurationLimit() throws Exception {
        final Path input = copy("a16.m4a");
        final Path output = tempDir.resolve("tiny.mka");
        final List<Integer> rates = new ArrayList<>();

        final ResolveException failure = assertThrows(ResolveException.class,
                () -> new LocalFileNormalizer(new MusicLoaderImpl(), 1_500L,
                        LocalFileNormalizer.MAX_OUTPUT_DURATION_NS,
                        rate -> {
                            rates.add(rate);
                            return new OpusEncoder(rate, 1, LocalFileNormalizer.OPUS_COMPLEXITY);
                        }, () -> {}).normalize(input, output));

        assertTrue(failure.detail().contains("duration limit"), failure.detail());
        assertEquals(LocalFileNormalizer.MIN_ENCODER_RATE, rates.get(rates.size() - 1),
                "最後は下限レート: " + rates);
        assertFalse(Files.exists(output));
        assertNoTempLeft();
    }

    /**
     * 1 回目が途中で止まり、2 回目 (下限レート) で最後まで入る。2 回目も入力の先頭から
     * 開き直して decode できること (直 decode の producer を作り直せること) を見る。
     */
    @Test
    void reopensTheDirectInputForTheSecondPass() throws Exception {
        final Path input = copy("a24.m4a");
        final Path output = tempDir.resolve("second.mka");
        final List<Integer> rates = new ArrayList<>();

        final TrackInfo info = new LocalFileNormalizer(new MusicLoaderImpl(),
                20_000L, LocalFileNormalizer.MAX_OUTPUT_DURATION_NS,
                rate -> {
                    rates.add(rate);
                    return rates.size() == 1
                            ? new FailingFirstPassEncoder(rate)
                            : new OpusEncoder(rate, 1, LocalFileNormalizer.OPUS_COMPLEXITY);
                }, () -> {}).normalize(input, output);

        assertNotNull(info);
        assertEquals(2, rates.size(), "変換し直した: " + rates);
        assertDecodable(new MusicLoaderImpl(), output, info.durationMs());
    }

    // ==== 回帰: 通常経路のまま通る入力 ====

    @Test
    void aacM4aKeepsTheNormalPath() throws Exception {
        final Path input = copy("aac.m4a");
        final Path output = tempDir.resolve("aac.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.normalizeLocalFile(input, output);

        assertDecodable(loader, output, info.durationMs());
        assertEquals(DirectInputs.Kind.MP4, DirectInputs.sniff(input));
        assertEquals(null, DirectInputs.open(input), "AAC は直 decode の対象外");
    }

    @Test
    void opusMkvKeepsTheNormalPath() throws Exception {
        final Path input = tempDir.resolve("clip.mkv");
        Files.write(input, TestMedia.mkv());
        final Path output = tempDir.resolve("clip.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.normalizeLocalFile(input, output);

        assertTrue(info.durationMs() > 1_000L, "実動画の音声が出る: " + info);
        assertDecodable(loader, output, info.durationMs());
        assertEquals(null, DirectInputs.open(input), "A_FLAC の無い MKV は直 decode の対象外");
    }

    /** FLAC と Opus が同居する MKV は通常経路 (Opus) が優先され、直 decode へ行かない。 */
    @Test
    void mixedFlacAndOpusMkvStaysOnTheNormalPath() throws Exception {
        final Path input = copy("mixed-flac-opus.mkv");
        final Path output = tempDir.resolve("mixed.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.normalizeLocalFile(input, output);

        assertDecodable(loader, output, info.durationMs());
        // 通常経路が成功する入力では resolveInput が direct を試さないので、
        // direct の対象ではあっても使われない (経路は PLAYER)。
        assertNotNull(DirectInputs.open(input));
    }

    // ==== magic の振り分け ====

    @Test
    void sniffsContainersByMagic() throws Exception {
        assertEquals(DirectInputs.Kind.MP4, DirectInputs.sniff(copy("a16.m4a")));
        assertEquals(DirectInputs.Kind.MP4, DirectInputs.sniff(copy("aac.m4a")));
        assertEquals(DirectInputs.Kind.MATROSKA, DirectInputs.sniff(copy("demo16.mkv")));
        final Path other = tempDir.resolve("x.bin");
        Files.write(other, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        assertEquals(DirectInputs.Kind.OTHER, DirectInputs.sniff(other));
        final Path tiny = tempDir.resolve("tiny.bin");
        Files.write(tiny, new byte[] {1});
        assertEquals(DirectInputs.Kind.OTHER, DirectInputs.sniff(tiny));
        assertEquals(DirectInputs.Kind.OTHER, DirectInputs.sniff(tempDir.resolve("missing")));
    }

    @Test
    void routesOnlyAlacAndFlacToDirectDecode() throws Exception {
        assertEquals(PcmInput.Origin.ALAC, DirectInputs.open(copy("a16.m4a")).origin());
        assertEquals(PcmInput.Origin.MATROSKA_FLAC, DirectInputs.open(copy("demo24.mkv")).origin());
        assertEquals(PcmInput.Origin.MATROSKA_FLAC,
                DirectInputs.open(copy("demo16-video.mkv")).origin());
        assertEquals(null, DirectInputs.open(copy("a24-trunc.m4a")));
    }

    /** 壊れた moov でも例外を漏らさず null (= 通常経路の元の失敗理由) に畳む。 */
    @Test
    void corruptedHeadersFoldIntoNull() throws Exception {
        final byte[] original = Files.readAllBytes(copy("a16.m4a"));
        final java.util.Random random = new java.util.Random(7);
        for (int round = 0; round < 40; round++) {
            final byte[] broken = original.clone();
            final int from = 12 + random.nextInt(Math.min(400, broken.length - 40));
            for (int i = 0; i < 24; i++) {
                broken[from + i] = (byte) (round % 2 == 0 ? 0xFF : random.nextInt(256));
            }
            final Path file = tempDir.resolve("broken-" + round + ".m4a");
            Files.write(file, broken);
            DirectInputs.open(file); // 例外が漏れないこと
        }
    }

    // ==== 補助 ====

    private void assertNormalizes(String fixture) throws Exception {
        final Path input = copy(fixture);
        final Path output = tempDir.resolve(fixture + ".mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.normalizeLocalFile(input, output);

        assertNotNull(info);
        assertTrue(Math.abs(info.durationMs() - 2_000L) <= 150L, fixture + " の尺: " + info);
        assertDecodable(loader, output, info.durationMs());
        assertNoTempLeft();
    }

    private static void assertDecodable(MusicLoaderImpl loader, Path output, long durationMs)
            throws Exception {
        assertTrue(MatroskaTrackScanner.isAudioOnly(output), "出力に映像 track がある");
        final TrackInfo resolved = loader.resolveTrustedFile(output);
        assertNotNull(resolved);
        assertTrue(resolved.durationMs() > 0L);
        assertTrue(TrustedLocalFileTest.decode(loader.openTrustedFile(output, 0L)).length > 0,
                "先頭から PCM が読める");
        assertTrue(TrustedLocalFileTest.decode(loader.openTrustedFile(output, durationMs / 2L)).length > 0,
                "途中 seek から PCM が読める");
    }

    private void assertNoTempLeft() throws IOException {
        try (var entries = Files.list(tempDir)) {
            assertTrue(entries.noneMatch(p -> p.getFileName().toString().startsWith(".mdm-normalize-")),
                    "一時ファイルが残っている");
        }
    }

    private Path copy(String name) throws IOException, URISyntaxException {
        final Path source = Path.of(Objects.requireNonNull(
                LocalFileNormalizerDirectInputTest.class.getResource("/local-import/" + name),
                "missing fixture " + name).toURI());
        final Path target = tempDir.resolve(name);
        if (!Files.exists(target)) {
            Files.copy(source, target);
        }
        return target;
    }

    /**
     * 出力上限を必ず溢れさせる encoder (packet を最大長まで膨らませる)。48kHz の 1 回目を途中で
     * 止め、2 回目の下限レートへ進ませるために使う。溢れた側の packet は捨てられる。
     */
    private static final class FailingFirstPassEncoder extends OpusEncoder {
        FailingFirstPassEncoder(int rate) {
            super(rate, 1, LocalFileNormalizer.OPUS_COMPLEXITY);
        }

        @Override
        public int encode(java.nio.ShortBuffer pcm, int frameSize, java.nio.ByteBuffer encoded) {
            super.encode(pcm, frameSize, encoded);
            encoded.limit(encoded.capacity());
            return encoded.capacity();
        }
    }
}
