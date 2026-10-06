package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;

/**
 * ローカル取込の MP3 非依存化を固定する。picker 候補・表示名・失敗→status 写像は
 * Minecraft 非依存の {@link LocalImportDecisions} として headless 試験できる
 * ({@code LocalAudioClient} 本体は Component 参照を含むため test runtime では読めない)。
 * decode/normalize 本体は lavaplayer 側の fixture 試験が担う。
 */
class LocalAudioClientImportTest {

    /** picker は設計§5の代表的な音声・動画形式を候補に出し、対応外も選べる (*.* = 明示失敗へ辿り着ける)。 */
    @Test
    void pickerCoversAudioAndVideoAndStillAllowsUnsupportedPicks() {
        final List<String> patterns = List.of(LocalImportDecisions.PICKER_PATTERNS);
        for (String required : new String[] {
                "*.mp3", "*.wav", "*.flac", "*.mka", "*.ogg", "*.aac",
                "*.mp4", "*.mov", "*.mkv", "*.webm" }) {
            assertTrue(patterns.contains(required), "picker に無い: " + required);
        }
        assertTrue(patterns.contains("*.*"), "対応外形式を選んで明示失敗させる経路が無い");
        assertTrue(patterns.size() > 1, "MP3 専用 picker へ戻っていない");
    }

    /** 表示名は末尾の拡張子を種類に関係なく 1 個だけ外す。MP3 特別扱いが無いこと。 */
    @Test
    void displayNameStripsAnyExtensionNotJustMp3() {
        assertEquals("song", LocalImportDecisions.displayName("song.mp3"));
        assertEquals("live show", LocalImportDecisions.displayName("live show.mp4"));
        assertEquals("clip", LocalImportDecisions.displayName("clip.mkv"));
        assertEquals("tone", LocalImportDecisions.displayName("tone.WAV"));
        assertEquals("noext", LocalImportDecisions.displayName("noext"));
        assertEquals("a.tar", LocalImportDecisions.displayName("a.tar.gz"));
        assertEquals("file.name", LocalImportDecisions.displayName("file.name.unknown9"));
    }

    /** 失敗の detail (normalizer の固定文言) が利用者向け status に分かれる。 */
    @Test
    void failureStatusMapsNormalizerDetails() {
        assertEquals("too_large", statusOf("input file exceeds the size limit"));
        assertEquals("too_long", statusOf("input exceeds the duration limit"));
        assertEquals("unsupported", statusOf("unsupported or undecodable input file"));
        assertEquals("unsupported", statusOf("could not inspect the input file"));
        assertEquals("unsupported", statusOf("no decodable audio"));
        assertEquals("unsupported", statusOf("input file is empty"));
        assertEquals("unavailable", statusOf("opus encoder is unavailable"));
        assertEquals("unavailable", statusOf("no-overwrite publish is not supported here"));
        assertEquals("select", statusOf("interrupted"));
        assertEquals("import_failed", statusOf("output file already exists"));
        assertEquals("import_failed", statusOf("normalization failed"));
        // publish の I/O 失敗 (disk full・hard link 不可の FS 等) は非対応ではなく取込失敗。
        assertEquals("import_failed", statusOf("could not publish the normalized file"));
    }

    /** 取消 (interrupt) は失敗表示ではなく idle へ戻る。原因チェーン経由でも拾う。 */
    @Test
    void failureStatusTreatsInterruptionAsCancel() {
        assertEquals("select", LocalImportDecisions.failureStatus(new InterruptedIOException("interrupted")));
        assertEquals("select", LocalImportDecisions.failureStatus(
                new IOException("wrapped", new InterruptedException())));
        assertEquals("import_failed", LocalImportDecisions.failureStatus(new IOException("disk")));
    }

    private static String statusOf(String detail) {
        return LocalImportDecisions.failureStatus(new ResolveException(FailureReason.UNKNOWN, detail));
    }
}
