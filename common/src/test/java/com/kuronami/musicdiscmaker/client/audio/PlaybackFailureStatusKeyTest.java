package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.client.audio.PlaybackFailure.Kind;

/**
 * ブームボックスの 1 行表示 ({@link Kind#statusKey()}) が、出荷する en_us と ja_jp の
 * 両方にあり、英語が 128px の帯に収まる長さであることを固定する。
 *
 * <p>{@link Kind#statusKey()} の網羅性はコンパイルが守るが、翻訳ファイルへの追記漏れは
 * コンパイラに見えない (未定義キーは画面に生のキーとして出る)。既存の
 * {@code everyKindHasItsGuiLabelInEveryLocale} は {@link Kind#guiKey()} だけを見るので、
 * 短い方のキーはここで見る。
 */
class PlaybackFailureStatusKeyTest {

    /** 1 行帯の幅 (px)。等幅でないため文字数の上限は目安。 */
    private static final int MAX_ENGLISH_CHARS = 20;

    private static final Pattern ENTRY =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    void everyKindHasAShortLineInEnglishAndJapanese() throws IOException {
        final Map<String, String> en = load("en_us.json");
        final Map<String, String> ja = load("ja_jp.json");
        for (final Kind kind : Kind.values()) {
            final String key = kind.statusKey();
            assertTrue(key.startsWith("gui.music_disc_maker.status."),
                    kind + " のキーが規約から外れている: " + key);
            assertTrue(en.containsKey(key), "en_us.json に " + kind + " の 1 行表示が無い: " + key);
            assertTrue(ja.containsKey(key), "ja_jp.json に " + kind + " の 1 行表示が無い: " + key);
            final String text = en.get(key);
            assertTrue(text.length() <= MAX_ENGLISH_CHARS,
                    kind + " の英語が " + MAX_ENGLISH_CHARS + " 文字を超えている: " + text);
            assertFalse(text.isBlank(), kind + " の 1 行表示が空になっている");
        }
    }

    /**
     * 共有してよい組み合わせだけがキーを共有すること。利用者に打つ手が無い 3 つ
     * ({@code STREAM_UNAVAILABLE} / {@code OPEN_ERROR} / {@code SOUND_ENGINE}) と、
     * 同じ「リンクが対応外」を指す {@code UNSUPPORTED_URL} / {@code BLOCKED_URL} だけ。
     */
    @Test
    void onlyTheGroupedKindsShareALine() {
        assertEquals(Kind.UNSUPPORTED_URL.statusKey(), Kind.BLOCKED_URL.statusKey());
        assertEquals(Kind.STREAM_UNAVAILABLE.statusKey(), Kind.OPEN_ERROR.statusKey());
        assertEquals(Kind.STREAM_UNAVAILABLE.statusKey(), Kind.SOUND_ENGINE.statusKey());

        final Set<String> distinct = new HashSet<>();
        for (final Kind kind : Kind.values()) {
            distinct.add(kind.statusKey());
        }
        assertEquals(13, distinct.size(), "共有していない分類が増減している: " + distinct);
    }

    private static Map<String, String> load(String file) throws IOException {
        try (InputStream in = PlaybackFailureStatusKeyTest.class.getResourceAsStream(
                "/assets/music_disc_maker/lang/" + file)) {
            assertNotNull(in, file + " が classpath に無い");
            final String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            final Map<String, String> values = new HashMap<>();
            final Matcher matcher = ENTRY.matcher(json);
            while (matcher.find()) {
                values.put(matcher.group(1), matcher.group(2));
            }
            assertFalse(values.isEmpty(), file + " から 1 件も読めなかった");
            return values;
        }
    }
}
