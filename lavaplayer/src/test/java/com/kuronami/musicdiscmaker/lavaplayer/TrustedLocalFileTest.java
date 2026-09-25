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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;
import com.sedmelluq.discord.lavaplayer.natives.mp3.Mp3Decoder;

class TrustedLocalFileTest {

    /**
     * ffmpeg で作った実 MP3。22.05kHz mono / 16kbps / 1.2秒の440Hz正弦波で、
     * ID3 title/artist を持つ。外部コマンドやネットワーク無しでデコーダーを実際に通す。
     */
    private static final String MP3_BASE64 = """
            SUQzBAAAAAAAV1RJVDIAAAARAAADTURNIExvY2FsIFByb2JlAFRQRTEAAAAPAAADS1VST05BTUkgVGVzdABUU1NFAAAADwAAA0xhdmY2Mi4xMi4xMDEAAAAAAAAAAAAAAP/zcMAAAAAAAAAAAABJbmZvAAAADwAAADAAAAqCABYWGxsgICUlKiovLzQ0OTk+PkNDSEhMTFFRUVZWW1tgYGVlampvb3R0eXl+foODiIiNjY2SkpeXnJyhoaamq6uwsLW1urq/v8TEycnJzs7T09jY3d3i4ufn7Ozx8fb2+/v//wAAAABMYXZjNjIuMjgAAAAAAAAAAAAAAAAkAmQAAAAAAAAKgkSwZOIAAAAAAAAAAAAAAAAA//MgxAAIMALNH0EAAuEILh/+AAD4Pg/BMHwfB+7y4Pv5zg//9/Kef5Tz/Ke/oXSZ18G/VP/zIsQGCmh6mAGaOADBghBTlBjgDVgBqDrypiLdCAwtShCD0Hv48NhtwVCXg0JTv1ICgf////MgxAQHuEZ8GdwAAqlSSyqyvhCCDDIQOJlUw0D1OKaNWwW/////2cAAJRFBJQBnkIt6uf/zIMQMCXA6Wb4OeiJad0+yRxbs8Z+qi08V9X0f9av9atv6/L24QaAAANthlrwBpgwCDZL/8yDEDQnAPkpeF7AkqljKxjY/IzIlQm0Z+9XZT/b+7Zan/9X/2+3VWCjAQgZ1W1rSRRgC//MixA0JeEokAAf0CIAKmoaEEQPSjQJvZFRWxb/7v9n/rf///9TdyCTH9kYE1lQUwA8ASNH/8yDEDweYSigABz4EkR8sIAhkgXmkN/AV///yNfULGOpiylyljGAQAefnzsgQDapKBZ68//MgxBcG4EYsAD88ID2f//713lBJl3HKZMNRVMATAIDTkSlEDATyBrbRWevCv//+n/7loP/zIMQiCCBKJAAHPgQAYALZZRgBI+Io45ZQycCI08tozb/9v6rf+67/Xs9v/xbRUCpluoL/8yLEKAiAOmpeDjoiwzOUxTADQB40LEo1AwD8ly60ttDP//+7/yCAADNahjLrl2Yaa0CACf/zIMQuB/hGKAA/PiA5tmWA4DFtoraM///9H/7fbd6gEwHsF2UCXagFMARALjSYzNs5IJD/8yDENQeoQjlQFvwgpadFZ4Z///3f+RXW4kYNgn6pWdJDGAUBSf6V5B9ApJOrLrI9n//9//MgxD0IIEYkAAf0CF/9P///7N5REwcxNljM5SFMAsCo/fcRT5CTVcaW2hjv//9//pXLGP/zIsRDCIBGKAAHsgiRg8CXrFZ0icYBAFp+SZdHsGlc7susj2f//7//asa0AmBWgwyKSgpZ//MgxEkHoEYoAAeyCNMATARjTMUR85o4uiy6HZcMO/+n/j//Jf9//1++gAUjcLENAF9Qkf/zIMRRB6hGKAAHsginXLXX+XMCAGTm0ksBQGLLodpjos6pfb3eum3/TKu/l7N//q8C1f//8yDEWQnwRiAAB/QI1jlAJrm4IpKCggABMARATDSKUjM45Aviy6HaYYTd//+t3+uQ+rb///MixFgLyEI1vi78IO/2Kv/n4xI1JN0wVjF1jACgEMz3JBoPg1H5rsasnmf//43/2f///or/8yDEUAqIRiAA5/RAG2AkuGABebLmMSOU1MIl+1aKSCv/o/9T/7ltmexn/xfuMSMCsBH0//MgxEwJIEYkAOfyQK1YxdYwAgBHM5oRODyNRScmNUt//r/1f9NOj//9eyreURMCxBEkrv/zIMROCAA6cH4HECZYpbkwAcBIM3bRmTxOSJcqM02P///I//f/VTwAbygMYQgYa1WvI9D/8yLEVQlQRiQAB/IIAAzOjTdA+FWM/tNix9V///v/8n/068AAJHKG8wCbHENOd1coVAgN3//zIMRXCGhKJAAH8ghrTAQDTXY1Snv2ipru/VT/64//PxjR3nWkisYucYAEAnmYFJSxzIr/8yDEXAjARixQL7AkYzkxqqw1//7Nf/yWn1f9X7EwwC5lSmDyEYyZxVKQaBYb1uX5vEzm//MgxGAIwEI1sBb8IBqlCqNv9j/9H/3f1v/neRo8LPSRXUXOMAAAUTKuEyI/TS+dmVVSB//zIsRkCbBGJAjf8kC///xn/kL+fzsZPn3kRlNQoABmABgMRltqwqcbCJrhQzW2a///VDH///MgxGUIeEIwUAewCKv+qoABa2AjCUCBXq15KoKAcm5F4oaksOhmVVSH//+r/6f///2V8//zIMRqCDBGJADf8EABTAywJ5LpdJc0CgKhkQ6eWc5JyulKaxNzv//1P/9P///6FQM9hRj/8yDEcAkgSiAA3/JASwM69WvJpBYDs17vXjIlgURmrpD/d/+//0qAoe4kwmQWF/OKmiIQ//MixHIImEYtSBewJDw1S/fTChkMSmbhN3//57/9v/f/9VXgAADje0egAWtnoc7alSHUxWn/8yDEdwjQRiQAL/AkMJwawS+uT+319v/r/7v0/1LABGAb0dEs80OtxHQGDJHlFSqgGaui//MgxHoHaEYsQBewJL///0f/////ugNgAFdgB9gNmQ00SJABzEyZCZDNVRX9e7///6P+7//zIMSDCIhGLBIXsCT9K8X0WzAxAwPcY50WW84qeoyB4ZKP4ojAIZ7HqG4H3X//qs//yH//8yLEhwioOlJeDjwg///61Q95RnqBqcLBi+wwAsmDnKQwoAKraemPXcDP//+z/1f9//+p9//zIMSMB7BCPUwOvCAMJgapPJgqCEdAWDAIVIkhotl6pLcr5mf//6n/+R+r//9ChDaws7b/8yDElAi4Pkh+DnwgBFaWnJbEACWYLSkBEIAep1BtBdwM//9Wqn/7///+2uoQACH7ngPm//MgxJgJUEYsVBb8ICRhDhZiVQKDGqwjA2Hu1S4D+///6//p/0IqJ7A56R4ogyZBEVQFc//zIsSZCLBKJAgG/gQP5UcAqAKrYeuOV6gZd7P/9v/r////VeZzh6amoW01BkgAVjE+FF0C//MgxJ4IqE4kAC/wJAAost6I/Ur5u///rG/+Se4S8wNABmUIacjMOgK5i86iEY6rIeOOV//zIMSiCRBKKAoW/iCoGXf//VR/93///1r/7zOWHnvKebTUZRQBUMbIUIzNRYOAJuoD7v//8yDEpAgQRjRwp55A/+p3+ij//6uugAFwH779SkdxrEHNHMHEzTcYwgODAiKXZf0T///7//MixKoI6EokIC7+ID/1VQESdvV8TLKIQKjMjoUWhjPXIakEgaheYysLcAOukKO+F9yeIJ//8yDErgfwTiQABv4EjjOE5/lcwND//mBofMDT+DAQDDP8oUcGCioC8AnGlxkShCBID0Q5//MgxLUI6EokAAfwCANic+ciSJJiu+KCgpvQUFd4KC/8UVVMQU1FMy4xMDBVVVVVVVVVVf/zIMS4CPBGJADf8EBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/8yLEuwgISoVZWwACVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/zIMTCD4j+iKmbgABVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/8yDEqgiYUpBRzAABVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
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
    void trustedFileBoundaryRejectsARealWavBecauseV310AcceptsMp3Only() throws Exception {
        final Path wav = tempDir.resolve("valid.wav");
        Files.write(wav, silentWav());

        final ResolveException failure = assertThrows(ResolveException.class,
                () -> new MusicLoaderImpl().resolveTrustedFile(wav));
        assertEquals(FailureReason.UNKNOWN, failure.reason());
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

    private static byte[] decode(IAudioSource source) throws Exception {
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

    private static boolean hasAudibleSample(byte[] pcm) {
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            if (pcm[i] != 0 || pcm[i + 1] != 0) {
                return true;
            }
        }
        return false;
    }

    /** 8kHz / mono / 16bit / 1 sample の最小有効WAV。MP3以外の実形式を弾く確認に使う。 */
    private static byte[] silentWav() {
        final ByteBuffer wav = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(38);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        wav.putShort((short) 1).putShort((short) 1).putInt(8_000).putInt(16_000);
        wav.putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(2).putShort((short) 0);
        return wav.array();
    }
}
