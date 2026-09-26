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
     * 括弧の無い客演表記。<b>目印から次の区切りまで</b>を落とし、その先は残す。
     *
     * <p>客演の名前がどこで終わるかは書式が無いので決められない —
     * {@code "See You Again ft. Charlie Puth Furious 7 Soundtrack"} の
     * {@code Furious 7 Soundtrack} が曲名の続きなのか客演の一部なのか、字面からは分けられない。
     * そこで<b>区切りが出るまで</b>を客演の句とみなす。区切りは {@code /} と開き括弧
     * ({@code (} {@code [} {@code （} {@code ［} {@code 【}) と文末。
     *
     * <p>区切りで止める理由は<b>後ろに別名が続く型</b>があるため。実測 888 件のうち 23 件が
     * {@code "ピノキオピー - 神っぽいな feat. 初音ミク / God-ish"} の形で、
     * 目印から文末まで落とすと英題 {@code God-ish} まで消えていた (2026-08-19 実測)。
     * 開き括弧も区切りに入れるのは {@code "Mithuriya feat. @DILUBeats (මිතුරියක නොවේ ඔබ මගේ)"}
     * のように原語の曲名が括弧で続く型があるため。
     *
     * <p>代わりに<b>曖昧さを 1 つ抱える</b>: {@code "Song feat. A / B"} の {@code B} が
     * 2 人目の客演なのか別名なのかは字面から分けられない。実測ではこの位置に現れたのは
     * 全て別名だったので、別名として残す側に倒してある。
     *
     * <p>{@code w.} を入れるのは実測に出るため
     * ({@code "Post Malone - I Like You (A Happier Song) w. Doja Cat [Official Music Video]"})。
     * <b>点を要求する</b> — 裸の {@code w} まで拾うと {@code "w"} で始まる普通の語を巻き込む。
     *
     * <p>括弧が開いたままの位置では<b>使わない</b> ({@link #stripFeature} が見る)。
     * {@code "Dynasty (Orchestral Version feat. KORK)"} の客演は括弧の注記の一部なので、
     * ここで落とすと注記の中身を書き換えてしまう ({@code "Dynasty (Orchestral Version)"})。
     */
    private static final Pattern FEATURE_TAIL = Pattern.compile(
            "(?i)[\\s,]*\\b(?:(?:feat|ft|featuring)\\.?|w\\.)\\s+\\S[^/\\(\\[（［【]*");

    private MetadataCleaner() {
    }

    /**
     * 動画サイトの生メタ情報を表示用に分ける。
     *
     * <p>{@link #clean} は代替音源の照合にも使うため、Official等の注記や客演表記を落とす。
     * 表示では客演・録音版を残す。動画種別だけの括弧と末尾注記に限って落とし、
     * 曲名の一部か判断できない括弧はそのままにする。Topicの曲名は分割も除去もしない。
     *
     * @return [title, author] の表示用配列
     */
    static String[] display(String rawTitle, String rawAuthor) {
        String title = rawTitle == null ? "" : rawTitle.trim();
        String author = rawAuthor == null ? "" : rawAuthor.trim();

        final boolean topic = author.toLowerCase(Locale.ROOT).endsWith("- topic");
        if (topic) {
            author = author.substring(0, author.length() - "- topic".length()).trim();
            return new String[] {stripArtistPrefix(title, author), author};
        }

        author = cleanAuthor(author);
        final String original = title;
        title = stripDisplayNoise(title);
        title = stripPipeCredit(title, author);
        // 【Ado】うっせぇわのように、投稿者と完全一致する先頭クレジットだけを分離する。
        final Matcher credit = LEADING_CREDIT.matcher(title);
        if (credit.find() && credit.group(1).trim().equalsIgnoreCase(author)) {
            title = title.substring(credit.end()).trim();
        }
        if (title.isBlank()) return new String[] {original, author};

        final Matcher quoted = QUOTED.matcher(title);
        if (quoted.find()) {
            final String song = firstNonNull(
                    quoted.group(2), quoted.group(3), quoted.group(4), quoted.group(5));
            final String artist = quoted.group(1).trim();
            if (song != null && !song.isBlank() && !artist.isBlank()) {
                final String suffix = title.substring(quoted.end()).trim();
                return new String[] {song.trim() + (suffix.isEmpty() ? "" : " " + suffix),
                        coverArtist(title, author, cleanAuthor(artist))};
            }
        }

        final String[] split = separatorSplit(title, author, separatorFlips(title, author));
        // Song - Live のような版の注記をアーティスト／曲名と取り違えない。
        if (split != null && !RECORDING_SUFFIX.matcher(split[0]).find()) {
            return new String[] {split[0], coverArtist(title, author, cleanAuthor(split[1]))};
        }
        title = stripSlashMvSuffix(stripTrailingCredit(title));
        if (title.isBlank()) {
            title = original;
        }
        return new String[] {title, coverArtist(original, author, author)};
    }

    /**
     * 2026-09-22公式oEmbedで確認した動画種別の完全一致だけを除去する。
     * ORIGINAL SONG+MV / ORIGINAL MV / オリジナル曲、Official Video / Music Videoを含む。
     * Live/Cover/Remix/Remasteredや、名前を含む複合見出しはこの集合に入れない。
     */
    private static final Pattern DISPLAY_NOISE_BRACKET = Pattern.compile(
            "(?i)\\s*" + OPEN + "\\s*(?:official(?:\\s+(?:music\\s+video|video|audio|mv))?"
            + "|original\\s+(?:song(?:\\s*\\+\\s*mv)?|mv)|オリジナル曲|mv|pv)\\s*[" + CLOSED + "]");
    private static final Pattern DISPLAY_NOISE_TAIL = Pattern.compile(
            "(?i)\\s+(?:official\\s+(?:music\\s+video|video|audio|mv))\\s*$");
    private static final Pattern LEADING_CREDIT = Pattern.compile("^【([^】]+)】");
    private static final Pattern RECORDING_SUFFIX = Pattern.compile(
            "(?i)^(?:live|remix|cover|remaster(?:ed)?|acoustic|instrumental)(?:\\b|$)");
    /**
     * 末尾の / 区切り列挙括弧。【周防パトラ / ハニスト】や (A/B) のような
     * 投稿者・レーベルのクレジット列挙を対象にする。<b>版の目印を含む括弧は除く</b> —
     * {@code (Live/Acoustic)} や {@code (A/B mix)} は曲の情報なので消さない。
     */
    private static final Pattern TRAILING_CREDIT = Pattern.compile(
            "\\s*" + OPEN + "([^" + CLOSED + "]*[/／][^" + CLOSED + "]*)[" + CLOSED + "]\\s*$");
    /**
     * 末尾の " / 〜MV" 型の宣伝尾 (例: "…クレーター / 1stアルバムスペシャルMV")。
     * 「…スペシャルMV」のように和文に直付きする形も末尾とみなす。
     * {@code \\b} には頼らない — 語境界の文字クラス定義は JDK17 (Unicode 準拠) と
     * JDK21+ (ASCII 基準) で異なり、カタカナ直後の MV が帯で揃わない (2026-09-26 実測)。
     */
    private static final Pattern SLASH_MV = Pattern.compile(
            "(?i)\\s*[/／]\\s*[^/／]*?(?:music\\s+video|mv)\\s*$");
    /**
     * "(Cover by X)" / "(Covered by X)" のクレジット。X が投稿者自身を指す時、
     * アーティスト表示を投稿者 (カバー実行者) に寄せるために使う。
     */
    private static final Pattern COVER_BY = Pattern.compile(
            "(?i)" + OPEN + "[^" + CLOSED + "]*\\bcover(?:ed)?\\s+by\\s+([^" + CLOSED + "]{1,80})["
                    + CLOSED + "]");
    /**
     * 版を表す語 (ASCII)。クレジット列挙の中に現れたら、その括弧は「どの版」の
     * 情報として残す。語の両側を英数字でない境界で囲む ({@link TrackMatch} と同じ規則)。
     */
    private static final String[] VERSION_MARKERS_ASCII = {
        "cover", "live", "remix", "rmx", "mix", "version", "ver", "edit", "edition",
        "deluxe", "demo", "karaoke", "instrumental", "acoustic", "unplugged",
        "nightcore", "slowed", "reverb", "reverbed", "sped up", "spedup", "8d",
        "off vocal", "remaster", "remastered", "feat", "ft", "featuring", "bootleg",
        "mashup", "reprise", "orchestral", "piano", "extended", "session", "flip",
        "vip", "rework", "radio edit", "single", "album", "ep",
    };
    /**
     * 版を表す語 (CJK)。{@link TrackMatch} の照合では部分一致で足りるが、ここでは
     * <b>直前がカタカナなら長い語の一部</b>とみなす — 「ホロライブ」の中の
     * 「ライブ」を目印にしないため (2026-09-26 実測の実タイトル)。
     */
    private static final String[] VERSION_MARKERS_CJK = {
        "カバー", "歌ってみた", "弾いてみた", "演奏してみた", "ライブ", "ライヴ",
        "リミックス", "インスト", "アコースティック", "オフボーカル", "生演奏",
        "バージョン",
    };

    private static String stripDisplayNoise(String title) {
        String cleaned = DISPLAY_NOISE_BRACKET.matcher(title).replaceAll(" ");
        cleaned = DISPLAY_NOISE_TAIL.matcher(cleaned).replaceAll("");
        cleaned = MULTISPACE.matcher(cleaned).replaceAll(" ").trim();
        return cleaned.isEmpty() ? title : cleaned;
    }

    /**
     * Topic 動画の曲名が "アーティスト - 曲名" 形の時、冗長な前置きだけを落とす
     * (例: "Adele - Hello" on "Adele - Topic" → "Hello" — 2026-09-26 実測)。
     * 区切り位置がアーティスト名の直後にある時に限る。
     */
    private static String stripArtistPrefix(String title, String artist) {
        if (artist.isEmpty()) {
            return title;
        }
        final Matcher separator = SEPARATOR.matcher(title);
        if (separator.find() && separator.start() == artist.length()
                && title.regionMatches(true, 0, artist, 0, artist.length())) {
            final String rest = title.substring(separator.end()).trim();
            if (!rest.isEmpty()) {
                return rest;
            }
        }
        return title;
    }

    /**
     * "A - B" の区切りを分解して [曲名, アーティスト] を返す。割れなければ null。
     * {@code flip} が立つ時は "SONG - ARTIST【credit】" 型とみなして左右を入れ替える。
     * 向きの判定は {@link #separatorFlips} に集約する — 呼び出し側が
     * ノイズ除去で括弧が消える前に判定できるよう分けてある。
     */
    private static String[] separatorSplit(String title, String author, boolean flip) {
        final Matcher separator = SEPARATOR.matcher(title);
        if (!(separator.find() && separator.start() > 0 && separator.end() < title.length())) {
            return null;
        }
        final String left = title.substring(0, separator.start()).trim();
        final String right = title.substring(separator.end()).trim();
        final String rightCore = stripSlashMvSuffix(stripTrailingCredit(right));
        if (flip) {
            return new String[] {left, rightCore};
        }
        if (left.isEmpty() || rightCore.isEmpty()) {
            return null;
        }
        return new String[] {rightCore, left};
    }

    /**
     * "A - B" 型が "SONG - ARTIST【credit】" (曲名が左) か。
     * 右側の末尾が / 区切りの列挙クレジット括弧で、括弧を除いた右側がチャンネル名と
     * 名を共有する時に限る (例: "ぽ - Omaru Polka【尾丸ポルカ/ホロライブ/4K】"
     * — 2026-09-26 実測)。<b>括弧が消える前のタイトル</b>に掛けること。
     */
    private static boolean separatorFlips(String title, String author) {
        final Matcher separator = SEPARATOR.matcher(title);
        if (!(separator.find() && separator.start() > 0 && separator.end() < title.length())) {
            return false;
        }
        final String right = title.substring(separator.end()).trim();
        final String rightBracketless = stripTrailingCredit(right);
        final String rightCore = stripSlashMvSuffix(rightBracketless);
        return !rightBracketless.equals(right)
                && !RECORDING_SUFFIX.matcher(rightCore).find()
                && namesShare(rightCore, author);
    }

    /**
     * 末尾の "|| クレジット" が投稿者名の再掲なら落とす
     * (例: "R x R x R || HAKOS BAELZ" — 2026-09-26 実測)。
     */
    private static String stripPipeCredit(String title, String author) {
        final int at = title.indexOf("||");
        if (at < 0) {
            return title;
        }
        final String tail = title.substring(at + 2).trim();
        final String head = title.substring(0, at).trim();
        final String tailCompact = compact(tail);
        final String authorCompact = compact(author);
        if (!tail.isEmpty() && !head.isEmpty() && tailCompact.length() >= 4
                && !authorCompact.isEmpty()
                && (authorCompact.contains(tailCompact) || tailCompact.contains(authorCompact))) {
            return head;
        }
        return title;
    }

    /**
     * 末尾の / 区切り列挙括弧を落とす。版の目印を含む括弧は曲の情報なので残す。
     */
    private static String stripTrailingCredit(String title) {
        final Matcher matcher = TRAILING_CREDIT.matcher(title);
        if (!matcher.find() || hasVersionMarker(matcher.group(1))) {
            return title;
        }
        final String head = title.substring(0, matcher.start()).trim();
        return head.isEmpty() ? title : head;
    }

    private static String stripSlashMvSuffix(String title) {
        final Matcher matcher = SLASH_MV.matcher(title);
        if (!matcher.find()) {
            return title;
        }
        final String head = title.substring(0, matcher.start()).trim();
        return head.isEmpty() ? title : head;
    }

    /**
     * "(Cover by X)" の X が投稿者自身を指す時、アーティストを投稿者に寄せる
     * (例: "Adele - Hello (Cover by Taka from ONE OK ROCK)" on ONE OK ROCK — 実測)。
     * 名の一致は双方向の内包で判断し、部分語一致より厳しくする。
     */
    private static String coverArtist(String title, String channelAuthor, String artist) {
        final Matcher matcher = COVER_BY.matcher(title);
        if (!matcher.find() || channelAuthor.isEmpty()) {
            return artist;
        }
        final String name = compact(matcher.group(1));
        final String channel = compact(channelAuthor);
        if (name.length() >= 4 && !channel.isEmpty()
                && (name.contains(channel) || channel.contains(name))) {
            return channelAuthor;
        }
        return artist;
    }

    /** 括弧の中身に版の目印 (live/remix/mix/edition 等) が現れるか。 */
    private static boolean hasVersionMarker(String bracketContent) {
        final String folded = java.text.Normalizer
                .normalize(bracketContent, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        for (final String marker : VERSION_MARKERS_ASCII) {
            if (containsMarkerWord(folded, marker, true)) {
                return true;
            }
        }
        for (final String marker : VERSION_MARKERS_CJK) {
            if (containsMarkerWord(folded, marker, false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 目印が語として現れるか。ASCII は両側を英数字でない境界に、CJK は
     * 両側をカタカナでない境界に制限する ({@link TrackMatch#containsWord} と
     * 同じ考え方で、CJK 側だけ「ホロライブ」「ライブリー」型の内包を除くよう
     * 厳しくした版)。
     */
    private static boolean containsMarkerWord(String folded, String marker, boolean ascii) {
        int from = 0;
        while (from <= folded.length() - marker.length()) {
            final int at = folded.indexOf(marker, from);
            if (at < 0) {
                return false;
            }
            final int end = at + marker.length();
            final boolean leftOk = at == 0 || !markerAdjacent(folded.charAt(at - 1), ascii);
            final boolean rightOk = end >= folded.length()
                    || !markerAdjacent(folded.charAt(end), ascii);
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    private static boolean markerAdjacent(char c, boolean ascii) {
        return ascii ? Character.isLetterOrDigit(c)
                : Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA;
    }

    /**
     * 2つの名前が少なくとも1つの実質語を共有するか。
     * 誤爆を避けるため ASCII 語は4字以上・非 ASCII 語は2字以上を要求する。
     */
    private static boolean namesShare(String a, String b) {
        final String aCompact = compact(a);
        final String bCompact = compact(b);
        if (aCompact.isEmpty() || bCompact.isEmpty()) {
            return false;
        }
        for (final String token : TrackMatch.wordList(a)) {
            if (substantial(token) && bCompact.contains(token)) {
                return true;
            }
        }
        for (final String token : TrackMatch.wordList(b)) {
            if (substantial(token) && aCompact.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean substantial(String token) {
        return token.length() >= 4
                || (token.length() >= 2 && !token.codePoints().allMatch(cp -> cp < 128));
    }

    private static String compact(String s) {
        return String.join("", TrackMatch.wordList(s));
    }

    /** @return [title, author] の整形済み配列。 */
    public static String[] clean(String rawTitle, String rawAuthor) {
        String title = rawTitle == null ? "" : rawTitle.trim();
        String author = rawAuthor == null ? "" : rawAuthor.trim();

        final boolean topic = author.toLowerCase(Locale.ROOT).endsWith("- topic");
        if (topic) {
            author = author.substring(0, author.length() - "- topic".length()).trim();
            title = stripArtistPrefix(title, author);
        }
        author = cleanAuthor(author);
        final String channelAuthor = author;

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
        // 向き判定は生タイトルで先に済ませる: 【尾丸ポルカ/ホロライブ/4K】のような
        // クレジット括弧は 4K 等を含むため stripNoise で消え、反転の根拠が失われる。
        final boolean flipped = separatorFlips(title, author);
        title = stripNoise(title);
        // 動画種別の見出し (【オリジナル曲】等) と、投稿者名の再掲だけの先頭クレジットは
        // 検索でも曲名にしない — 候補側も同じ規則で整形されるので対称な正規化。
        title = DISPLAY_NOISE_BRACKET.matcher(title).replaceAll(" ").trim();
        title = stripPipeCredit(title, author);
        final Matcher leading = LEADING_CREDIT.matcher(title);
        if (leading.find() && leading.group(1).trim().equalsIgnoreCase(author)) {
            title = title.substring(leading.end()).trim();
        }
        if (!topic) {
            final String[] split = separatorSplit(title, author, flipped);
            if (split != null) {
                // 客演表記が前半に付いていたら、それはアーティスト側の情報なので残す。
                author = cleanAuthor(split[1]);
                title = split[0];
            }
        }
        title = stripSlashMvSuffix(stripTrailingCredit(title));
        author = coverArtist(title, channelAuthor, author);
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
     * <p>落とすのは<b>客演の句だけ</b>で、その後ろに続く別名は残す
     * ({@link #FEATURE_TAIL} が区切りで止まる)。切った跡には空白を 1 つ置き、
     * 二重になった空白は {@link #finish} が畳む。
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
        if (tail.find() && bracketsClosed(t.substring(0, tail.start()))) {
            final String kept =
                    (t.substring(0, tail.start()) + " " + t.substring(tail.end())).trim();
            if (!kept.isEmpty()) {
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
