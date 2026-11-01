package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
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

    /**
     * 上限の手前で切れる入力は、encoder のレートを 24kHz へ落として 1 回だけ変換し直し、
     * 入力ぶんの尺を持つ盤にする (曲が黙って途中で終わらない)。
     *
     * <p>尺から選ぶレートは実測の最悪側に余裕を足して決めてあるので、48kHz を選んだ曲が
     * 実際に溢れるのは VBR がその余裕を超えて膨らんだ時だけ。その上振れを
     * {@link InflatingEncoder} で作り、2 回目が実 encoder の 24kHz で最後まで入ることを見る。
     */
    @Test
    void reEncodesAtHalfRateInsteadOfEndingTheTrackEarly() throws Exception {
        final Path input = write("noise.wav", noiseWav(48_000)); // 6秒@8kHz の雑音 (VBR で膨らみやすい)
        final MusicLoaderImpl loader = new MusicLoaderImpl();
        final Baseline full = encodeFullyAt(input);
        // 48kHz が選ばれる (尺と上限だけで決まる) かつ、膨らんだ 1 回目が途中で止まる上限。
        final long cap = 46_000L;
        final List<Integer> used = new ArrayList<>();
        final Path output = tempDir.resolve("retried.mka");

        final TrackInfo info = new LocalFileNormalizer(loader, cap,
                LocalFileNormalizer.MAX_OUTPUT_DURATION_NS,
                rate -> {
                    used.add(rate);
                    return rate == LocalFileNormalizer.DEFAULT_ENCODER_RATE
                            ? new InflatingEncoder(rate)
                            : new OpusEncoder(rate, 1, LocalFileNormalizer.OPUS_COMPLEXITY);
                }, () -> {}).normalize(input, output);

        assertEquals(List.of(LocalFileNormalizer.DEFAULT_ENCODER_RATE,
                LocalFileNormalizer.MIN_ENCODER_RATE), used,
                "1 回目は 48kHz で切れ、2 回目は 24kHz で変換し直す: " + used);
        assertEquals(full.durationMs(), info.durationMs(),
                "尺が入力と一致する (途中で終わらない): " + info);
        assertTrue(Files.size(output) <= cap, "出力は上限内: " + Files.size(output));
        assertAudioOnlyDecodableOutput(loader, output, info.durationMs());
        assertNoTempLeft();
    }

    /**
     * 下限レート (24kHz) まで下げても上限に入らない入力は、短い成功を返さず尺の上限として
     * 失敗する (半分だけ鳴る盤を黙って作らない)。
     */
    @Test
    void failsWhenEvenTheFloorRateCannotHoldTheWholeTrack() throws Exception {
        final Path input = write("noise.wav", noiseWav(48_000));
        final MusicLoaderImpl loader = new MusicLoaderImpl();
        final Baseline full = encodeFullyAt(input);
        // 24kHz でも入らない上限 (実測の 24kHz は 48kHz の半分強なので、その内側)。
        final long cap = full.bytes() * 35L / 100L;
        final Path output = tempDir.resolve("too-small.mka");
        final List<Integer> used = new ArrayList<>();

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> new LocalFileNormalizer(loader, cap,
                        LocalFileNormalizer.MAX_OUTPUT_DURATION_NS,
                        rate -> {
                            used.add(rate);
                            return new OpusEncoder(rate, 1, LocalFileNormalizer.OPUS_COMPLEXITY);
                        }, () -> {})
                        .normalize(input, output));

        assertTrue(ex.detail().contains("duration limit"), ex.detail());
        assertEquals(List.of(LocalFileNormalizer.MIN_ENCODER_RATE), used,
                "この上限では最初から下限レートで、変換し直す先が無い: " + used);
        assertFalse(Files.exists(output), "短い成功を返さない");
        assertNoTempLeft();
    }

    /** 上限に収まる曲は 1 回の encode のまま (変換し直しの経路に入らない)。 */
    @Test
    void keepsASinglePassForATrackThatFits() throws Exception {
        final Path input = write("short.wav", noiseWav(9_600)); // 1.2秒@8kHz
        final Path output = tempDir.resolve("short.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();
        final List<Integer> used = new ArrayList<>();

        new LocalFileNormalizer(loader, LocalFileNormalizer.MAX_OUTPUT_BYTES,
                LocalFileNormalizer.MAX_OUTPUT_DURATION_NS,
                rate -> {
                    used.add(rate);
                    return new OpusEncoder(rate, 1, LocalFileNormalizer.OPUS_COMPLEXITY);
                }, () -> {}).normalize(input, output);

        assertEquals(List.of(LocalFileNormalizer.DEFAULT_ENCODER_RATE), used,
                "短い曲は 48kHz のまま変換し直さない: " + used);
        assertTrue(Files.size(output) <= LocalFileNormalizer.MAX_OUTPUT_BYTES);
        assertNoTempLeft();
    }

    /**
     * 出力見積の packet 付帯は実測値 (7〜8 byte) の 8。以前の 12 は 2 時間の曲で
     * 4 byte × 50 個/秒 × 7200 秒 ≈ 1.4MiB を音声から差し引いていた。
     */
    @Test
    void countsTheMeasuredPerPacketOverhead() {
        assertEquals(8L, LocalFileNormalizer.ESTIMATE_PER_PACKET);
        assertEquals(LocalFileNormalizer.ESTIMATE_PER_PACKET * 50L * 8L,
                LocalFileNormalizer.CONTAINER_BPS, "コンテナ分は同じ値から計算する");
    }

    // ==== 尺から選ぶ encoder のレート (長い曲を「尺超過」で失敗させない) ====

    /**
     * 24kHz で encode した盤も、48kHz と同じ尺・音高・seek で鳴る。
     *
     * <p>出力の SamplingFrequency と OpusHead は 48kHz のまま (Opus の復号レートは encoder の
     * レートに依らない) なので、読み戻す側は 48kHz の PCM を受け取る。間引きで尺が縮んだり
     * 音高がずれたりしていれば、この読み戻しで分かる。
     */
    @Test
    void aTrackEncodedAtHalfRateKeepsItsDurationPitchAndSeek() throws Exception {
        final Path input = writeMka("tone.mka", 51); // 宣言 1.02 秒・decode で 50 frame = 1 秒
        final Path output = tempDir.resolve("half-rate.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();
        final List<Integer> used = new ArrayList<>();
        // 1 秒で 48kHz は収まらず 24kHz なら収まる上限 (レートは尺と上限だけで決まる)。
        final long cap = 6_000L;

        final TrackInfo info = new LocalFileNormalizer(loader, cap,
                LocalFileNormalizer.MAX_OUTPUT_DURATION_NS,
                rate -> {
                    used.add(rate);
                    return new OpusEncoder(rate, 1, LocalFileNormalizer.OPUS_COMPLEXITY);
                }, () -> {}).normalize(input, output);

        assertEquals(List.of(LocalFileNormalizer.MIN_ENCODER_RATE), used,
                "この上限では 24kHz を 1 回だけ使う: " + used);
        assertEquals(1_000L, info.durationMs(), "間引いても尺が変わらない: " + info);
        assertAudioOnlyDecodableOutput(loader, output, info.durationMs());
        assertTonePitch(TrustedLocalFileTest.decode(loader.openTrustedFile(output, 0L)), 440.0);
    }

    /**
     * 60 秒の盤でも尺・音高・seek が保たれる (24kHz 化で壊れやすい 3 点を 1 本で見る)。
     *
     * <p>前半 30 秒を 440Hz・後半 30 秒を 880Hz にしてあるので、seek 先の音高で位置が分かる。
     * Cluster は 30 秒ごとなので、seek が効かず先頭から鳴っていれば 440Hz が出る。
     *
     * <p>尺は絶対値ではなく同じ入力の 48kHz 版と比べる。入力 WAV の 8kHz→48kHz 変換と
     * 末尾 1 frame の差で絶対値は 60 秒ちょうどにならない (48kHz 版も同じだけ短い) ので、
     * ここで見たい「レートを落として尺が動かない」は比較で判定する。
     */
    @Test
    void aMinuteLongTrackAtHalfRateKeepsItsLengthPitchAndSeek() throws Exception {
        final Path input = write("minute.wav", twoToneWav(30, 440.0, 30, 880.0));
        final Path output = tempDir.resolve("minute.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();
        final List<Integer> used = new ArrayList<>();
        // 60 秒が 24kHz でだけ収まる上限 (48kHz なら 390KB 前後になる)。
        final long cap = 300_000L;
        final Baseline full = encodeFullyAt(input);

        final TrackInfo info = new LocalFileNormalizer(loader, cap,
                LocalFileNormalizer.MAX_OUTPUT_DURATION_NS,
                rate -> {
                    used.add(rate);
                    return new OpusEncoder(rate, 1, LocalFileNormalizer.OPUS_COMPLEXITY);
                }, () -> {}).normalize(input, output);

        assertEquals(List.of(LocalFileNormalizer.MIN_ENCODER_RATE), used,
                "この上限では 24kHz を 1 回だけ使う: " + used);
        assertEquals(full.durationMs(), info.durationMs(),
                "レートを落としても尺が変わらない: " + info);
        assertTrue(Files.size(output) <= cap, "出力は上限内: " + Files.size(output));

        final byte[] whole = TrustedLocalFileTest.decode(loader.openTrustedFile(output, 0L));
        final byte[] reference = TrustedLocalFileTest.decode(
                loader.openTrustedFile(full.output(), 0L));
        assertTrue(Math.abs(whole.length - reference.length) <= 1_920,
                "復号 PCM の長さが 48kHz 版と同じ (差 "
                        + Math.abs(whole.length - reference.length) + " byte)");
        assertTonePitchAt(whole, 5.0, 440.0);
        assertTonePitchAt(whole, 45.0, 880.0);

        final byte[] fromFortySeconds = TrustedLocalFileTest.decode(
                loader.openTrustedFile(output, 40_000L));
        assertTrue(fromFortySeconds.length < whole.length * 0.5,
                "seek 後は 40 秒以降だけを復号する: " + fromFortySeconds.length + "/" + whole.length);
        assertTonePitchAt(fromFortySeconds, 1.0, 880.0);
        assertNoTempLeft();
    }

    /** 短い曲は既定の 48kHz のまま (品質を落とさない)。 */
    @Test
    void keepsTheDefaultRateForAShortTrack() {
        assertEquals(LocalFileNormalizer.DEFAULT_ENCODER_RATE,
                LocalFileNormalizer.rateFor(3L * 60_000L, LocalFileNormalizer.MAX_OUTPUT_BYTES));
    }

    /** 中くらいの長さは 48kHz のままで、上限尺では 24kHz へ落ちる (段がある)。 */
    @Test
    void lowersTheRateOnlyWhereTheOutputWouldNotFit() {
        assertEquals(LocalFileNormalizer.DEFAULT_ENCODER_RATE,
                LocalFileNormalizer.rateFor(60L * 60_000L, LocalFileNormalizer.MAX_OUTPUT_BYTES));
        assertEquals(LocalFileNormalizer.MIN_ENCODER_RATE,
                LocalFileNormalizer.rateFor(90L * 60_000L, LocalFileNormalizer.MAX_OUTPUT_BYTES),
                "90 分は 48kHz のままだと 32MiB に入らない");
    }

    /**
     * 上限尺 (2 時間) は下限レートで 32MiB に余裕を残して収まる — 尺を理由に失敗させない。
     * 実測の最悪側 ({@link LocalFileNormalizer#MEASURED_BPS_24K}) とコンテナ分で見積もり、
     * 余裕が 5% 以上あることを確かめる (余裕が無いと VBR の上振れで末尾が切れる)。
     */
    @Test
    void theLongestAllowedTrackFitsAtTheFloorRate() {
        assertEquals(LocalFileNormalizer.MIN_ENCODER_RATE,
                LocalFileNormalizer.rateFor(LocalFileNormalizer.MAX_DECLARED_DURATION_MS,
                        LocalFileNormalizer.MAX_OUTPUT_BYTES));

        final long estimated = LocalFileNormalizer.MAX_DECLARED_DURATION_MS / 1_000L
                * (LocalFileNormalizer.MEASURED_BPS_24K + LocalFileNormalizer.CONTAINER_BPS) / 8L;
        final long margin = LocalFileNormalizer.MAX_OUTPUT_BYTES - estimated;
        assertTrue(margin * 100L / LocalFileNormalizer.MAX_OUTPUT_BYTES >= 5L,
                "2 時間の見積が上限に近すぎる: " + estimated + " byte");
    }

    /**
     * 尺が分からない入力は既定レートで始める。尺を持たない短い入力 (一部の ADTS 等) を
     * 理由もなく 24kHz にしない。長ければ encode 中の実 packet で溢れ、その時に
     * 24kHz へ落として変換し直す。
     */
    @Test
    void keepsTheDefaultRateWhenTheLengthIsUnknown() {
        assertEquals(LocalFileNormalizer.DEFAULT_ENCODER_RATE,
                LocalFileNormalizer.rateFor(0L, LocalFileNormalizer.MAX_OUTPUT_BYTES));
        assertEquals(LocalFileNormalizer.DEFAULT_ENCODER_RATE,
                LocalFileNormalizer.rateFor(Long.MAX_VALUE,
                        LocalFileNormalizer.MAX_OUTPUT_BYTES));
    }

    /** 選んだレートが実際に encoder へ渡る (尺 → レート → encode の配線)。 */
    @Test
    void createsTheEncoderWithTheRateChosenFromTheLength() throws Exception {
        final Path input = write("long.wav", TrustedLocalFileTest.toneWav(480_000)); // 60秒@8kHz
        final Path output = tempDir.resolve("lowered.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();
        // 60 秒で 48kHz が入らない上限 (24kHz なら入る)。実寸の 2% 上にしない:
        // レートの選択は尺と上限だけで決まり、変換し直しは起きない。
        final long cap = 250_000L;
        final AtomicReference<Integer> used = new AtomicReference<>();

        new LocalFileNormalizer(loader, cap, LocalFileNormalizer.MAX_OUTPUT_DURATION_NS,
                rate -> {
                    used.set(rate);
                    return new OpusEncoder(rate, 1, LocalFileNormalizer.OPUS_COMPLEXITY);
                }, () -> {}).normalize(input, output);

        final int chosen = LocalFileNormalizer.rateFor(60_000L, cap);
        assertEquals(Integer.valueOf(chosen), used.get(),
                "尺から選んだレートが encoder へ渡るべき");
        assertEquals(LocalFileNormalizer.MIN_ENCODER_RATE, chosen,
                "この入力と cap では 24kHz へ落ちるべき: " + chosen);
        assertTrue(Files.size(output) <= cap, "出力は上限内: " + Files.size(output));
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

    /** 宣言尺が 2 時間超の入力は decode 前に明示失敗する。 */
    @Test
    void failsForDeclaredDurationOverTheLimit() throws Exception {
        final Path input = write("too-long.mka",
                TrustedLocalFileTest.minimalMka(121.0 * 60_000.0, "t", "a"));
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
                        rate -> {
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

    /**
     * 尺 cap を超える入力は、cap ちょうどで黙って終わらせず尺の上限として失敗する
     * (cap を小さくして機構を試す)。
     */
    @Test
    void failsWhenTheTrackIsLongerThanTheDurationBound() throws Exception {
        final Path input = writeMka("long.mka", 3_001); // decode で 3000 frame = 60 秒
        final Path output = tempDir.resolve("truncated.mka");
        final MusicLoaderImpl loader = new MusicLoaderImpl();
        final long capNs = 60L * 20_000_000L; // 60 frame = 1.2 秒ちょうど

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> new LocalFileNormalizer(loader, LocalFileNormalizer.MAX_OUTPUT_BYTES, capNs,
                        rate -> new OpusEncoder(rate, 1, LocalFileNormalizer.OPUS_COMPLEXITY),
                        () -> {}).normalize(input, output));

        assertTrue(ex.detail().contains("duration limit"), ex.detail());
        assertFalse(Files.exists(output), "短い成功にしない");
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
                rate -> new OpusEncoder(rate, 1, LocalFileNormalizer.OPUS_COMPLEXITY),
                () -> {}).normalize(input, exact);
        assertEquals(1_200L, exactInfo.durationMs(), "cap と一致する入力は全 frame が入る: " + exactInfo);

        final Path shortCap = tempDir.resolve("short-cap.mka");
        final TrackInfo shortInfo = new LocalFileNormalizer(loader,
                LocalFileNormalizer.MAX_OUTPUT_BYTES, 1_180_000_000L, // 59 frame
                rate -> new OpusEncoder(rate, 1, LocalFileNormalizer.OPUS_COMPLEXITY),
                () -> {}).normalize(input, shortCap);
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
                        rate -> new OpusEncoder(rate, 1, LocalFileNormalizer.OPUS_COMPLEXITY),
                        () -> {}).normalize(input, output));
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
                        rate -> new OpusEncoder(rate, 1, LocalFileNormalizer.OPUS_COMPLEXITY),
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

    /**
     * 復号 PCM (48kHz mono S16LE) の中央 0.5 秒の正の向きのゼロ交差から音高を測る。
     * 間引きのレートを取り違えると 1 オクターブずれる (220Hz / 880Hz) ので、
     * 440Hz ±5% で判定できる。
     */
    private static void assertTonePitch(byte[] pcm, double expectedHz) {
        assertTonePitchAt(pcm, pcm.length / 2.0 / 48_000.0 / 3.0, expectedHz);
    }

    /** {@code fromSeconds} から 0.5 秒ぶんの音高。フィルタの過渡を避けて窓をずらす時に使う。 */
    private static void assertTonePitchAt(byte[] pcm, double fromSeconds, double expectedHz) {
        final int sampleRate = 48_000;
        final int samples = pcm.length / 2;
        final int from = (int) Math.round(fromSeconds * sampleRate);
        final int to = Math.min(samples, from + sampleRate / 2);
        assertTrue(to - from >= sampleRate / 4,
                "音高を測る長さが足りない: " + (to - from) + " sample (" + fromSeconds + "s から)");
        int crossings = 0;
        int previous = 0;
        for (int i = from; i < to; i++) {
            final int value = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
            if (previous < 0 && value >= 0) {
                crossings++;
            }
            previous = value;
        }
        final double measured = crossings / ((to - from) / (double) sampleRate);
        assertTrue(Math.abs(measured - expectedHz) <= expectedHz * 0.05,
                fromSeconds + "s からの音高が " + measured + "Hz (期待 " + expectedHz
                        + "Hz): 間引きか seek でレート・位置がずれている");
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

    /** 上限を作るための実測値 (同じ入力を既定レートで最後まで encode した実寸・尺・置き場所)。 */
    private record Baseline(Path output, long bytes, long durationMs) {}

    /**
     * {@code input} を実 encoder (48kHz) で最後まで encode した実寸と尺。上限はこの実寸から作る
     * (固定の byte 数を書くと、encoder の実出力との差で試験が不安定になる)。
     */
    private Baseline encodeFullyAt(Path input) throws IOException {
        final Path probe = tempDir.resolve("baseline.mka");
        final TrackInfo info = new LocalFileNormalizer(new MusicLoaderImpl(),
                LocalFileNormalizer.MAX_OUTPUT_BYTES, LocalFileNormalizer.MAX_OUTPUT_DURATION_NS,
                rate -> new OpusEncoder(rate, 1, LocalFileNormalizer.OPUS_COMPLEXITY),
                () -> {}).normalize(input, probe);
        return new Baseline(probe, Files.size(probe), info.durationMs());
    }

    /**
     * 48kHz の出力を実際の packet より膨らませる encoder。実 encoder の 48kHz は 51.4kbps 止まりで、
     * 尺から選ぶレートの余裕 (52kbps) を超えないため、VBR の上振れで上限を溢れる状況だけを作る。
     * 溢れた側の packet は捨てられるので、中身は実 packet + 0 埋めでよい。
     */
    private static final class InflatingEncoder extends OpusEncoder {

        private static final int INFLATION = 4;

        InflatingEncoder(int rate) {
            super(rate, 1, LocalFileNormalizer.OPUS_COMPLEXITY);
        }

        @Override
        public int encode(ShortBuffer pcm, int frameSize, ByteBuffer encoded) {
            final int length = super.encode(pcm, frameSize, encoded);
            // 長さだけ膨らませる (中身は実 packet の後ろの残り)。溢れた側の packet は
            // 捨てられるので、読める packet である必要はない。
            final int inflated = Math.min(length * INFLATION, encoded.capacity());
            encoded.limit(inflated);
            return inflated;
        }
    }

    /**
     * 8kHz mono 16bit の 2 音 WAV (前半 {@code firstSeconds} 秒を {@code firstHz}、後半を
     * {@code secondHz})。seek が効いているかを復号後の音高で判定するために使う。
     */
    private static byte[] twoToneWav(int firstSeconds, double firstHz, int secondSeconds,
            double secondHz) {
        final int rate = 8_000;
        final int samples = (firstSeconds + secondSeconds) * rate;
        final int dataBytes = samples * 2;
        final ByteBuffer wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataBytes);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        wav.putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2);
        wav.putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
        final int switchAt = firstSeconds * rate;
        for (int i = 0; i < samples; i++) {
            final double hz = i < switchAt ? firstHz : secondHz;
            wav.putShort((short) (10_000 * Math.sin(2.0 * Math.PI * hz * i / rate)));
        }
        return wav.array();
    }

    /**
     * 8kHz mono 16bit の白色雑音 WAV。VBR で膨らみやすい入力の代わり (2 時間の曲は試験に収まらない
     * ので、短い尺と同じ形で試す)。
     */
    private static byte[] noiseWav(int samples) {
        final int dataBytes = samples * 2;
        final ByteBuffer wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataBytes);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        wav.putShort((short) 1).putShort((short) 1).putInt(8_000).putInt(16_000);
        wav.putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
        final Random random = new Random(7L);
        for (int i = 0; i < samples; i++) {
            wav.putShort((short) (random.nextInt(20_000) - 10_000));
        }
        return wav.array();
    }
}
