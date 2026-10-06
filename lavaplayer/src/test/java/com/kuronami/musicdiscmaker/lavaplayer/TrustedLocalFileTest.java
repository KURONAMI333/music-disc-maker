package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerProbe;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.natives.mp3.Mp3Decoder;

class TrustedLocalFileTest {

    /**
     * ffmpeg で作った実 MP3。22.05kHz mono / 16kbps / 1.2秒の440Hz正弦波で、
     * ID3 title/artist を持つ。外部コマンドやネットワーク無しでデコーダーを実際に通す。
     */
    static final String MP3_BASE64 = """
            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
            """;

    @TempDir
    Path tempDir;

    @Test
    void resolvesDecodesAndSeeksAnActualMp3WithoutExposingItsPath() throws Exception {
        final Path file = writeFixture();
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.resolveTrustedFile(file);
        assertEquals("Song - Live Mix", info.title());
        assertEquals("Band Official", info.author());
        assertEquals(1_200L, info.durationMs(), info.toString());
        assertEquals("", info.uri());
        assertEquals("", info.identifier());
        assertEquals("", info.thumbnailUrl());
        assertFalse(info.stream());

        final byte[] whole = decode(loader.openTrustedFile(file, 0L));
        final byte[] latterHalf = decode(loader.openTrustedFile(file, 600L));
        assertTrue(whole.length > 80_000, "実MP3から十分なPCMが出ていない: " + whole.length);
        assertTrue(latterHalf.length > 20_000, "seek後にPCMが出ていない: " + latterHalf.length);
        assertTrue(latterHalf.length < whole.length * 0.8,
                "seek位置が反映されず先頭から全量を復号している: " + latterHalf.length + "/" + whole.length);
        assertTrue(hasAudibleSample(whole), "復号PCMが全て無音");

        final byte[] pastEnd = decode(loader.openTrustedFile(file, Long.MAX_VALUE));
        assertTrue(pastEnd.length < 8_000,
                "極端に大きいseekが先頭や曲中へ戻っている: " + pastEnd.length);
    }

    @Test
    void ordinaryUrlApiStillRejectsAPlainLocalPath() throws Exception {
        final Path file = writeFixture();
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final ResolveException failure = assertThrows(ResolveException.class,
                () -> loader.resolve(file.toString()));
        assertEquals(FailureReason.UNSUPPORTED_URL, failure.reason());
    }

    @Test
    void missingId3DoesNotExposeTheStoredFileNameOrLavaplayerPlaceholders() throws Exception {
        final byte[] tagged = Base64.getMimeDecoder().decode(MP3_BASE64);
        final int tagBytes = 10 + ((tagged[6] & 0x7f) << 21) + ((tagged[7] & 0x7f) << 14)
                + ((tagged[8] & 0x7f) << 7) + (tagged[9] & 0x7f);
        final Path file = tempDir.resolve("secret-content-hash.mp3");
        Files.write(file, Arrays.copyOfRange(tagged, tagBytes, tagged.length));

        final TrackInfo info = new MusicLoaderImpl().resolveTrustedFile(file);
        assertEquals("", info.title());
        assertEquals("", info.author());
        assertFalse(info.toString().contains("secret-content-hash"));
        assertFalse(info.toString().contains(tempDir.toString()));
    }

    @Test
    void trustedFileBoundaryAcceptsARealWavWithPositiveDuration() throws Exception {
        // 保存ファイルは hash 名(拡張子なし)で置かれる。内容 probe だけで判定されることを確認する。
        final Path wav = tempDir.resolve("valid-audio");
        Files.write(wav, toneWav(4_400)); // 8kHz mono で 550ms

        final TrackInfo info = new MusicLoaderImpl().resolveTrustedFile(wav);
        assertTrue(info.durationMs() > 0L, "WAV の尺が0: " + info);
    }

    @Test
    void trustedFileBoundaryAcceptsARealFlacWithPositiveDuration() throws Exception {
        final Path flac = tempDir.resolve("valid-audio");
        Files.write(flac, minimalFlac(44_100, 44_100)); // STREAMINFO 宣言で 1.0s

        final TrackInfo info = new MusicLoaderImpl().resolveTrustedFile(flac);
        assertTrue(info.durationMs() > 0L, "FLAC の尺が0: " + info);
    }

