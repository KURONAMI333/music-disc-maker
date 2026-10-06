package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;
import com.sedmelluq.discord.lavaplayer.natives.opus.OpusEncoder;

/**
 * stage 3a の normalize コア試験。実 decoder/encoder を通して
 * 「任意のローカル入力 → mono 48kHz Opus の音声だけの MKA」を検査する。
 * fixture は実メディア ({@link TestMedia}) と既存の stage1/2 fixture を共用する。
 */
class LocalFileNormalizerTest {

    @TempDir
    Path tempDir;

    // ==== 正常系 ====

    /** 実 MP3 (22.05kHz mono の実ファイル) が音声のみ MKA になる。最も多い入力の回帰。 */
    @Test
    void normalizesARealMp3IntoAnAudioOnlyMka() throws Exception {
        final Path input = write("song.mp3",
                Base64.getMimeDecoder().decode(TrustedLocalFileTest.MP3_BASE64));
        final Path output = tempDir.resolve("normalized.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.normalizeLocalFile(input, output);

        assertNotNull(info);
        assertTrue(info.durationMs() > 0L, "尺が出ている: " + info);
        assertTrue(Files.isRegularFile(output));
        assertAudioOnlyDecodableOutput(loader, output, info.durationMs());
    }

    /** MKA (実 Opus packet) 入力 → 音声のみ MKA。 */
    @Test
    void normalizesAnMkaInputIntoAnAudioOnlyMka() throws Exception {
        final Path input = tempDir.resolve("input.mka");
        MkaWriter.write(input, MkaWriterTest.encodeTone(60, 440.0)); // 1.2秒
        final Path output = tempDir.resolve("normalized.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.normalizeLocalFile(input, output);

        assertNotNull(info);
        assertTrue(info.durationMs() > 0L);
        assertTrue(Files.isRegularFile(output));
        assertAudioOnlyDecodableOutput(loader, output, info.durationMs());
    }

    /** WAV 入力 → 音声のみ MKA。 */
    @Test
    void normalizesAWavInputIntoAnAudioOnlyMka() throws Exception {
        final Path input = write("tone.wav", TrustedLocalFileTest.toneWav(9_600)); // 1.2秒@8kHz
        final Path output = tempDir.resolve("normalized.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.normalizeLocalFile(input, output);

        assertNotNull(info);
        assertTrue(info.durationMs() > 0L);
        assertAudioOnlyDecodableOutput(loader, output, info.durationMs());
    }

    /** 実 Ogg/Vorbis 入力 → 音声のみ MKA。 */
    @Test
    void normalizesARealOggIntoAnAudioOnlyMka() throws Exception {
        final Path input = write("tone.ogg", TestMedia.ogg());
        final Path output = tempDir.resolve("normalized.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.normalizeLocalFile(input, output);

        assertNotNull(info);
        assertTrue(info.durationMs() > 0L);
        assertAudioOnlyDecodableOutput(loader, output, info.durationMs());
    }

    /**
     * 実 MP4 (h264 映像 + aac 音声) から音声だけを取り出す。
     * 生成物は音声のみ MKA で、映像 track は残らない。
     */
    @Test
    void extractsAudioFromARealMp4() throws Exception {
        final Path input = write("clip.mp4", TestMedia.mp4());
        final Path output = tempDir.resolve("clip.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.normalizeLocalFile(input, output);

        assertNotNull(info);
        assertTrue(info.durationMs() > 1_000L, "実動画の音声 ~1.4s が出るべき: " + info);
        assertTrue(Files.isRegularFile(output));
        assertAudioOnlyDecodableOutput(loader, output, info.durationMs());
    }

    /** 実 matroska (h264 映像 + opus 音声) から音声だけを取り出す。 */
    @Test
    void extractsAudioFromARealMkv() throws Exception {
        final Path input = write("clip.mkv", TestMedia.mkv());
        final Path output = tempDir.resolve("clip.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.normalizeLocalFile(input, output);

        assertNotNull(info);
        assertTrue(info.durationMs() > 1_000L, "実動画の音声 ~1.4s が出るべき: " + info);
        assertTrue(Files.isRegularFile(output));
        assertAudioOnlyDecodableOutput(loader, output, info.durationMs());
    }

    /** 実 MOV (mpeg4 映像 + aac 音声) から音声だけを取り出す。 */
    @Test
    void extractsAudioFromARealMov() throws Exception {
        final Path input = write("clip.mov", TestMedia.mov());
        final Path output = tempDir.resolve("clip.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.normalizeLocalFile(input, output);

        assertNotNull(info);
        assertTrue(info.durationMs() > 1_000L, "実動画の音声 ~1.4s が出るべき: " + info);
        assertTrue(Files.isRegularFile(output));
        assertAudioOnlyDecodableOutput(loader, output, info.durationMs());
    }

    /** 実 WebM (vp8 映像 + opus 音声) から音声だけを取り出す。 */
    @Test
    void extractsAudioFromARealWebm() throws Exception {
        final Path input = write("clip.webm", TestMedia.webm());
        final Path output = tempDir.resolve("clip.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.normalizeLocalFile(input, output);

        assertNotNull(info);
        assertTrue(info.durationMs() > 1_000L, "実動画の音声 ~1.4s が出るべき: " + info);
        assertTrue(Files.isRegularFile(output));
        assertAudioOnlyDecodableOutput(loader, output, info.durationMs());
    }

    /**
     * 音声 track が 2 本ある実動画では 1 本だけ decode される (この pin 版では
     * 0.7s の後続 track が選ばれた = 実測 700ms)。どの track が選ばれるかは
     * lavaplayer の track 選択に従う。出力はどちらにしても音声のみ MKA。
     */
    @Test
    void picksOneAudioTrackFromAMultiTrackVideo() throws Exception {
        final Path input = write("two-audio.mkv", TestMedia.mkv2Audio());
        final Path output = tempDir.resolve("clip.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.normalizeLocalFile(input, output);

        assertNotNull(info);
        assertTrue(info.durationMs() >= 600L && info.durationMs() <= 1_000L,
                "この pin 版では後続の 0.7s track が選ばれた (実測 700ms): " + info);
        assertTrue(Files.isRegularFile(output));
        assertAudioOnlyDecodableOutput(loader, output, info.durationMs());
    }

    /** 実 FLAC 入力 → 音声のみ MKA。 */
    @Test
    void normalizesARealFlacIntoAnAudioOnlyMka() throws Exception {
        final Path input = write("tone.flac", TestMedia.flac());
        final Path output = tempDir.resolve("normalized.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.normalizeLocalFile(input, output);

        assertNotNull(info);
        assertTrue(info.durationMs() > 0L);
        assertAudioOnlyDecodableOutput(loader, output, info.durationMs());
    }

    /** 実 ADTS (aac) 入力 → 音声のみ MKA。 */
    @Test
    void normalizesARealAdtsIntoAnAudioOnlyMka() throws Exception {
        final Path input = write("tone.aac", TestMedia.adts());
        final Path output = tempDir.resolve("normalized.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.normalizeLocalFile(input, output);

        assertNotNull(info);
        assertTrue(info.durationMs() > 0L);
        assertAudioOnlyDecodableOutput(loader, output, info.durationMs());
    }

    /**
     * 拡張子ではなく実内容で判定する。中身が WAV の .mp4、実 MP4 の .wav、
     * 拡張子なしの実 MKV が全部通る。
     */
    @Test
    void detectsContentNotTheFileExtension() throws Exception {
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final Path wavNamedMp4 = write("video.mp4", TrustedLocalFileTest.toneWav(9_600));
        final Path out1 = tempDir.resolve("from-mp4-name.mka");
        assertNotNull(loader.normalizeLocalFile(wavNamedMp4, out1));
        assertTrue(MatroskaTrackScanner.isAudioOnly(out1));

        final Path mp4NamedWav = write("tone.wav", TestMedia.mp4());
        final Path out2 = tempDir.resolve("from-wav-name.mka");
        assertNotNull(loader.normalizeLocalFile(mp4NamedWav, out2));
        assertTrue(MatroskaTrackScanner.isAudioOnly(out2));

        final Path mkvNoExt = write("a1b2c3d4", TestMedia.mkv());
        final Path out3 = tempDir.resolve("from-noext.mka");
        assertNotNull(loader.normalizeLocalFile(mkvNoExt, out3));
        assertTrue(MatroskaTrackScanner.isAudioOnly(out3));
    }

    /** 無音だが尺がある入力は正当な音声 — 0 尺の失敗とは別物として通る。 */
    @Test
    void normalizesASilentInput() throws Exception {
        final byte[] wav = TrustedLocalFileTest.toneWav(4_800); // 0.6秒@8kHz の形状
        Arrays.fill(wav, 44, wav.length, (byte) 0); // PCM を全部無音にする
        final Path input = write("silent.wav", wav);
        final Path output = tempDir.resolve("silent.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.normalizeLocalFile(input, output);
        assertNotNull(info);
        assertTrue(info.durationMs() > 0L);
        assertTrue(MatroskaTrackScanner.isAudioOnly(output));
    }

    /** 長い入力は出力上限で有界に打ち切られる (cap を小さくして機構を試験する)。 */
    @Test
    void truncatesAtTheOutputBound() throws Exception {
        final Path input = write("long.wav", TrustedLocalFileTest.toneWav(480_000)); // 60秒@8kHz
        final Path output = tempDir.resolve("truncated.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();
        final long cap = 64L * 1024L;

        final TrackInfo info = new LocalFileNormalizer(loader, cap,
                LocalFileNormalizer.MAX_OUTPUT_DURATION_NS,
                () -> new OpusEncoder(48_000, 1, 64_000), () -> {}).normalize(input, output);

        assertNotNull(info);
        assertTrue(Files.isRegularFile(output));
        assertTrue(Files.size(output) <= cap, "出力は cap 内: " + Files.size(output));
        assertTrue(info.durationMs() > 0L);
        assertTrue(info.durationMs() < 55_000L, "60秒入力が cap で短くなっている: " + info);
        assertAudioOnlyDecodableOutput(loader, output, info.durationMs());
    }

    // ==== 明示失敗 ====

    /** 入力 2GiB 超は decode 前に明示失敗 (sparse file で実測)。 */
    @Test
    void failsForAnOversizedInput() throws Exception {
        final Path input = tempDir.resolve("huge.bin");
        try (final var raf = new java.io.RandomAccessFile(input.toFile(), "rw")) {
            raf.setLength(3L * 1024 * 1024 * 1024);
        }
        final Path output = tempDir.resolve("out.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> loader.normalizeLocalFile(input, output));
        assertTrue(ex.detail().contains("size limit"), ex.detail());
        assertFalse(Files.exists(output));
        assertNoTempLeft();
    }

    /** 0 バイトの入力は decode 前に明示失敗。 */
    @Test
    void failsForAnEmptyFile() throws Exception {
        final Path input = write("empty.bin", new byte[0]);
        final Path output = tempDir.resolve("out.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        assertThrows(ResolveException.class, () -> loader.normalizeLocalFile(input, output));
        assertFalse(Files.exists(output));
        assertNoTempLeft();
    }

    /** 内容の無いジャンクは「decode 不能」で明示失敗。output/temp は残らない。 */
    @Test
    void failsForCorruptInput() throws Exception {
        final byte[] junk = new byte[4_096];
        new Random(42L).nextBytes(junk);
        final Path input = write("garbage.bin", junk);
        final Path output = tempDir.resolve("out.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> loader.normalizeLocalFile(input, output));
        assertFalse(ex.detail().isBlank());
        assertFalse(Files.exists(output));
        assertNoTempLeft();
    }

    /** 実ファイルの途中で切れた入力も decode 側で明示失敗する。 */
    @Test
    void failsForTruncatedRealInput() throws Exception {
        final Path input = write("cut.mp4", Arrays.copyOf(TestMedia.mp4(), 2_048));
        final Path output = tempDir.resolve("out.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        assertThrows(ResolveException.class, () -> loader.normalizeLocalFile(input, output));
        assertFalse(Files.exists(output));
        assertNoTempLeft();
    }

    /** ヘッダは正しいが packet が壊れた MKA は decode で明示失敗。 */
    @Test
    void failsForUndecodableMka() throws Exception {
        final Path input = write("broken.mka",
                TrustedLocalFileTest.minimalMka(1_500.0, "t", "a"));
        final Path output = tempDir.resolve("out.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        assertThrows(ResolveException.class, () -> loader.normalizeLocalFile(input, output));
        assertFalse(Files.exists(output));
        assertNoTempLeft();
    }

    /** 映像 track を宣言し、壊れた音声しか持たない MKV も decode で明示失敗。 */
    @Test
    void failsForVideoMkvWithUndecodableAudio() throws Exception {
        final Path input = write("broken-video.mkv", TrustedLocalFileTest.audioVideoMkv());
        final Path output = tempDir.resolve("out.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        assertThrows(ResolveException.class, () -> loader.normalizeLocalFile(input, output));
        assertFalse(Files.exists(output));
        assertNoTempLeft();
    }

    /** 尺ゼロ (音声 0 frame) の入力は明示失敗。 */
    @Test
    void failsForZeroLengthAudio() throws Exception {
        final Path input = write("empty.wav", TrustedLocalFileTest.toneWav(0));
        final Path output = tempDir.resolve("out.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        assertThrows(ResolveException.class, () -> loader.normalizeLocalFile(input, output));
        assertFalse(Files.exists(output));
        assertNoTempLeft();
    }

    /** 宣言尺が 60 分超の入力は decode 前に明示失敗する。 */
    @Test
    void failsForDeclaredDurationOverTheLimit() throws Exception {
        final Path input = write("too-long.mka",
                TrustedLocalFileTest.minimalMka(61.0 * 60_000.0, "t", "a"));
        final Path output = tempDir.resolve("out.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> loader.normalizeLocalFile(input, output));
        assertTrue(ex.detail().contains("duration"), ex.detail());
        assertFalse(Files.exists(output));
        assertNoTempLeft();
    }

    /** 呼び出しスレッドが既に中断されていると即座に失敗し、interrupt 状態を保持する。 */
    @Test
    void failsWhenInterruptedBeforeDecoding() throws Exception {
        final Path input = write("in.wav", TrustedLocalFileTest.toneWav(9_600));
        final Path output = tempDir.resolve("out.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        Thread.currentThread().interrupt();
        try {
            assertThrows(ResolveException.class,
                    () -> loader.normalizeLocalFile(input, output));
        } finally {
            assertTrue(Thread.interrupted(), "interrupt 状態が残っているべき (ここでクリア)");
        }
        assertFalse(Files.exists(output));
        assertNoTempLeft();
    }

    /** encode 途中の中断でも明示失敗し、encoder close・temp 削除が走る。 */
    @Test
    void abortsWhenInterruptedDuringEncoding() throws Exception {
        final Path input = write("very-long.wav",
                TrustedLocalFileTest.toneWav(4_800_000)); // 600秒@8kHz
        final Path output = tempDir.resolve("out.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();
        final AtomicReference<Throwable> thrown = new AtomicReference<>();
        final AtomicBoolean flagKept = new AtomicBoolean();

        final Thread worker = new Thread(() -> {
            try {
                loader.normalizeLocalFile(input, output);
            } catch (final Throwable t) {
                thrown.set(t);
                flagKept.set(Thread.currentThread().isInterrupted());
                Thread.interrupted(); // テスト側でクリア
            }
        });
        worker.start();
        Thread.sleep(300L);
        worker.interrupt();
        worker.join(30_000L);

        assertFalse(worker.isAlive(), "中断で止まらなかった (30秒 hang)");
        assertTrue(thrown.get() instanceof ResolveException,
                "中断は ResolveException: " + thrown.get());
        assertTrue(flagKept.get(), "呼び出し側の interrupt 状態が残るべき");
        assertFalse(Files.exists(output));
        assertNoTempLeft();
    }

    /** Opus encoder の native が立てない環境では理由つき明示失敗 (注入で再現)。 */
    @Test
    void failsWhenOpusEncoderIsUnavailable() throws Exception {
        final Path input = write("ok.wav", TrustedLocalFileTest.toneWav(9_600));
        final Path output = tempDir.resolve("out.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> new LocalFileNormalizer(loader, LocalFileNormalizer.MAX_OUTPUT_BYTES,
                        LocalFileNormalizer.MAX_OUTPUT_DURATION_NS,
                        () -> {
                            throw new UnsatisfiedLinkError("no opus natives");
                        }, () -> {}).normalize(input, output));
        assertTrue(ex.detail().contains("opus encoder"), ex.detail());
        assertFalse(Files.exists(output));
        assertNoTempLeft();
    }

    /** 既存の output は上書きしない (置き換え前に止まる)。 */
    @Test
    void refusesToOverwriteAnExistingOutput() throws Exception {
        final Path input = write("ok.wav", TrustedLocalFileTest.toneWav(9_600));
        final Path output = tempDir.resolve("exists.mka");
        Files.write(output, new byte[] {9, 9, 9});
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        assertThrows(ResolveException.class, () -> loader.normalizeLocalFile(input, output));
        assertEquals(3L, Files.size(output), "既存ファイルが壊れていない");
        assertNoTempLeft();
    }

    // ==== F-2: 尺上限は encode した実尺側でも効く ====
    // この pin 版の再生経路は末尾の 1 frame (20ms) を返さない (実測: 60 packet の
    // MKA → 59 frame、1.2s の WAV → 59 frame)。frame 単位の境界試験は packet 数が
    // 正確な MKA fixture で組み立て、「decode 後の frame 数」を基準に cap を置く。

    /** 尺 cap は宣言に依らず encode した実尺で打ち切る (cap を小さくして機構を試す)。 */
    @Test
    void truncatesAtTheDurationBound() throws Exception {
        final Path input = writeMka("long.mka", 3_001); // decode で 3000 frame
        final Path output = tempDir.resolve("truncated.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();
        final long capNs = 60L * 20_000_000L; // 60 frame = 1.2 秒ちょうど

        final TrackInfo info = new LocalFileNormalizer(loader,
                LocalFileNormalizer.MAX_OUTPUT_BYTES, capNs,
                () -> new OpusEncoder(48_000, 1, 64_000), () -> {}).normalize(input, output);

        assertNotNull(info);
        assertEquals(1_200L, info.durationMs(), "cap ちょうどの尺に揃う: " + info);
        assertAudioOnlyDecodableOutput(loader, output, info.durationMs());
        assertNoTempLeft();
    }

    /** decode 後の尺が cap とちょうど一致すれば打ち切らない。1 frame 短い cap では 1 frame 分だけ切る。 */
    @Test
    void durationBoundIsInclusive() throws Exception {
        final MusicLoaderImpl loader = new MusicLoaderImpl();
        final Path input = writeMka("tone.mka", 61); // decode で 60 frame = 1.2 秒ちょうど

        final Path exact = tempDir.resolve("exact.mka");
        final TrackInfo exactInfo = new LocalFileNormalizer(loader,
                LocalFileNormalizer.MAX_OUTPUT_BYTES, 1_200_000_000L,
                () -> new OpusEncoder(48_000, 1, 64_000), () -> {}).normalize(input, exact);
        assertEquals(1_200L, exactInfo.durationMs(), "cap と一致する入力は全 frame が入る: " + exactInfo);

        final Path shortCap = tempDir.resolve("short-cap.mka");
        final TrackInfo shortInfo = new LocalFileNormalizer(loader,
                LocalFileNormalizer.MAX_OUTPUT_BYTES, 1_180_000_000L, // 59 frame
                () -> new OpusEncoder(48_000, 1, 64_000), () -> {}).normalize(input, shortCap);
        assertEquals(1_180L, shortInfo.durationMs(), "cap-1frame で打ち切る: " + shortInfo);
        assertNoTempLeft();
    }

    /** cap が 1 frame に満たないなら中途半端な出力を出さず明示失敗に倒す。 */
    @Test
    void failsWhenDurationCapFitsNoFrame() throws Exception {
        final Path input = writeMka("tone.mka", 61);
        final Path output = tempDir.resolve("out.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> new LocalFileNormalizer(loader, LocalFileNormalizer.MAX_OUTPUT_BYTES,
                        10_000_000L, // 10ms < 1 frame (20ms)
                        () -> new OpusEncoder(48_000, 1, 64_000), () -> {}).normalize(input, output));
        assertTrue(ex.detail().contains("no decodable audio"), ex.detail());
        assertFalse(Files.exists(output));
        assertNoTempLeft();
    }

    // ==== F-1: 既存 output を競合時も置換しない発行 ====

    /**
     * 事前検査の後・発行の直前に第三者が output を作った競合でも、既存を置換せず
     * 明示失敗に倒す。発行直前フックで「check→発行の窓」への割込みを決定的に再現する。
     */
    @Test
    void failsWithoutClobberingWhenOutputAppearsDuringNormalization() throws Exception {
        final Path input = write("ok.wav", TrustedLocalFileTest.toneWav(9_600));
        final Path output = tempDir.resolve("raced.mka");
        final byte[] foreign = {7, 7, 7};
        final MusicLoaderImpl loader = new MusicLoaderImpl();
        final AtomicBoolean raced = new AtomicBoolean();

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> new LocalFileNormalizer(loader, LocalFileNormalizer.MAX_OUTPUT_BYTES,
                        LocalFileNormalizer.MAX_OUTPUT_DURATION_NS,
                        () -> new OpusEncoder(48_000, 1, 64_000),
                        () -> {
                            try {
                                Files.write(output, foreign); // 発行直前に同名を出す
                                raced.set(true);
                            } catch (final IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }).normalize(input, output));
        assertTrue(raced.get(), "競合注入が発行前に走っていない");
        assertTrue(ex.detail().contains("already exists"), ex.detail());
        assertArrayEquals(foreign, Files.readAllBytes(output), "競合側の既存ファイルは無傷");
        assertNoTempLeft();
    }

    /** 発行 primitive 単体でも「既存名は置換しない」を FS が保証する (hard link = EEXIST)。 */
    @Test
    void publishFailsClosedWhenTargetExists() throws Exception {
        final Path temp = write("stage.bin", new byte[] {1, 2, 3});
        final Path output = tempDir.resolve("taken.mka");
        Files.write(output, new byte[] {9, 9});
        final LocalFileNormalizer normalizer = new LocalFileNormalizer(new MusicLoaderImpl());

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> normalizer.publish(temp, output));
        assertTrue(ex.detail().contains("already exists"), ex.detail());
        assertArrayEquals(new byte[] {9, 9}, Files.readAllBytes(output), "既存ファイルは無傷");
        assertTrue(Files.exists(temp), "失敗した発行は temp を消費しない (呼出し側が掃除する)");
    }

    /** 発行 primitive の正常系: temp の内容が output 名へ付き、temp 名は外れる。 */
    @Test
    void publishInstallsAFreshTarget() throws Exception {
        final Path temp = write("stage.bin", new byte[] {4, 5, 6});
        final Path output = tempDir.resolve("fresh.mka");
        final LocalFileNormalizer normalizer = new LocalFileNormalizer(new MusicLoaderImpl());

        normalizer.publish(temp, output);

        assertArrayEquals(new byte[] {4, 5, 6}, Files.readAllBytes(output));
        assertFalse(Files.exists(temp), "発行済みの temp 名は外れる");
    }

    // ==== stage3b: picker 分岐の前提 ====

    /**
     * {@code LocalAudioClient.prepare} と同じ判定を loader 層で固定する:
     * 保存 probe ({@code resolveTrustedFile}) が通れば pass-through、落ちれば
     * {@code normalizeLocalFile} へ送る。拡張子はどちらの判定にも出ない。
     */
    @Test
    void saveProbeOrNormalizeMatchesTheClientBranch() throws Exception {
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        // 実 MP3 → 保存形式 = pass-through。
        final Path mp3 = write("song.mp3",
                Base64.getMimeDecoder().decode(TrustedLocalFileTest.MP3_BASE64));
        assertNotNull(loader.resolveTrustedFile(mp3));

        // 同じ実 MP3 を誤った拡張子で。内容検出なのでやはり pass-through する。
        final Path mp3Renamed = write("video.webm",
                Base64.getMimeDecoder().decode(TrustedLocalFileTest.MP3_BASE64));
        assertNotNull(loader.resolveTrustedFile(mp3Renamed));

        // 実動画 (内容 MP4) → 保存 probe は失敗 → normalize → 音声のみ MKA →
        // そのまま保存・再生の両境界を通る。
        final Path mp4 = write("clip.bin", TestMedia.mp4());
        assertThrows(ResolveException.class, () -> loader.resolveTrustedFile(mp4));
        final Path out = tempDir.resolve("out.mka");
        final TrackInfo info = loader.normalizeLocalFile(mp4, out);
        assertNotNull(info);
        assertAudioOnlyDecodableOutput(loader, out, info.durationMs());
    }

    /** 対応外ジャンクと無音声動画は picker 経路でも明示失敗し、残骸を残さない。 */
    @Test
    void pickerBranchFailsExplicitlyForUnsupportedAndAudioLessVideo() throws Exception {
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final Path junk = write("junk.bin", new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        assertThrows(ResolveException.class, () -> loader.resolveTrustedFile(junk));
        final Path out1 = tempDir.resolve("junk.mka");
        assertThrows(ResolveException.class, () -> loader.normalizeLocalFile(junk, out1));
        assertFalse(Files.exists(out1));

        final Path videoOnly = write("movie.mkv", TrustedLocalFileTest.videoOnlyMkv());
        final Path out2 = tempDir.resolve("movie.mka");
        assertThrows(ResolveException.class, () -> loader.normalizeLocalFile(videoOnly, out2));
        assertFalse(Files.exists(out2));
        assertNoTempLeft();
    }

    // ==== helpers ====

    /**
     * normalize の出力が「音声のみ・尺&gt;0・先頭から decode できる・途中 seek で
     * 読める」を生成物に対して外側から再確認する。
     */
    private static void assertAudioOnlyDecodableOutput(MusicLoaderImpl loader, Path output,
            long durationMs) throws Exception {
        assertTrue(MatroskaTrackScanner.isAudioOnly(output), "出力に映像 track がある");
        assertTrue(Files.size(output) <= LocalFileNormalizer.MAX_OUTPUT_BYTES);
        final TrackInfo resolved = loader.resolveTrustedFile(output);
        assertNotNull(resolved);
        assertTrue(resolved.durationMs() > 0L);
        final byte[] whole = TrustedLocalFileTest.decode(loader.openTrustedFile(output, 0L));
        assertTrue(whole.length > 0, "先頭から PCM が読める");
        final byte[] mid = TrustedLocalFileTest.decode(
                loader.openTrustedFile(output, durationMs / 2L));
        assertTrue(mid.length > 0, "途中 seek から PCM が読める");
    }

    private void assertNoTempLeft() throws IOException {
        try (final var entries = Files.list(tempDir)) {
            assertTrue(entries.noneMatch(
                    p -> p.getFileName().toString().startsWith(".mdm-normalize-")),
                    "一時ファイルが残っている");
        }
    }

    private Path write(String name, byte[] data) throws IOException {
        final Path file = tempDir.resolve(name);
        Files.write(file, data);
        return file;
    }

    /** 指定 packet 数の MKA を実 encoder で組んで書き出す (decode 時は -1 frame)。 */
    private Path writeMka(String name, int packets) throws IOException {
        final Path file = tempDir.resolve(name);
        MkaWriter.write(file, MkaWriterTest.encodeTone(packets, 440.0));
        return file;
    }
}
