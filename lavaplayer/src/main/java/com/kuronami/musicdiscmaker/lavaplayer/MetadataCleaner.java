package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube 等の生メタ情報 (動画タイトル / チャンネル名) を、表示用の「曲名 / アーティスト」に
 * ヒューリスティックで整形する。完全ではないが、よくある型を大きく改善する:
 *
 * <ul>
 *   <li>"〜 - Topic" 自動生成チャンネル: author がアーティスト、title はそのまま</li>
 *   <li>日本語/引用型 "ARTIST「SONG」…" / "ARTIST 'SONG' …": 引用内を曲名、前をアーティストに</li>
 *   <li>"Artist - Song" 型: " - " で分割</li>
 *   <li>(Official Video)[HD](4K Remaster) 等の括弧ノイズ + 末尾の "Official MV" 等を除去</li>
 *   <li>author の "VEVO" / "Official" 接尾辞を除去</li>
 * </ul>
 */
public final class MetadataCleaner {

    private static final Pattern NOISE_BRACKET = Pattern.compile(
            "(?i)\\s*[\\(\\[][^\\)\\]]*\\b(official|video|audio|lyric[s]?|visualizer|hd|hq|4k|8k"
            + "|remaster(ed)?|m/?v|explicit|full\\s*album|color\\s*coded)\\b[^\\)\\]]*[\\)\\]]");
    // 末尾の括弧なしノイズ ("Official Music Video" / "Official MV" / "MV" / "Lyric Video" 等)
    private static final Pattern NOISE_TAIL = Pattern.compile(
            "(?i)[\\s\\-–—|/]*(official\\s+music\\s+video|official\\s+video|official\\s+audio"
            + "|official\\s+mv|music\\s+video|lyric\\s+video|visualizer|\\bmv\\b|\\baudio\\b)\\s*$");
    // ARTIST「SONG」 / ARTIST 'SONG' / ARTIST "SONG" を分解 (前/引用中)
    private static final Pattern QUOTED = Pattern.compile(
            "^(.*?)(?:「(.+?)」|『(.+?)』|['‘](.+?)['’]|[\"“](.+?)[\"”])");
    private static final Pattern VEVO = Pattern.compile("(?i)\\s*VEVO$");
    private static final Pattern AUTHOR_OFFICIAL = Pattern.compile("(?i)\\s*-?\\s*Official$");
    private static final Pattern MULTISPACE = Pattern.compile("\\s{2,}");

    private MetadataCleaner() {
    }

    /** @return [title, author] の整形済み配列。 */
    public static String[] clean(String rawTitle, String rawAuthor) {
        String title = rawTitle == null ? "" : rawTitle.trim();
        String author = rawAuthor == null ? "" : rawAuthor.trim();

        final boolean topic = author.toLowerCase(Locale.ROOT).endsWith("- topic");
        if (topic) {
            author = author.substring(0, author.length() - "- topic".length()).trim();
        }
        author = cleanAuthor(author);

        // 1. 引用型 (ARTIST「SONG」… 等) を最優先で分解
        final Matcher q = QUOTED.matcher(title);
        if (q.find()) {
            final String quoted = firstNonNull(q.group(2), q.group(3), q.group(4), q.group(5));
            if (quoted != null && !quoted.isBlank()) {
                final String before = stripNoise(q.group(1)).trim();
                if (!topic && !before.isBlank()) {
                    author = cleanAuthor(before);
                }
                return finish(quoted.trim(), author, rawTitle);
            }
        }

        // 2. 括弧/末尾ノイズ除去 → "Artist - Song" 分割
        title = stripNoise(title);
        if (!topic) {
            final int idx = title.indexOf(" - ");
            if (idx > 0 && idx < title.length() - 3) {
                final String artistPart = title.substring(0, idx).trim();
                final String songPart = title.substring(idx + 3).trim();
                if (!artistPart.isEmpty() && !songPart.isEmpty()) {
                    author = cleanAuthor(artistPart);
                    title = songPart;
                }
            }
        }
        return finish(title, author, rawTitle);
    }

    private static String stripNoise(String s) {
        if (s == null) {
            return "";
        }
        String t = NOISE_BRACKET.matcher(s).replaceAll("");
        t = NOISE_TAIL.matcher(t).replaceAll("");
        return t.trim();
    }

    private static String cleanAuthor(String a) {
        if (a == null) {
            return "";
        }
        String t = stripNoise(a);                 // 括弧/末尾ノイズ
        t = VEVO.matcher(t).replaceAll("");
        t = AUTHOR_OFFICIAL.matcher(t).replaceAll("");
        return t.trim();
    }

    private static String[] finish(String title, String author, String rawTitle) {
        String t = MULTISPACE.matcher(title).replaceAll(" ").trim();
        if (t.isEmpty()) {
            t = rawTitle == null ? "" : rawTitle.trim();
        }
        return new String[] {t, author};
    }

    private static String firstNonNull(String... values) {
        for (final String v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
