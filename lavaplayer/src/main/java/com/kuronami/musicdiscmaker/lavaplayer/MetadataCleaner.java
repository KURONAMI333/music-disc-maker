package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.List;
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
     * <p>2026-10-09 本人指摘で、表示では次も落とす (実タイトル 690 件の採取に出た形。
     * {@code _work/mdm-310-execution/title-corpus-20261009/}): 【】の見出し全部・ハッシュタグ・
     * 曲を使った作品の紹介 ({@code TVアニメ『X』ノンクレジットOP} / {@code (映画「X」主題歌)})・
     * 投稿者名の再掲 ({@code / THE FIRST TAKE})。作品名の引用を曲名に取り違えない
     * ({@link #quotedSong})。
     *
     * @return [title, author] の表示用配列
     */
    static String[] display(String rawTitle, String rawAuthor) {
        return display(rawTitle, rawAuthor, null);
    }

    /**
     * {@link #display(String, String)} に、動画の説明欄を足した版。題に作品名と OP/ED の紹介しか無く
     * 曲名が書かれていない時 ({@code TVアニメ『彼方から』ノンクレジットエンディング映像}) だけ、説明欄の
     * 先頭から曲名と歌手を探す ({@link #descriptionSong})。説明欄の watch ページは
     * {@link #needsDescription} が真の動画だけ取りに行く (呼び出し側 {@code MusicLoaderImpl})。
     *
     * @param description 同じ動画の説明欄。無ければ null
     */
    static String[] display(String rawTitle, String rawAuthor, String description) {
        final String[] parts = displayParts(rawTitle, rawAuthor, description);
        return parts.length == 2 ? parts : new String[] {parts[0], parts[1]};
    }

    /**
     * 題に曲名が書かれておらず、説明欄があれば曲名を探せる動画か
     * ({@code TVアニメ『X』ノンクレジットOP}・作品名と役割だけの題)。
     */
    static boolean needsDescription(String rawTitle, String rawAuthor) {
        return displayParts(rawTitle, rawAuthor, null).length == 3;
    }

    /** 表示の長さの上限。YouTube の題は 100 字まで。長い入力で整形の正規表現が遅くならないよう切る。 */
    private static final int MAX_DISPLAY_INPUT = 512;

    /** @return [曲名, 歌手]。題に曲名が無く説明欄からも取れなかった時だけ 3 つ目に印 (空文字) */
    private static String[] displayParts(String rawTitle, String rawAuthor, String description) {
        String title = rawTitle == null ? "" : bounded(spaces(rawTitle).strip());
        String author = rawAuthor == null ? "" : bounded(spaces(rawAuthor).strip());
        if (title.isEmpty() && rawTitle != null) {
            title = rawTitle.strip().isEmpty() ? rawTitle : bounded(rawTitle);
        }

        final boolean topic = author.toLowerCase(Locale.ROOT).endsWith("- topic");
        if (topic) {
            author = author.substring(0, author.length() - "- topic".length()).trim();
            return new String[] {stripArtistPrefix(title, author), author};
        }

        author = displayAuthor(cleanAuthor(author));
        final String original = title;
        title = stripDisplayNoise(title);
        title = stripPipeCredit(title, author);
        // 【Ado】うっせぇわのように、投稿者と完全一致する先頭クレジットだけを分離する。
        final Matcher credit = LEADING_CREDIT.matcher(title);
        if (credit.find() && credit.group(1).trim().equalsIgnoreCase(author)) {
            title = title.substring(credit.end()).trim();
        }
        if (title.isBlank()) return new String[] {original, author};
        // 向きの判定はクレジット括弧が消える前に済ませる ({@link #separatorFlips})。
        final boolean flip = separatorFlips(title, author);
        final boolean coverHeading = COVER_HEADING.matcher(title).find();
        final boolean coverCredit = coverHeading || COVER_CREDIT.matcher(title).find();
        final String headed = stripHeadings(title);
        if (!headed.isBlank()) {
            title = headed;
        }

        final String[] song = quotedSong(title, author);
        if (song != null && song.length == 3) {
            // 題に曲名が無い: 説明欄の「オープニングテーマ「曲」歌手」を使う。
            final String[] described = descriptionSong(description);
            if (described != null) {
                return new String[] {described[0], described[1].isEmpty() ? author : described[1]};
            }
            return new String[] {finishDisplay(song[0]), author, ""};
        }
        if (song != null) {
            return new String[] {finishDisplay(song[0]), trimArtist(coverArtist(original, author, song[1]))};
        }
        // 曲名の引用が無い時は、見出しで区切られた最初の部分だけを見る (後ろは所属・企画名)。
        title = firstPart(title);
        final Matcher quoted = QUOTED.matcher(title);
        if (quoted.find() && quoted.group(4) != null) {
            final String artist = trimArtist(quoted.group(1));
            if (!quoted.group(4).isBlank() && !artist.isBlank()) {
                final String suffix = title.substring(quoted.end()).trim();
                return new String[] {finishDisplay(quoted.group(4).trim() + (suffix.isEmpty() ? "" : " " + suffix)),
                        coverArtist(original, author, cleanAuthor(artist))};
            }
        }

        final String[] split = separatorSplit(title, author, flip);
        // Song - Live のような版の注記をアーティスト／曲名と取り違えない。
        if (split != null && !RECORDING_SUFFIX.matcher(split[0]).find()) {
            if (coverHeading) {
                // 【歌ってみた】曲 - 原曲の歌手: 左が曲名、歌っているのは投稿者。
                return new String[] {finishDisplay(coverSong(title)), author};
            }
            return new String[] {finishDisplay(stripUploaderTail(split[0], author)),
                    trimArtist(coverArtist(original, author, cleanAuthor(split[1])))};
        }
        final String[] slash = slashCredit(title, author, coverCredit);
        if (slash != null) {
            return new String[] {finishDisplay(slash[0]), trimArtist(slash[1])};
        }
        title = stripSlashMvSuffix(stripTrailingCredit(title));
        if (title.isBlank()) {
            title = original;
        }
        return new String[] {finishDisplay(title), coverArtist(original, author, author)};
    }

    /** 見出しを落とした跡。区切りとして扱い、表示には残さない。 */
    private static final char CUT = '\uE000';
    /** 動画の見出しに付く括弧。中身は問わず、表示では落とす (2026-10-09 本人指摘)。 */
    private static final Pattern HEADING_BRACKET = Pattern.compile("【([^】]*)】");
    /** 見出しが歌ってみた・カバーを示すか。曲名と原曲の歌手の並びが逆になる。 */
    private static final Pattern COVER_HEADING = Pattern.compile(
            "(?i)【[^】]*(?:歌ってみた|カバー|cover)[^】]*】|[(（\\[［]\\s*cover\\s*[)）\\]］]");
    private static final Pattern COVER_CREDIT = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])cover(?:ed)?\\s+by(?![A-Za-z0-9])|\\scover$");
    private static final Pattern HASHTAG = Pattern.compile("[#＃](?![0-9０-９])[^\\s#＃【】\\[\\]()（）]+");
    private static final Pattern NOTE_TAIL = Pattern.compile("\\s*※.*$");
    private static final Pattern EMPTY_BRACKET = Pattern.compile("[(（\\[［]\\s*[)）\\]］]");
    private static final String WORK_KINDS =
            "TV\\s*アニメ|アニメ|ドラマ|映画|劇場版|日曜劇場|月9|ゲーム|番組|舞台|ミュージカル|朝ドラ|(?<![A-Za-z])CM";
    /** 曲を使った作品の種類。この直後の引用は作品名で、曲名ではない。 */
    private static final Pattern WORK_KIND_BEFORE = Pattern.compile("(?:" + WORK_KINDS + ")\\s*$");
    /** 引用の直後に続けば、その引用は作品名 (主題歌・OP などの紹介)。 */
    private static final Pattern WORK_ROLE_AFTER = Pattern.compile(
            "^\\s*(?:主題歌|挿入歌|テーマ|オープニング|エンディング|ノンクレジット|ノンテロップ|第\\S{1,4}(?:期|クール|話)"
            + "|(?i:season)|篇|編|(?:OP|ED)(?![A-Za-z]))");
    /** 曲がその作品で担う役割 (主題歌・OP 等)。この直後の引用は曲名、直後に続けばその前の引用は作品名。 */
    private static final String ROLES = "主題歌|挿入歌|オープニング|エンディング|テーマ|ノンクレジット|ノンテロップ"
            + "|(?<![A-Za-z])(?:OP|ED)(?![A-Za-z])|(?i:opening|ending|theme\\s+song)";
    private static final Pattern ROLE = Pattern.compile(ROLES);
    private static final Pattern ROLE_BEFORE = Pattern.compile("(?:" + ROLES + ")\\s*[:：]?\\s*$");
    /** 作品の紹介を示す語。括弧や区切りの後ろの部分がこれを含めば、曲の紹介文として落とす。 */
    private static final Pattern TIE_IN = Pattern.compile(
            "主題歌|挿入歌|オープニング|エンディング|テーマ|ノンクレジット|ノンテロップ|" + WORK_KINDS
            + "|(?<![A-Za-z])(?:OP|ED)(?![A-Za-z])|(?i:opening|ending|theme\\s+song)");
    /** 末尾にある作品の紹介 ({@code ／TVアニメ『X』OPテーマ})。後ろに曲名の引用が無い時だけ落とす。 */
    private static final Pattern TIE_IN_TAIL = Pattern.compile(
            "[\\s\u3000/／|｜]*(?:" + WORK_KINDS + ")\\s*[「『｢][^」』｣]+[」』｣][^「『｢]*$");
    /** 動画の種類だけを表す語。括弧の中身・引用の中身・曲名の後ろがこれだけなら落とす。 */
    private static final Pattern MEDIA_ONLY = Pattern.compile(
            "(?i)^[\\s/／・+&-]*(?:(?:official|公式|music|video|audio|lyrics?|visualizer|4k|8k|hd|hq|1080p|60fps"
            + "|m/?v|pv|clip|remaster(?:ed)?|animation|anime|ミュージックビデオ|スペシャル|special|movie|ムービー|full|size"
            + "|フル|ver\\.?|\\d+(?:st|nd|rd|th)|single|album)[\\s/／・+&-]*)+$");
    private static final Pattern MEDIA_TAIL = Pattern.compile(
            "(?i)[\\s:：/／|｜\\-–—]*(?:official\\s+|アニメ|anime\\s+|animation\\s+|スペシャル|special\\s+)?(?:music\\s+video|music\\s+clip"
            + "|lyric\\s+video|video|m/?v|pv|ミュージックビデオ|ムービー|movie)(?:\\s+full(?:\\s+size)?)?[\\s\\-–—]*$");
    private static final Pattern SEPARATED_MEDIA_TAIL = Pattern.compile(
            "(?i)[\\s:：/／|｜\\-–—]+(?:official\\s+)?(?:music\\s+video|music\\s+clip|lyric\\s+video|video|m/?v|pv"
            + "|ミュージックビデオ)(?:\\s+full(?:\\s+size)?)?[\\s\\-–—]*$");
    private static final Pattern ANY_BRACKET = Pattern.compile("[(（\\[［]([^)）\\]］]*)[)）\\]］]");
    private static final Pattern JP_QUOTE = Pattern.compile(
            "「([^」]+)」|『([^』]+)』|｢([^｣]+)｣|“([^”]+)”|\"([^\"]+)\"");
    private static final Pattern ARTIST_EDGE = Pattern.compile(
            "^[\\s\u3000♪×xX:：/／|｜│\\-–—・、,\uE000]+|[\\s\u3000♪×:：/／|｜│\\-–—・、,\uE000]+$");
    private static final Pattern SEGMENT_BREAK = Pattern.compile("[｜|│／/\u3000】」』｣\uE000]");
    private static final Pattern RELEASE_LABEL = Pattern.compile(
            "(?i)\\d+(?:st|nd|rd|th)\\s+(?:single|album)|(?<![A-Za-z0-9])(?:single|album)(?![A-Za-z0-9])"
            + "|\\d+(?:st|nd|rd|th)?\\s*(?:オリジナル)?(?:フル|ミニ)?(?:アルバム|シングル)");

    private static String bounded(String text) {
        return text.length() <= MAX_DISPLAY_INPUT ? text : text.substring(0, MAX_DISPLAY_INPUT);
    }

    /** NBSP 等を普通の空白にそろえる (実タイトルの区切りに NBSP が出る・2026-10-09 採取)。 */
    private static String spaces(String text) {
        return text.replaceAll("[\\u00A0\\u2000-\\u200B\\u202F\\u205F]", " ");
    }

    /** 投稿者名の表示: 【にじさんじ】のような所属の見出しを落とす。 */
    private static String displayAuthor(String author) {
        final String stripped = MULTISPACE.matcher(HEADING_BRACKET.matcher(author).replaceAll(" ")).replaceAll(" ").strip();
        return stripped.isEmpty() ? author : stripped;
    }

    /**
     * 見出しと紹介文を落とす: 【】 (中に曲名の引用があれば中身だけ残す)・ハッシュタグ・※の注記・
     * 作品の紹介を含む括弧・動画の種類だけの括弧・末尾の作品の紹介と動画の種類。
     */
    private static String stripHeadings(String title) {
        final Matcher heading = HEADING_BRACKET.matcher(title);
        final StringBuilder out = new StringBuilder();
        while (heading.find()) {
            final String inner = heading.group(1);
            heading.appendReplacement(out, Matcher.quoteReplacement(
                    inner.contains("「") || inner.contains("『") ? " " + inner + " " : " " + CUT + " "));
        }
        heading.appendTail(out);
        String t = HASHTAG.matcher(out.toString()).replaceAll(" ");
        t = NOTE_TAIL.matcher(t).replaceAll("");
        final Matcher bracket = ANY_BRACKET.matcher(t);
        final StringBuilder kept = new StringBuilder();
        while (bracket.find()) {
            final String inner = bracket.group(1);
            final boolean drop = (TIE_IN.matcher(inner).find() && !hasVersionMarker(inner))
                    || MEDIA_ONLY.matcher(inner).matches();
            bracket.appendReplacement(kept, Matcher.quoteReplacement(drop ? " " : bracket.group()));
        }
        bracket.appendTail(kept);
        t = EMPTY_BRACKET.matcher(kept.toString()).replaceAll(" ");
        final Matcher tieTail = TIE_IN_TAIL.matcher(t);
        if (tieTail.find() && !t.substring(0, tieTail.start()).isBlank()) {
            t = t.substring(0, tieTail.start());
        }
        // 区切りの後ろに独立した動画種別 ("新宝島 -Music Video-") を先に落とし、
        // 「/ 1stアルバムスペシャルMV」型の宣伝尾は、語に付いた動画種別を削る前に丸ごと落とす。
        t = tidy(t);
        final String separated = SEPARATED_MEDIA_TAIL.matcher(t).replaceAll("").strip();
        if (!separated.isEmpty()) {
            t = separated;
        }
        return tidy(cutMediaTail(stripSlashMvSuffix(t)));
    }

    private static String cutMediaTail(String t) {
        final String cut = MEDIA_TAIL.matcher(t).replaceAll("").strip();
        return cut.isEmpty() ? t : cut;
    }

    /** 区切りだけが残った端を整える。 */
    private static String tidy(String t) {
        t = MULTISPACE.matcher(t.replace('\u3000', ' ')).replaceAll(" ");
        t = t.replaceAll("\\s*\uE000(?:\\s*\uE000)*\\s*", " " + CUT + " ");
        t = t.replaceAll("^[\\s\uE000]+|[\\s\uE000｜|\\-–—]+$", "");
        return t.strip();
    }

    /** 見出しの跡で区切られた、最初の空でない部分。 */
    private static String firstPart(String title) {
        for (final String part : title.split(String.valueOf(CUT))) {
            if (!part.isBlank()) {
                return part.strip();
            }
        }
        return title;
    }

    /**
     * 引用 (「」『』｢｣“” "") に入った曲名を選ぶ。作品名の引用
     * ({@code TVアニメ『X』} / {@code 『X』主題歌} / {@code 『X』×歌手「曲」}) は飛ばす。
     * アーティストは、曲名の引用の直前の区切り以降、無ければ直後の短い語句か括弧、
     * それも無ければ引用の中の {@code 曲 / 歌手}、最後に投稿者。
     *
     * <p>曲名の引用が無く作品名の引用だけの時は、作品の種類の語と引用符を外した見出しを返す
     * ({@code TVアニメ『彼方から』ノンクレジットエンディング映像} → {@code 彼方から ノンクレジットエンディング映像})。
     *
     * @return [曲名, アーティスト]。引用が無ければ null
     */
    private static String[] quotedSong(String title, String author) {
        final Matcher m = JP_QUOTE.matcher(title);
        final java.util.ArrayList<int[]> spans = new java.util.ArrayList<>();
        final java.util.ArrayList<String> contents = new java.util.ArrayList<>();
        while (m.find()) {
            spans.add(new int[] {m.start(), m.end()});
            contents.add(firstNonNull(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5)).strip());
        }
        boolean anyWork = false;
        String quotedArtist = "";
        for (int i = 0; i < spans.size(); i++) {
            final String content = contents.get(i);
            final String before = title.substring(i == 0 ? 0 : spans.get(i - 1)[1], spans.get(i)[0]);
            final String after = title.substring(spans.get(i)[1]);
            if (content.isEmpty() || MEDIA_ONLY.matcher(java.text.Normalizer.normalize(content,
                    java.text.Normalizer.Form.NFKC)).matches()) {
                continue;
            }
            if (isWork(i, before, after)) {
                anyWork = true;
                continue;
            }
            if (after.matches("^\\s*[「『｢].*")) {
                // 『歌手』「曲」: 続けて引用が来る時、前の引用は歌手名。
                quotedArtist = content;
                continue;
            }
            String songTitle = content;
            String artist = artistBefore(i == 0 ? before : title.substring(0, spans.get(i)[0]));
            if (artist.isEmpty() && !quotedArtist.isEmpty() && before.isBlank()) {
                artist = quotedArtist;
            }
            if (artist.isEmpty()) {
                artist = artistAfter(after);
            }
            if (artist.isEmpty()) {
                final int slash = content.indexOf(" / ");
                if (slash > 0) {
                    songTitle = content.substring(0, slash).strip();
                    artist = content.substring(slash + 3).strip();
                }
            }
            if (artist.isEmpty()) {
                artist = author;
            }
            return new String[] {songTitle + versionSuffix(after), artist};
        }
        if (anyWork) {
            String t = title.replaceAll("(?:" + WORK_KINDS + ")\\s*(?=[「『｢])", "");
            t = t.replaceAll("[「『｢」』｣]", " ");
            // 3 つ目の要素は「題に曲名が無かった」印。
            return new String[] {firstPart(tidy(t)), author, ""};
        }
        return null;
    }

    private static final int DESCRIPTION_LINES = 25;
    private static final Pattern CREDIT_LINE = Pattern.compile(
            "^\\s*(?:歌|歌唱|唄|アーティスト|artist|vocals?)\\s*[:：]\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    /**
     * 説明欄の先頭から、曲の役割 (オープニングテーマ・主題歌 等) の行と、その行か次の 2 行にある
     * 曲名の引用を探す。歌手は同じ行の引用の前後か、続く「歌：」「アーティスト：」の行。
     * 実物 (2026-10-09 採取): {@code オープニングテーマ：「Unbroken」MADKID} /
     * {@code ♪オープニングテーマ} + {@code CLAN QUEEN「WARNING!!」} /
     * {@code ▼エンディングテーマ} + {@code 「False Love」} + {@code 歌：Ceui}。
     *
     * @return [曲名, 歌手 (無ければ空)]。見つからなければ null
     */
    static String[] descriptionSong(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        final String[] lines = spaces(description).split("\\R");
        final int limit = Math.min(lines.length, DESCRIPTION_LINES);
        for (int i = 0; i < limit; i++) {
            if (lines[i].length() > 120 || !ROLE.matcher(lines[i]).find()) {
                continue;
            }
            for (int j = i; j < Math.min(limit, i + 3); j++) {
                if (lines[j].length() > 300) {
                    continue;
                }
                final String line = lines[j];
                final Matcher quote = JP_QUOTE.matcher(line);
                while (quote.find()) {
                    final String before = line.substring(0, quote.start());
                    final String after = line.substring(quote.end());
                    final String song = firstNonNull(quote.group(1), quote.group(2), quote.group(3),
                            quote.group(4), quote.group(5)).strip();
                    if (song.isEmpty() || song.length() > 100 || WORK_KIND_BEFORE.matcher(before).find()
                            || WORK_ROLE_AFTER.matcher(after).find()) {
                        continue;
                    }
                    String artist = artistBefore(before);
                    if (artist.isEmpty()) {
                        artist = artistAfter(after);
                    }
                    for (int k = j + 1; artist.isEmpty() && k < Math.min(limit, j + 3); k++) {
                        final Matcher credit = CREDIT_LINE.matcher(lines[k]);
                        if (credit.find()) {
                            artist = artistCandidate(credit.group(1));
                        }
                    }
                    return new String[] {song, trimArtist(artist)};
                }
            }
        }
        return null;
    }

    /**
     * 引用が作品名か。直前が作品の種類の語、直後が主題歌・OP 等の紹介、
     * 直後から次の区切りまでに紹介の語がある、または「作品」×歌手「曲」の前側。
     */
    private static boolean isWork(int index, String before, String after) {
        if (WORK_KIND_BEFORE.matcher(before).find()) {
            return true;
        }
        if (ROLE_BEFORE.matcher(before).find()) {
            // 主題歌「曲」・EDテーマ「曲」
            return false;
        }
        if (index > 0 && before.matches("^\\s*[×xX]\\s*$")) {
            // 歌手「曲」×「作品」の後ろ側
            return true;
        }
        final Matcher cross = Pattern.compile("^\\s*[×xX]\\s*(.*)$", Pattern.DOTALL).matcher(after);
        if (cross.find()) {
            final String rest = cross.group(1).strip();
            // 「曲」×ドラマ『作品』・歌手「曲」×「作品」は曲側、「作品」× 歌手「曲」は作品側。
            return !(rest.isEmpty() || rest.matches("^[「『｢].*")
                    || Pattern.compile("^(?:" + WORK_KINDS + ")").matcher(rest).find());
        }
        if (!artistBefore(before).isEmpty()) {
            // 歌手「曲」主題歌スペシャルムービー: 前に歌手名があれば曲名。
            return false;
        }
        if (WORK_ROLE_AFTER.matcher(after).find()) {
            return true;
        }
        final String segment = after.split("[｜|│／(（【「『｢\"“\uE000]", 2)[0];
        return ROLE.matcher(segment).find();
    }

    /**
     * 曲名の引用の直前で、区切りより後ろの語句。紹介の語の後ろだけを取る
     * ({@code OPテーマ：キタニタツヤ} → {@code キタニタツヤ})。作品の紹介語や発売情報だけなら
     * 1 つ前の区切りまで戻る (2 つまで)。
     */
    private static String artistBefore(String before) {
        String rest = ARTIST_EDGE.matcher(before).replaceAll("");
        for (int round = 0; round < 3 && !rest.isEmpty(); round++) {
            final Matcher seg = SEGMENT_BREAK.matcher(rest);
            int cut = 0;
            while (seg.find()) {
                cut = seg.end();
            }
            final int note = rest.lastIndexOf('♪');
            if (note >= cut) {
                cut = note + 1;
            }
            String candidate = rest.substring(cut);
            final Matcher tie = ROLE.matcher(candidate);
            int tieEnd = -1;
            while (tie.find()) {
                tieEnd = tie.end();
            }
            if (tieEnd >= 0) {
                candidate = candidate.substring(tieEnd);
            }
            candidate = RELEASE_LABEL.matcher(candidate).replaceAll("");
            candidate = cutMediaTail(candidate.strip());
            candidate = ARTIST_EDGE.matcher(candidate.replaceAll("^\\s*(?:映像|ムービー|movie)", "")).replaceAll("").strip();
            if (!candidate.isEmpty() && candidate.length() <= 40 && !MEDIA_ONLY.matcher(candidate).matches()
                    && !WORK_KIND_BEFORE.matcher(candidate).find()
                    && !candidate.matches("^(?:第\\S{1,4}(?:期|クール|話)|(?i:season)\\s*\\d*|.*[「『」』｢｣].*)$")) {
                return candidate;
            }
            if (tieEnd >= 0 && !candidate.isEmpty()) {
                return "";
            }
            rest = ARTIST_EDGE.matcher(rest.substring(0, Math.max(0, cut - 1))).replaceAll("");
            if (rest.matches(".*(?:" + WORK_KINDS + ")\\s*$") || rest.endsWith("』") || rest.endsWith("」")) {
                return "";
            }
        }
        return "";
    }

    /** 曲名の引用の直後のアーティスト: 括弧の中身か、次の区切りまでの短い語句。 */
    private static String artistAfter(String after) {
        final Matcher paren = Pattern.compile("^\\s*[(（]([^)）]+)[)）]").matcher(after);
        if (paren.find()) {
            return artistCandidate(paren.group(1));
        }
        // 「曲」フルver. / 歌手 のように、最初の区切りまでが版や動画の種類なら次へ進む (3 つまで)。
        final String[] chunks = ARTIST_EDGE.matcher(cutMediaTail(after.strip())).replaceAll("")
                .split("\\s*[｜|│/／【\\[(（\uE000]\\s*");
        for (int i = 0; i < Math.min(3, chunks.length); i++) {
            final String chunk = chunks[i].strip();
            final String found = artistCandidate(chunk);
            if (!found.isEmpty()) {
                return found;
            }
            if (!chunk.isEmpty() && !MEDIA_ONLY.matcher(chunk).matches() && !hasVersionMarker(chunk)
                    && !chunk.matches("(?i)^(?:フル|full).*")) {
                return "";
            }
        }
        return "";
    }

    private static String artistCandidate(String raw) {
        String a = ARTIST_EDGE.matcher(cutMediaTail(raw.strip())).replaceAll("").strip();
        a = a.replaceAll("^(?:歌|歌唱|唄|Vo\\.?)\\s*[:：]\\s*|^(?i:by)\\s+", "");
        final String withoutFeature = a.replaceAll("(?i)\\s*(?<![A-Za-z])(?:feat|ft|featuring)\\.?\\s.*$", "");
        if (a.isEmpty() || a.length() > 40 || TIE_IN.matcher(a).find() || MEDIA_ONLY.matcher(a).matches()
                || hasVersionMarker(withoutFeature) || a.matches("(?i)^(?:feat|ft|featuring)\\b.*")
                || a.matches("(?i)^(?:from|at|on|live|ver)(?![A-Za-z0-9]).*") || a.startsWith("📍") || a.startsWith("@")) {
            return "";
        }
        return a;
    }

    /**
     * 曲名の引用の直後に続く版の情報 (「(Live) feat. X」「feat. X」)。直後が版の括弧か客演で
     * 始まる時だけ残す — 後ろは動画の種類・作品の紹介・ローマ字の再掲 ({@code Kenshi Yonezu - Yodaka})・
     * 歌手名が大半。
     */
    private static String versionSuffix(String after) {
        String s = after.split("[｜|│／【\uE000]", 2)[0];
        final Matcher bracket = ANY_BRACKET.matcher(s);
        final StringBuilder kept = new StringBuilder();
        while (bracket.find()) {
            final String inner = bracket.group(1);
            bracket.appendReplacement(kept, Matcher.quoteReplacement(
                    (TIE_IN.matcher(inner).find() && !hasVersionMarker(inner)) || MEDIA_ONLY.matcher(inner).matches()
                            ? " " : bracket.group()));
        }
        bracket.appendTail(kept);
        s = cutMediaTail(kept.toString().strip());
        if (s.isEmpty() || MEDIA_TAIL.matcher(s).replaceAll("").isBlank()) {
            return "";
        }
        final Matcher lead = Pattern.compile("^[(（\\[［]([^)）\\]］]*)[)）\\]］]").matcher(s);
        final boolean versionBracket = lead.find() && hasVersionMarker(lead.group(1));
        final boolean feature = s.matches("(?i)^(?:feat|ft|featuring)\\.?\\s.*");
        return versionBracket || feature ? " " + s : "";
    }

    /**
     * 「曲 / 歌手」型。歌手が投稿者名の再掲なら分ける ({@code Okie Dokie Dance ／ 大空スバル} @
     * {@code Subaru Ch. 大空スバル}・{@code ベテルギウス / THE FIRST TAKE} @ {@code THE FIRST TAKE})。
     * 左が投稿者名の再掲なら「歌手 / 曲」({@code サカナクション / 新宝島})。
     * 歌ってみた・カバーの時は右が原曲の歌手なので、曲名だけ取って歌い手は投稿者にする。
     *
     * @return [曲名, アーティスト]。当てはまらなければ null
     */
    private static String[] slashCredit(String title, String author, boolean cover) {
        if (cover) {
            // 歌ってみたは「曲/原曲の歌手」を空白無しでも書く。
            final String first = title.split("\\s*[/／]\\s*", 2)[0].strip();
            return first.isEmpty() || first.length() == title.length() ? null : new String[] {first, author};
        }
        // 空白の無い / は曲名の一部 ("relax/study"・"24/7"・"W/X/Y") なので区切りにしない。
        final String[] parts = title.split("\\s+/\\s*|\\s*/\\s+|\\s*／\\s*");
        if (parts.length < 2 || parts[0].isBlank() || parts[parts.length - 1].isBlank()) {
            return null;
        }
        final String head = title.substring(0, title.length() - parts[parts.length - 1].length())
                .replaceAll("\\s*[/／]\\s*$", "").strip();
        final String tail = parts[parts.length - 1].strip();
        if (sameName(tail, author)) {
            return new String[] {head, tail};
        }
        if (parts.length == 2 && sameName(parts[0], author)) {
            return new String[] {tail, parts[0].strip()};
        }
        return null;
    }

    /**
     * 名前が投稿者名の再掲か: 客演表記を除いた語が全部、投稿者名の語に含まれる
     * ({@code 大空スバル} ⊂ {@code Subaru Ch. 大空スバル})。1 語が共通なだけでは再掲としない
     * ({@code lofi hip hop radio} と {@code Lofi Girl})。
     */
    private static boolean sameName(String name, String author) {
        final String bare = name.replaceAll("(?i)\\s*(?<![A-Za-z])(?:feat|ft|featuring)(?:\\.\\s*|\\s+).*$", "");
        final List<String> words = TrackMatch.wordList(bare);
        if (words.isEmpty()) {
            return false;
        }
        if (TrackMatch.wordList(author).containsAll(words)) {
            return true;
        }
        // まふまふ ⊂ まふまふちゃんねる: 語の切れ目の無い投稿者名は、名前全体の包含で見る。
        final String compactName = compact(bare);
        return substantial(compactName) && compact(author).contains(compactName);
    }

    /** 曲名の末尾の「/ 投稿者名」({@code カフェ・オ・レ / THE FIRST TAKE}) を落とす。 */
    private static String stripUploaderTail(String text, String author) {
        final Matcher slash = Pattern.compile("\\s+/\\s*|\\s*/\\s+|\\s*／\\s*").matcher(text);
        int start = -1;
        int end = -1;
        while (slash.find()) {
            start = slash.start();
            end = slash.end();
        }
        if (start > 0 && end < text.length() && sameName(text.substring(end), author)) {
            return text.substring(0, start).strip();
        }
        return text;
    }

    /** 歌ってみたの「曲 - 原曲の歌手」から曲名を取る。 */
    private static String coverSong(String title) {
        final Matcher separator = SEPARATOR.matcher(title);
        final String head = separator.find() ? title.substring(0, separator.start()) : title;
        return head.split("\\s*[/／]\\s*", 2)[0].strip();
    }

    private static String trimArtist(String artist) {
        final String raw = artist == null ? "" : artist.strip();
        final String t = ARTIST_EDGE.matcher(raw.replaceAll("[「」『』｢｣]", "")).replaceAll("").strip();
        return t.isEmpty() ? raw : t;
    }

    /** 表示する曲名の仕上げ: 動画の種類だけの末尾と、端に残った区切りを落とす。 */
    private static String finishDisplay(String title) {
        String t = firstPart(tidy(title)).replace(String.valueOf(CUT), " ");
        t = cutMediaTail(t);
        t = t.replaceAll("^[\\s｜|/／:：\\-–—]+|[\\s｜|/／:：\\-–—]+$", "").strip();
        t = MULTISPACE.matcher(t).replaceAll(" ");
        return t.isEmpty() ? tidy(title).replace(String.valueOf(CUT), " ").strip() : t;
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
        "vip", "rework", "radio edit", "single", "album", "ep", "side",
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
     * 構造化クレジット ({@link YoutubeMusicMetadata}) の採用経路からも使う。
     */
    static String stripArtistPrefix(String title, String artist) {
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
     * "A - B" 型が "SONG - ARTIST" (曲名が左) か。
     * 右側の末尾が / 区切りの列挙クレジット括弧で、括弧を除いた右側がチャンネル名と
     * 名を共有する時に限る (例: "ぽ - Omaru Polka【尾丸ポルカ/ホロライブ/4K】"
     * — 2026-09-26 実測)。<b>括弧が消える前のタイトル</b>に掛けること。
     *
     * <p>括弧の中身が実体のある語を持たない時 ("【a/b】") はクレジット括弧とみなさず
     * 反転しない — 通常の "ARTIST - SONG【…】" を誤って入れ替えないため。
     * 括弧を持たない平面の "SONG - ARTIST" は、右側が投稿者名と正規化後に一致する
     * 時だけ反転する (例: "ぽ - Omaru Polka" @ "Omaru Polka")。
     */
    private static boolean separatorFlips(String title, String author) {
        final Matcher separator = SEPARATOR.matcher(title);
        if (!(separator.find() && separator.start() > 0 && separator.end() < title.length())) {
            return false;
        }
        final String right = title.substring(separator.end()).trim();
        final String rightBracketless = stripTrailingCredit(right);
        final String rightCore = stripSlashMvSuffix(rightBracketless);
        if (RECORDING_SUFFIX.matcher(rightCore).find()) {
            return false;
        }
        if (!rightBracketless.equals(right)) {
            // クレジット括弧は中身が実体のある語を持つ時だけ反転の根拠にする
            return namesShare(rightCore, author) && hasSubstantialWord(creditContent(right));
        }
        final String authorCompact = compact(author);
        return !authorCompact.isEmpty() && compact(rightCore).equals(authorCompact);
    }

    /** 末尾の / 列挙クレジット括弧の中身を返す。無い・版目印を含む時は入力のまま。 */
    private static String creditContent(String text) {
        final Matcher matcher = TRAILING_CREDIT.matcher(text);
        if (!matcher.find() || hasVersionMarker(matcher.group(1))) {
            return text;
        }
        return matcher.group(1);
    }

    /** 4字以上の ASCII 語か 2字以上の非 ASCII 語を少なくとも1つ持つか。 */
    private static boolean hasSubstantialWord(String text) {
        for (final String token : TrackMatch.wordList(text)) {
            if (substantial(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 末尾の "|| クレジット" が投稿者名の再掲なら落とす
     * (例: "R x R x R || HAKOS BAELZ" — 2026-09-26 実測)。
     * <b>投稿者名の先頭から語が一致する</b>ことを要求する — 「Sky ⊂ Skylines」のような
     * 部分文字列の包含では削らず、曲名の一部を食わないため。
     */
    private static String stripPipeCredit(String title, String author) {
        final int at = title.indexOf("||");
        if (at < 0) {
            return title;
        }
        final String tail = title.substring(at + 2).trim();
        final String head = title.substring(0, at).trim();
        if (!tail.isEmpty() && !head.isEmpty() && compact(tail).length() >= 4
                && isUploaderRestated(tail, author)) {
            return head;
        }
        return title;
    }

    /**
     * "||" の後が投稿者名の再掲か。再掲側の語列が<b>投稿者名の語列の先頭部分と
     * 一致する</b>時だけ真 ("HAKOS BAELZ" は "Hakos Baelz Ch. hololive" の先頭2語)。
     * 部分語や後続の余分な語を持つ側は再掲とみなさない。
     */
    private static boolean isUploaderRestated(String tail, String author) {
        final List<String> tailWords = TrackMatch.wordList(tail);
        final List<String> authorWords = TrackMatch.wordList(author);
        if (tailWords.isEmpty() || tailWords.size() > authorWords.size()) {
            return false;
        }
        for (int i = 0; i < tailWords.size(); i++) {
            if (!tailWords.get(i).equals(authorWords.get(i))) {
                return false;
            }
        }
        return true;
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
     * 名の一致は<b>語の一致</b>で見る — 1 語の投稿者名が他人名の一部に現れるだけでは
     * 帰属しない ("Someone Famous" ⊃ "Famous" は他人の姓でありうる)。
     */
    private static String coverArtist(String title, String channelAuthor, String artist) {
        final Matcher matcher = COVER_BY.matcher(title);
        if (!matcher.find() || channelAuthor.isEmpty()) {
            return artist;
        }
        final List<String> name = TrackMatch.wordList(matcher.group(1));
        final List<String> channel = TrackMatch.wordList(channelAuthor);
        if (name.isEmpty() || channel.isEmpty()) {
            return artist;
        }
        if (name.equals(channel) || (channel.size() >= 2 && containsWordRun(name, channel))) {
            return channelAuthor;
        }
        return artist;
    }

    /** haystack の中に needle が語の連続として現れるか。 */
    private static boolean containsWordRun(List<String> haystack, List<String> needle) {
        for (int start = 0; start + needle.size() <= haystack.size(); start++) {
            boolean match = true;
            for (int i = 0; i < needle.size(); i++) {
                if (!haystack.get(start + i).equals(needle.get(i))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    /** 括弧の中身に版の目印 (live/remix/mix/edition 等) が現れるか。 */
    private static boolean hasVersionMarker(String bracketContent) {
        final String folded = java.text.Normalizer
                .normalize(bracketContent, java.text.Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        for (final String marker : VERSION_MARKERS_ASCII) {
            // 複数形 ("remixes", "covers", "mixes") も版の目印として拾う — 単数形だけの
            // 照合では "(Remixes/X)" のような括弧がクレジットと取り違えられる。
            if (containsMarkerWord(folded, marker, true)
                    || (!marker.endsWith("s")
                            && (containsMarkerWord(folded, marker + "s", true)
                                    || containsMarkerWord(folded, marker + "es", true)))) {
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
            // display() と同じく "Song - Live" のような版の注記を
            // アーティスト／曲名と取り違えない。
            if (split != null && !RECORDING_SUFFIX.matcher(split[0]).find()) {
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