    @Test
    void trustedFileBoundaryAcceptsARealMkaWithPositiveDurationAndTags() throws Exception {
        final Path mka = tempDir.resolve("valid-audio");
        Files.write(mka, minimalMka(1_500.0, "Stage1 Fixture", "SWE2"));

        final TrackInfo info = new MusicLoaderImpl().resolveTrustedFile(mka);
        assertEquals(1_500L, info.durationMs(), info.toString());
        assertEquals("Stage1 Fixture", info.title());
        assertEquals("SWE2", info.author());
    }

    @Test
    void audioVideoMkvIsRejectedAtTheSaveBoundary() throws Exception {
        // audio track を併せ持つため matroska probe 自体は受理するが、保存境界の
        // track 検査が VIDEO を見て拒否する (B1: 映像バイトごとの原本保存・転送を防ぐ)。
        final Path mkv = tempDir.resolve("muxed-video");
        Files.write(mkv, audioVideoMkv());

        final MusicLoaderImpl loader = new MusicLoaderImpl();
        final ResolveException failure = assertThrows(ResolveException.class,
                () -> loader.resolveTrustedFile(mkv));
        // 拒否が probe 失敗ではなく映像 track 検査に由来することを detail で確かめる。
        assertTrue(failure.detail().contains("video"), "想定外の拒否理由: " + failure.detail());
        assertFalse(loader.openTrustedFileDetailed(mkv, 0L).isOk(),
                "映像入り MKV が再生境界では開けてしまう");

        // 同じ尺・同じ A_OPUS 構成で映像 track を含まない対照は受理されること
        // (audio+video だけを潰し、音声のみ MKA を巻き込んでいない)。
        final Path mka = tempDir.resolve("audio-only");
        Files.write(mka, minimalMka(1_500.0, "Stage1 Fixture", "SWE2"));
        assertEquals(1_500L, loader.resolveTrustedFile(mka).durationMs());
    }

