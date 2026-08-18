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
 *   <li>"Artist - Song" 型: 区切り (半角ハイフン / en dash / em dash) で分割</li>
 *   <li>(Official Video)[HD](4K Remaster)［Official Video］ 等の括弧ノイズ + 末尾の "Official MV" 等を除去</li>
 *   <li>曲名側に残った客演表記 ("… ft. X" / "(feat. X)") を除去 — <b>アーティスト側は触らない</b></li>
 *   <li>author の "VEVO" / "Official" 接尾辞を除去</li>
 * </ul>
 */
public final class MetadataCleaner {

    /** 開き括弧。全角も同じ扱いにする ({@link TrackMatch} の版目印探しと同じ集合)。 */
    private static final String OPEN = "[\\(\\[（［【]";
    /** 閉じ括弧 (文字クラスの中身だけ)。{@link #OPEN} と対にして使う。 */
    private static final String CLOSED = "\\)\\]）］】";

    /**
     * 括弧に入った注記。<b>全角の括弧も見る</b> — 実際の oEmbed に
     * {@code ［Official Video］} (Official髭男dism) と {@code （MV）} (imase) が出る (2026-08-18 実測)。
     */
    private static final Pattern NOISE_BRACKET = Pattern.compile(
            "(?i)\\s*" + OPEN + "[^" + CLOSED + "]*\\b(official|video|audio|lyric[s]?|visualizer|hd|hq|4k|8k"
            + "|remaster(ed)?|m/?v|explicit|full\\s*album|color\\s*coded)\\b[^" + CLOSED + "]*[" + CLOSED + "]");
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

    /**
     * アーティストと曲名の区切り。<b>前後に空白のあるハイフン類</b>だけを区切りとみなす。
     *
     * <p>裸のハイフンまで区切りにすると {@code Jay-Z} {@code T-Pain} {@code Spider-Man} が割れる。
     * en dash を入れるのは実際の oEmbed がそれで区切ってくるため —
     * {@code "Queen – Bohemian Rhapsody (Official Video Remastered)"} (2026-08-18 実測)。
     * em dash も同じ形で出る ({@code "AUBREY — Bread (Acoustic Cover…)"})。
     *
     * <p>全角ハイフン ({@code －}) は<b>入れていない</b> — 実測した 388 件のタイトルに現れなかった。
     * 同じ理由で {@code |} {@code ｜} {@code :} {@code ：} も区切りにしない
     * (実測ではまとめ動画の見出しや {@code "Sorry (PURPOSE : The Movement)"} のような
     * 曲名の一部としてしか出ず、区切りに使うと曲名を壊す)。
     */
    private static final Pattern SEPARATOR = Pattern.compile("\\s[-–—]\\s");
    /**
     * 括弧に入った客演表記。曲名の途中にも末尾にも出る
     * ({@code "We Don't Talk Anymore (feat. Selena Gomez) [Official Video]"}・2026-08-18 実測)。
     */
    private static final Pattern FEATURE_BRACKET = Pattern.compile(
            "(?i)\\s*" + OPEN + "\\s*(?:feat|ft|featuring)\\.?\\s[^" + CLOSED + "]*[" + CLOSED + "]");
    /**
     * 括弧の無い客演表記。<b>目印から曲名の終わりまでを丸ごと</b>落とす。
     *
     * <p>客演の名前がどこで終わるかは書式が無いので決められない —
     * {@code "See You Again ft. Charlie Puth Furious 7 Soundtrack"} の
     * {@code Furious 7 Soundtrack} が曲名の続きなのか客演の一部なのか、字面からは分けられない。
     * ここで<b>後ろを全部落とす</b>ので、末尾に別名が付いた曲
     * ({@code "神っぽいな feat. 初音ミク / God-ish"}) はその別名も一緒に消える。
     * 括弧に入った注記は残したいので、そちらは {@link #FEATURE_BRACKET} が別に扱う。
     *
     * <p>{@code w.} を入れるのは実測に出るため
     * ({@code "Post Malone - I Like You (A Happier Song) w. Doja Cat [Official Music Video]"})。
     * <b>点を要求する</b> — 裸の {@code w} まで拾うと {@code "w"} で始まる普通の語を巻き込む。
     *
     * <p>括弧が開いたままの位置では<b>使わない</b> ({@link #stripFeature} が見る)。
     * {@code "Dynasty (Orchestral Version feat. KORK)"} の客演は括弧の注記の一部で、
     * 末尾として落とすと {@code "Dynasty (Orchestral Version"} という開きっぱなしが残る。
     */
    private static final Pattern FEATURE_TAIL = Pattern.compile(
            "(?i)[\\s,]*\\b(?:(?:feat|ft|featuring)\\.?|w\\.)\\s+\\S.*$");

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
                return finish(stripFeature(quoted.trim()), author, rawTitle);
            }
        }

        // 2. 括弧/末尾ノイズ除去 → "Artist - Song" 分割 → 曲名側の客演表記を除去
        title = stripNoise(title);
        if (!topic) {
            final Matcher sep = SEPARATOR.matcher(title);
            if (sep.find() && sep.start() > 0 && sep.end() < title.length()) {
                final String artistPart = title.substring(0, sep.start()).trim();
                final String songPart = title.substring(sep.end()).trim();
                if (!artistPart.isEmpty() && !songPart.isEmpty()) {
                    // 客演表記が前半に付いていたら、それはアーティスト側の情報なので残す。
                    author = cleanAuthor(artistPart);
                    title = songPart;
                }
            }
        }
        return finish(stripFeature(title), author, rawTitle);
    }

    /**
     * 曲名側の客演表記を落とす。<b>アーティスト側には掛けない</b> —
     * 照合はアーティスト名の語を先頭から突き合わせるので、
     * {@code "Daft Punk feat. Romanthony"} の後半を落とすと材料が減る。
     *
     * <p>落とす理由は照合にある。oEmbed が返す
     * {@code "Luis Fonsi - Despacito ft. Daddy Yankee"} は曲名側に {@code ft. Daddy Yankee} を
     * 残すので、{@code Despacito} としか名乗らない候補と語の集合が一致しない (2026-08-18 実測)。
     *
     * @param title 整形途中の曲名
     * @return 客演表記を落とした曲名。全部消えるなら元のまま返す
     */
    private static String stripFeature(String title) {
        if (title == null || title.isBlank()) {
            return title;
        }
        String t = FEATURE_BRACKET.matcher(title).replaceAll("");
        final Matcher tail = FEATURE_TAIL.matcher(t);
        if (tail.find()) {
            final String kept = t.substring(0, tail.start()).trim();
            if (!kept.isEmpty() && bracketsClosed(t.substring(0, tail.start()))) {
                t = kept;
            }
        }
        t = t.trim();
        return t.isEmpty() ? title : t;
    }

    /**
     * そこまでの括弧が全部閉じているか。開いたままなら、その先の客演表記は
     * 括弧の注記の一部なので末尾として落としてはいけない。
     *
     * @param prefix 曲名の先頭からの部分
     * @return 開いたままの括弧が無ければ {@code true}
     */
    private static boolean bracketsClosed(String prefix) {
        int depth = 0;
        for (int i = 0; i < prefix.length(); i++) {
            final char c = prefix.charAt(i);
            if (c == '(' || c == '[' || c == '{' || c == '（' || c == '［' || c == '【') {
                depth++;
            } else if ((c == ')' || c == ']' || c == '}' || c == '）' || c == '］' || c == '】')
                    && depth > 0) {
                depth--;
            }
        }
        return depth == 0;
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
