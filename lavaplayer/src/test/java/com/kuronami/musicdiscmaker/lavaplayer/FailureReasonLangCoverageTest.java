package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;

/**
 * {@link FailureReason} を 1 つ増やした時に、lang への追記漏れを<b>機械が見つける</b>こと。
 *
 * <h2>なぜ要るか</h2>
 * 理由を増やしてもコンパイラが止めてくれるのは {@code PlaybackFailure.kindOf} の網羅 switch
 * だけで、翻訳ファイルは 14 本ある。1 本でも入れ忘れると、その言語の利用者には
 * <b>生の翻訳キーがそのまま画面に出る</b> (MC は未定義のキーをキー文字列として描く)。
 * 2026-08-21 時点で {@code FailureReason} を網羅するテストは 1 本も無かった
 * ({@code values()} を回していたのは {@code PlaybackFailure.Kind} 側だけ)。
 *
 * <h2>読み方</h2>
 * 翻訳ファイルは {@code common} 側にあり、この層から classpath では見えないので<b>ファイルとして
 * 読む</b>。見つからない・数が足りないは<b>失敗として扱う</b> — 黙って 0 件を検査して緑になると、
 * このテストを足した意味が消える。ロケールは決め打ちせずディレクトリを走査するので、15 番目の
 * 言語を足した時も自動で検査対象に入る。
 */
class FailureReasonLangCoverageTest {

    /** 出荷している翻訳ファイルの数。これを下回ったら、読む場所を間違えたか lang が消えている。 */
    private static final int SHIPPED_LOCALES = 14;

    private static final String LANG_DIR =
            "common/src/main/resources/assets/music_disc_maker/lang";

    @Test
    void everyFailureReasonHasItsGuiLabelInEveryLocale() {
        final List<Path> files = langFiles();
        assertTrue(files.size() >= SHIPPED_LOCALES,
                "翻訳ファイルが " + files.size() + " 本しか見つからない: " + LANG_DIR);
        for (final Path file : files) {
            final String text = read(file);
            for (final FailureReason reason : FailureReason.values()) {
                assertTrue(text.contains('"' + reason.guiKey() + '"'),
                        file.getFileName() + " に " + reason + " のキーが無い: " + reason.guiKey());
            }
        }
    }

    /** 理由ごとに<b>違う</b>キーであること (同じキーを共有すると画面上で区別が消える)。 */
    @Test
    void everyFailureReasonHasItsOwnGuiKey() {
        final Set<String> keys = new HashSet<>();
        for (final FailureReason reason : FailureReason.values()) {
            final String key = reason.guiKey();
            assertTrue(key != null && key.startsWith("gui.music_disc_maker.failed"),
                    reason + " の翻訳キーが規約から外れている: " + key);
            assertTrue(keys.add(key), "翻訳キーが重複している: " + key);
        }
        assertEquals(FailureReason.values().length, keys.size(), "理由の数とキーの数が合わない");
    }

    /** {@code lang} ディレクトリの {@code *.json} を全部。見つからなければ失敗させる。 */
    private static List<Path> langFiles() {
        Path here = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && here != null; up++, here = here.getParent()) {
            final Path dir = here.resolve(LANG_DIR);
            if (Files.isDirectory(dir)) {
                return jsonFiles(dir);
            }
        }
        throw new IllegalStateException(
                "翻訳ファイルの置き場が見つからない (探した起点: "
                        + Path.of("").toAbsolutePath() + " から親へ 6 段): " + LANG_DIR);
    }

    private static List<Path> jsonFiles(Path dir) {
        try (Stream<Path> found = Files.list(dir)) {
            final List<Path> files = new ArrayList<>();
            found.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(files::add);
            return files;
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