    @Test
    void trustedFileBoundaryRejectsPlaylistAndInputOnlyContainers() throws Exception {
        // .m3u 拡張子のヒントがあっても playlist probe は registry に無いので受理されない。
        final Path m3u = tempDir.resolve("playlist.m3u");
        Files.write(m3u, ("#EXTM3U\n#EXTINF:120,Song\n" + tempDir.resolve("probe.mp3") + "\n")
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(ResolveException.class, () -> new MusicLoaderImpl().resolveTrustedFile(m3u));

        // ADTS は入力(registry)には読めるが保存形式ではないため、保存境界では弾く。
        final Path aac = tempDir.resolve("voice.aac");
        Files.write(aac, minimalAdts());
        assertThrows(ResolveException.class, () -> new MusicLoaderImpl().resolveTrustedFile(aac));
    }

    @Test
    void storedMp3WithoutExtensionStillUsesTheMp3IndexBranch() throws Exception {
        // 保存名が拡張子なし hash になった後も、内容検出で MP3 と判定されれば
        // LocalMp3Index の尺補正・seek 補正に入ることを確認する。
        final byte[] mp3 = Base64.getMimeDecoder().decode(MP3_BASE64);
        replaceAscii(mp3, "MDM Local Probe", "Song - Live Mix");
        replaceAscii(mp3, "KURONAMI Test", "Band Official");
        final Path file = tempDir.resolve("a".repeat(64));
        Files.write(file, mp3);
        final MusicLoaderImpl loader = new MusicLoaderImpl();

        final TrackInfo info = loader.resolveTrustedFile(file);
        assertEquals(1_200L, info.durationMs(), info.toString());

        final byte[] whole = decode(loader.openTrustedFile(file, 0L));
        final byte[] latterHalf = decode(loader.openTrustedFile(file, 600L));
        assertTrue(latterHalf.length > 20_000 && latterHalf.length < whole.length * 0.8,
                "拡張子なし MP3 の seek 補正が効いていない: " + latterHalf.length + "/" + whole.length);
    }

    @Test
    void probeRegistriesContainNoPlaylistProbesAndKeepMp3Last() {
        assertEquals(List.of("matroska/webm", "wav", "flac", "mp3"),
                MusicLoaderImpl.SAVE_REGISTRY.getAll().stream().map(MediaContainerProbe::getName).toList());
        assertEquals(List.of("matroska/webm", "mp4", "ogg", "wav", "flac", "adts", "mp3"),
                MusicLoaderImpl.INPUT_REGISTRY.getAll().stream().map(MediaContainerProbe::getName).toList());
        // 既定 registry に混在する playlist probe がどちらにも入っていないこと。
        for (MediaContainerProbe probe : MediaContainerRegistry.DEFAULT_REGISTRY.getAll()) {
            assertFalse(Set.of("m3u", "pls", "plain").contains(probe.getName())
                    && MusicLoaderImpl.INPUT_REGISTRY.getAll().contains(probe));
        }
    }

    @Test
    void rejectsAFileWithATruncatedFinalFrameInsteadOfAcceptingAShorterSong() throws Exception {
        final byte[] complete = Base64.getMimeDecoder().decode(MP3_BASE64);
        final Path truncated = tempDir.resolve("truncated.mp3");
        Files.write(truncated, Arrays.copyOf(complete, complete.length - 20));

        final ResolveException failure = assertThrows(ResolveException.class,
                () -> LocalMp3Index.read(truncated));
        assertEquals(FailureReason.UNKNOWN, failure.reason());
    }

    @Test
    void acceptsAStandardId3v1TrailerAfterCompleteFrames() throws Exception {
        final byte[] complete = Base64.getMimeDecoder().decode(MP3_BASE64);
        final byte[] withId3v1 = Arrays.copyOf(complete, complete.length + 128);
        System.arraycopy("TAG".getBytes(StandardCharsets.US_ASCII), 0,
                withId3v1, complete.length, 3);
        final Path file = tempDir.resolve("id3v1.mp3");
        Files.write(file, withId3v1);

        assertEquals(1_200L, LocalMp3Index.read(file).durationMs());
    }

    @Test
    void rejectsAHeaderWithoutFrameSyncEvenIfOtherHeaderBitsLookValid() throws Exception {
        final byte[] complete = Base64.getMimeDecoder().decode(MP3_BASE64);
        final int first = firstFrameOffset(complete);
        final byte[] fake = Arrays.copyOfRange(complete, first,
                first + Mp3Decoder.getFrameSize(complete, first));
        fake[0] = 0;
        final Path file = tempDir.resolve("no-sync.mp3");
        Files.write(file, fake);

        assertThrows(ResolveException.class, () -> LocalMp3Index.read(file));
    }

    @Test
    void rejectsAReservedMpegVersionHeader() throws Exception {
        final byte[] complete = Base64.getMimeDecoder().decode(MP3_BASE64);
        final int first = firstFrameOffset(complete);
        final byte[] fake = Arrays.copyOfRange(complete, first,
                first + Mp3Decoder.getFrameSize(complete, first));
        fake[1] = (byte) ((fake[1] & 0xe7) | 0x08); // version bits 01 = reserved
        final Path file = tempDir.resolve("reserved-version.mp3");
        Files.write(file, fake);

        assertThrows(ResolveException.class, () -> LocalMp3Index.read(file));
    }

    @Test
    void rejectsAChannelLayoutChangeBetweenFrames() throws Exception {
        final byte[] mp3 = Base64.getMimeDecoder().decode(MP3_BASE64);
        final int first = firstFrameOffset(mp3);
        final int second = first + Mp3Decoder.getFrameSize(mp3, first);
        mp3[second + 3] &= 0x3f; // mono(11)からstereo(00)へ変更
        final Path file = tempDir.resolve("channel-change.mp3");
        Files.write(file, mp3);

        assertThrows(ResolveException.class, () -> LocalMp3Index.read(file));
    }

    @Test
    void rejectsXingAndInfoFrameCountsThatDisagreeWithTheActualAudio() throws Exception {
        for (String marker : new String[] {"Info", "Xing"}) {
            for (int declared : new int[] {0, 1, -1}) {
                final byte[] mp3 = Base64.getMimeDecoder().decode(MP3_BASE64);
                final int first = firstFrameOffset(mp3);
                final int xing = first + 13; // このfixtureはMPEG-2 mono、CRCなし
                System.arraycopy(marker.getBytes(StandardCharsets.US_ASCII), 0, mp3, xing, 4);
                mp3[xing + 8] = (byte) (declared >>> 24);
                mp3[xing + 9] = (byte) (declared >>> 16);
                mp3[xing + 10] = (byte) (declared >>> 8);
                mp3[xing + 11] = (byte) declared;
                final Path file = tempDir.resolve(marker + declared + ".mp3");
                Files.write(file, mp3);
                assertThrows(ResolveException.class, () -> LocalMp3Index.read(file));
            }
        }
    }

    @Test
    void detailedOpenReportsAnUnavailableTrustedFileWithoutLeakingItsPath() {
        final Path missing = tempDir.resolve("private-name.mp3");
        final OpenStreamResult result = new MusicLoaderImpl().openTrustedFileDetailed(missing, 0L);

        assertFalse(result.isOk());
        assertEquals(FailureReason.UNKNOWN, result.reason());
        assertFalse(result.detail().contains(missing.toString()));
    }

    private Path writeFixture() throws Exception {
        final Path file = tempDir.resolve("probe.mp3");
        final byte[] mp3 = Base64.getMimeDecoder().decode(MP3_BASE64);
        replaceAscii(mp3, "MDM Local Probe", "Song - Live Mix");
        replaceAscii(mp3, "KURONAMI Test", "Band Official");
        Files.write(file, mp3);
        return file;
    }

    private static int firstFrameOffset(byte[] mp3) {
        return 10 + ((mp3[6] & 0x7f) << 21) + ((mp3[7] & 0x7f) << 14)
                + ((mp3[8] & 0x7f) << 7) + (mp3[9] & 0x7f);
    }

    /** 同じバイト長の ID3 text payload だけを差し替え、frame size と音声本体は維持する。 */
    private static void replaceAscii(byte[] bytes, String before, String after) {
        final byte[] needle = before.getBytes(StandardCharsets.US_ASCII);
        final byte[] replacement = after.getBytes(StandardCharsets.US_ASCII);
        assertEquals(needle.length, replacement.length, "ID3 frame sizeを変えている");
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            boolean matches = true;
            for (int j = 0; j < needle.length; j++) {
                matches &= bytes[i + j] == needle[j];
            }
            if (matches) {
                System.arraycopy(replacement, 0, bytes, i, replacement.length);
                return;
            }
        }
        throw new AssertionError("MP3 fixtureに置換対象のID3文字列が無い: " + before);
    }

    static byte[] decode(IAudioSource source) throws Exception {
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
        throw new AssertionError("PCM終端まで8秒以内に到達しなかった");
    }

    static boolean hasAudibleSample(byte[] pcm) {
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            if (pcm[i] != 0 || pcm[i + 1] != 0) {
                return true;
            }
        }
        return false;
    }

    /** 8kHz / mono / 16bit / {@code samples} 個の実 PCM を持つ最小 WAV。 */
    static byte[] toneWav(int samples) {
        final int dataBytes = samples * 2;
        final ByteBuffer wav = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataBytes);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        wav.putShort((short) 1).putShort((short) 1).putInt(8_000).putInt(16_000);
        wav.putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
        for (int i = 0; i < samples; i++) {
            wav.putShort((short) (10_000 * Math.sin(2.0 * Math.PI * 440.0 * i / 8_000.0)));
        }
        return wav.array();
    }

    /**
     * STREAMINFO だけを持つ最小 FLAC。尺は宣言 totalSamples/sampleRate から来るため、
     * probe の受理と尺&gt;0 を検査するには音声 frame 本体は要らない。
     */
    private static byte[] minimalFlac(int sampleRate, long totalSamples) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("fLaC".getBytes(StandardCharsets.US_ASCII));
        // metadata block header: last-block(0x80) | type STREAMINFO(0), length=34
        out.write(0x80); out.write(0); out.write(0); out.write(34);
        final ByteBuffer info = ByteBuffer.allocate(34);
        info.putShort((short) 4_096).putShort((short) 4_096); // min/max block size
        info.put(new byte[] {0, 0, 0}).put(new byte[] {0, 0, 0}); // min/max frame size
        // sampleRate(20) | channels-1(3) | bps-1(5) | totalSamples(36) を 64bit で詰める。
        final long packed = ((long) sampleRate << 44) | (0L << 41) | (15L << 36)
                | (totalSamples & 0xFFFFFFFFFL);
        info.putLong(packed);
        info.put(new byte[16]); // MD5 未使用
        out.writeBytes(info.array());
        return out.toByteArray();
    }

    /**
     * 1 本の A_OPUS TrackEntry を持つ最小 MKA。EBML+Segment+Info{TimestampScale,
     * Duration}+Tracks+Tags+1 Cluster の構成で、probe が尺と codec を読める
     * (MDM_310_VIDEO_AUDIO_DESIGN_CORRECTED_RESULT_20260927 §6 の必須要素のみ)。
     * SimpleBlock の中身は probe が読まないので、再生不可能な擬似パケットでよい。
     */
    static byte[] minimalMka(double durationMs, String title, String artist) {
        final byte[] ebml = el("1A45DFA3", cat(
                uintEl("4286", 1), uintEl("42F7", 1), uintEl("42F2", 4), uintEl("42F3", 8),
                strEl("4282", "matroska"), uintEl("4287", 4), uintEl("4285", 2)));
        final byte[] info = el("1549A966", cat(
                uintEl("2AD7B1", 1_000_000), f64El("4489", durationMs),
                utf8El("4D80", "mdm-stage1-fixture"), utf8El("5741", "mdm-stage1-fixture")));
        final byte[] audio = el("E1", cat(f32El("B5", 48_000.0f), uintEl("9F", 1)));
        final byte[] trackEntry = el("AE", cat(
                uintEl("D7", 1), uintEl("73C5", 1), uintEl("83", 2),
                strEl("86", "A_OPUS"), audio));
        final byte[] tracks = el("1654AE6B", trackEntry);
        final byte[] tags = el("1254C367", el("7373", cat(
                el("67C8", cat(strEl("45A3", "TITLE"), utf8El("4487", title))),
                el("67C8", cat(strEl("45A3", "ARTIST"), utf8El("4487", artist))))));
        // track1・相対 timecode 0・keyframe、中身は擬似パケット (probe は参照しない)。
        final byte[] block = el("A3", new byte[] {(byte) 0x81, 0, 0, (byte) 0x80, 1, 2, 3, 4});
        final byte[] cluster = el("1F43B675", cat(uintEl("E7", 0), block));
        return cat(ebml, el("18538067", cat(info, tracks, tags, cluster)));
    }

    /**
     * 映像 track (TrackType=1) と音声 track (A_OPUS) を併せ持つ最小 MKV。
     * audio track が在るため matroska probe は解決に成功するが、保存境界の
     * 映像検査で弾かれるべき形 (B1 回帰用)。映像 block の中身は区別が付く大きさの
     * 擬似バイトで、実 decoder を通さなくてよい。
     */
    static byte[] audioVideoMkv() {
        final byte[] ebml = el("1A45DFA3", cat(
                uintEl("4286", 1), uintEl("42F7", 1), uintEl("42F2", 4), uintEl("42F3", 8),
                strEl("4282", "matroska"), uintEl("4287", 4), uintEl("4285", 2)));
        final byte[] info = el("1549A966", cat(
                uintEl("2AD7B1", 1_000_000), f64El("4489", 1_500.0)));
        final byte[] videoSettings = el("E0", cat(uintEl("B0", 640), uintEl("BA", 360)));
        final byte[] videoEntry = el("AE", cat(
                uintEl("D7", 1), uintEl("73C5", 11), uintEl("83", 1),
                strEl("86", "V_MS/VFW/FOURCC"), videoSettings));
        final byte[] audioSettings = el("E1", cat(f32El("B5", 48_000.0f), uintEl("9F", 1)));
        final byte[] audioEntry = el("AE", cat(
                uintEl("D7", 2), uintEl("73C5", 22), uintEl("83", 2),
                strEl("86", "A_OPUS"), audioSettings));
        final byte[] tracks = el("1654AE6B", cat(videoEntry, audioEntry));
        // 保存領域へそのまま入ると困る「映像バイト」を模した 1KiB の payload。
        final byte[] fakeVideo = new byte[1_024];
        for (int i = 0; i < fakeVideo.length; i++) { fakeVideo[i] = (byte) i; }
        final byte[] videoBlock = el("A3",
                cat(new byte[] {(byte) 0x81, 0, 0, (byte) 0x80}, fakeVideo));
        final byte[] audioBlock = el("A3",
                new byte[] {(byte) 0x82, 0, 0, (byte) 0x80, 1, 2, 3, 4});
        final byte[] cluster = el("1F43B675", cat(uintEl("E7", 0), videoBlock, audioBlock));
        return cat(ebml, el("18538067", cat(info, tracks, cluster)));
    }

    /**
     * 映像 track だけを持つ最小 MKV (音声 track なし)。picker で選ばれた無音声動画が
     * normalize でも明示失敗する回帰用。構造は {@link #audioVideoMkv()} と同型で
     * audio Entry/block を抜いたもの。
     */
    static byte[] videoOnlyMkv() {
        final byte[] ebml = el("1A45DFA3", cat(
                uintEl("4286", 1), uintEl("42F7", 1), uintEl("42F2", 4), uintEl("42F3", 8),
                strEl("4282", "matroska"), uintEl("4287", 4), uintEl("4285", 2)));
        final byte[] info = el("1549A966", cat(
                uintEl("2AD7B1", 1_000_000), f64El("4489", 1_500.0)));
        final byte[] videoSettings = el("E0", cat(uintEl("B0", 640), uintEl("BA", 360)));
        final byte[] videoEntry = el("AE", cat(
                uintEl("D7", 1), uintEl("73C5", 11), uintEl("83", 1),
                strEl("86", "V_MS/VFW/FOURCC"), videoSettings));
        final byte[] tracks = el("1654AE6B", videoEntry);
        final byte[] fakeVideo = new byte[1_024];
        for (int i = 0; i < fakeVideo.length; i++) { fakeVideo[i] = (byte) i; }
        final byte[] videoBlock = el("A3",
                cat(new byte[] {(byte) 0x81, 0, 0, (byte) 0x80}, fakeVideo));
        final byte[] cluster = el("1F43B675", cat(uintEl("E7", 0), videoBlock));
        return cat(ebml, el("18538067", cat(info, tracks, cluster)));
    }

    /** 1 frame の ADTS (AAC) ヘッダ + 擬似ペイロード。保存境界が入力専用形式を弾く確認用。 */
    private static byte[] minimalAdts() {
        final int frameLength = 7 + 16;
        final ByteBuffer b = ByteBuffer.allocate(frameLength + 16);
        // syncword 0xFFF, MPEG-4, layer 0, protection_absent 1
        b.put((byte) 0xFF).put((byte) 0xF1);
        // profile=AAC LC(1), sampling index=44100(4), private 0, channel=2
        b.put((byte) ((1 << 6) | (4 << 2)));
        // channel config 下位 2bit + frame_length(13bit)
        b.put((byte) ((2 << 6) | ((frameLength >>> 11) & 0x3)));
        b.put((byte) (frameLength >>> 3));
        b.put((byte) ((frameLength & 0x7) << 5 | 0x1F));
        b.put((byte) 0xFC); // fullness 下位 + 0 blocks
        b.put(new byte[frameLength - 7]);
        return Arrays.copyOf(b.array(), frameLength);
    }

    // ---------- 最小 EBML helpers ----------

    private static byte[] el(String idHex, byte[] payload) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] id = hex(idHex);
        out.write(id, 0, id.length);
        final byte[] size = vintSize(payload.length);
        out.write(size, 0, size.length);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    private static byte[] vintSize(long value) {
        for (int len = 1; len <= 8; len++) {
            final long max = (1L << (7 * len)) - 2; // all-ones は unknown の意味
            if (value <= max) {
                final byte[] out = new byte[len];
                long x = value | (1L << (7 * len));
                for (int i = len - 1; i >= 0; i--) { out[i] = (byte) x; x >>= 8; }
                return out;
            }
        }
        throw new IllegalArgumentException("EBML size too big");
    }

    private static byte[] uintEl(String id, long value) {
        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (int i = 7; i >= 0; i--) {
            if ((value >> (i * 8)) != 0 || i == 0 || payload.size() > 0) {
                payload.write((int) (value >> (i * 8)) & 0xFF);
            }
        }
        return el(id, payload.toByteArray());
    }

    private static byte[] strEl(String id, String value) {
        return el(id, value.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] utf8El(String id, String value) {
        return el(id, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] f64El(String id, double value) {
        return el(id, ByteBuffer.allocate(8).putDouble(value).array());
    }

    private static byte[] f32El(String id, float value) {
        return el(id, ByteBuffer.allocate(4).putFloat(value).array());
    }

    private static byte[] cat(byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private static byte[] hex(String value) {
        final byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
