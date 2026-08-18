package com.kuronami.musicdiscmaker.lavaplayer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「YouTube で鳴らせなかった曲」と「別のソースで見つかった候補」が<b>同じ録音か</b>を判定する。
 *
 * <h2>なぜ厳しくするか</h2>
 * 検索が返すのはカバー・ライブ版・リミックス・ナイトコア・作業用 BGM のまとめを含む雑多な列で、
 * 上位に来るからといって同じ録音とは限らない (2026-08-18 実測: {@code scsearch:} で
 * 「Rick Astley Never Gonna Give You Up」を引くと 10 件中 4 件がカバー / bootleg /
 * nightcore / remix だった)。<b>違う曲が鳴るのは、鳴らないより悪い</b>ので、迷ったら落とす。
 *
 * <h2>判定の三段</h2>
 * <ol>
 * <li><b>版の目印</b> — 括弧の中に {@code cover} / {@code live} / {@code remix} 等が現れるか。
 *     両側で<b>一致しなければ</b>落とす (片側だけに付いている = 別の版)</li>
 * <li><b>曲名</b> — 正規化した語の集合が<b>完全に一致</b>すること。部分一致を許すと
 *     「Sexy And I Know It, Sorry For Party Rocking, Party Rock Anthem」のような寄せ集めが通る</li>
 * <li><b>アーティスト</b> — 正規化した語が<b>先頭から噛み合う</b>こと (客演の足し引きは許すが、
 *     主が入れ替わったら落とす)。どちらかが空なら落とす (照合する材料が無い = 判定できない)</li>
 * <li><b>尺</b> — 元の尺が取れているなら釣り合っていること ({@link #durationFits})</li>
 * </ol>
 *
 * <p>入力は<b>生のメタ情報</b>を渡す。整形は {@link MetadataCleaner} に任せる
 * (「Artist - Song」の分割・{@code (Official Video)} 等の除去・VEVO の接尾辞は既にそこにある)。
 * これにより、SoundCloud の再アップロード
 * (曲名 {@code "Rick Astley - Never Gonna Give You Up"} / 投稿者 {@code "pajlada"}) からも
 * 本来のアーティスト名が取り出せる。
 */
public final class TrackMatch {

    /**
     * 版を表す目印。<b>括弧の中に現れた時だけ</b>目印とみなす。
     *
     * <p>括弧に限るのは、これらの語が曲名そのものに含まれうるため
     * ({@code "Live and Let Die"} / {@code "Cover Me"})。実際の付き方は括弧か角括弧で、
     * {@code "( Rick Astley Cover )"} / {@code "[Nightcore]"} / {@code "[slowed + reverbed]"}
     * / {@code "(Daymaan Remix)"} のように現れる (2026-08-18 実測)。
     *
     * <p>括弧の外に付いた版違い ({@code "... Live at Budokan"}) はここでは拾えないが、
     * 語が増えるぶん曲名の集合一致が落とす。
     */
    private static final String[] VARIANT_MARKERS = {
        "cover", "live", "remix", "rmx", "bootleg", "mashup", "karaoke", "instrumental",
        "acoustic", "unplugged", "nightcore", "slowed", "reverb", "reverbed", "sped up",
        "spedup", "speed up", "8d", "off vocal", "demo", "flip", "edit", "vip", "rework",
        "reprise", "orchestral", "piano", "extended", "radio edit", "session",
        "カバー", "歌ってみた", "弾いてみた", "演奏してみた", "ライブ", "ライヴ", "リミックス",
        "インスト", "アコースティック", "オフボーカル", "生演奏",
    };

    /**
     * 照合から落とす語。表記ゆれだけを生んで内容を持たない語に限る
     * ({@code "Rock & Roll"} と {@code "Rock and Roll"} を同じにするため)。
     * 内容語 ({@code "video"} {@code "the"} 等) は落とさない — 曲名そのものでありうる。
     */
    private static final Set<String> IGNORED_WORDS =
            Set.of("and", "feat", "featuring", "ft", "with", "prod", "vs");

    /** 尺の下限 (元に対する比)。これを下回る候補は抜粋・試聴版とみなす。 */
    private static final double MIN_DURATION_RATIO = 0.5d;
    /** 尺の上限 (元に対する比)。これを超える候補は寄せ集め・ループとみなす。 */
    private static final double MAX_DURATION_RATIO = 3.0d;

    private TrackMatch() {
    }

    /**
     * 生のメタ情報どうしを突き合わせて、同じ録音とみなせるかを返す。
     *
     * @param sourceTitle     元 (YouTube 等) の生タイトル
     * @param sourceAuthor    元の生アーティスト / チャンネル名
     * @param candidateTitle  候補の生タイトル
     * @param candidateAuthor 候補の生アーティスト
     * @return 同じ録音とみなせるなら {@code true}
     */
    public static boolean sameRecording(String sourceTitle, String sourceAuthor,
            String candidateTitle, String candidateAuthor) {
        if (blank(sourceTitle)) {
            return false;
        }
        final String[] source = MetadataCleaner.clean(sourceTitle, sourceAuthor);
        return sameRecordingFromCleanSource(sourceTitle, source[0], source[1],
                candidateTitle, candidateAuthor);
    }

    /**
     * 元の側の曲名・アーティストが<b>既に分かっている</b>時の照合。
     *
     * <p>Spotify 経路がこちらを使う — og タグから曲名とアーティストを別々に取っているので、
     * {@link MetadataCleaner} の「Artist - Song」分割をかけると<b>壊れる</b>
     * ({@code "Bohemian Rhapsody - Remastered 2011"} はアーティスト
     * {@code "Bohemian Rhapsody"} ・曲名 {@code "Remastered 2011"} になる)。
     *
     * @param markerTitle     版の目印を探す元のタイトル
     * @param sourceTitle     整形済みの曲名
     * @param sourceAuthor    整形済みのアーティスト
     * @param candidateTitle  候補の生タイトル
     * @param candidateAuthor 候補の生アーティスト
     * @return 同じ録音とみなせるなら {@code true}
     */
    public static boolean sameRecordingFromCleanSource(String markerTitle, String sourceTitle,
            String sourceAuthor, String candidateTitle, String candidateAuthor) {
        if (blank(sourceTitle) || blank(candidateTitle)) {
            return false;
        }
        if (!variantMarkers(markerTitle).equals(variantMarkers(candidateTitle))) {
            return false;
        }
        final String[] candidate = MetadataCleaner.clean(candidateTitle, candidateAuthor);
        if (blank(sourceAuthor) || blank(candidate[1])) {
            // 照合する材料が片側に無い。曲名だけの一致で通すと、同名異曲やカバーが素通りする。
            return false;
        }
        final Set<String> sourceWords = words(sourceTitle);
        final Set<String> candidateWords = words(candidate[0]);
        if (sourceWords.isEmpty() || !sourceWords.equals(candidateWords)) {
            return false;
        }
        return artistsAgree(wordList(sourceAuthor), wordList(candidate[1]));
    }

    /**
     * アーティスト名が噛み合っているか。<b>片方がもう片方の先頭から始まっている</b>ことを見る。
     *
     * <h2>「含む」で判定してはいけない (2026-08-18 実測)</h2>
     * 集合の包含で通すと、<b>マッシュアップが素通りする</b>。
     * {@code "Alan Walker - Faded"} を探した時に {@code "2Pac Ft. Alan Walker - Faded"} が
     * 返ってきた ({@code soundcloud.com/jamiegos/2pac-ft-alan-walker-faded-jamie-gos-remix})。
     * 語の集合では {@code {alan, walker}} が {@code {2pac, alan, walker}} に含まれるので通ってしまうが、
     * これは別の録音。
     *
     * <p>{@code "Daft Punk"} に対する {@code "Daft Punk feat. Romanthony"} との違いは<b>並び</b>にある
     * — 客演が後ろに足された時は先頭が一致し、マッシュアップで別のアーティストが主になった時は
     * 先頭から食い違う。
     *
     * @param source    元のアーティスト名の語 (並び順のまま)
     * @param candidate 候補のアーティスト名の語 (並び順のまま)
     * @return 噛み合っていれば {@code true}
     */
    private static boolean artistsAgree(List<String> source, List<String> candidate) {
        if (source.isEmpty() || candidate.isEmpty()) {
            return false;
        }
        final int shared = Math.min(source.size(), candidate.size());
        for (int i = 0; i < shared; i++) {
            if (!source.get(i).equals(candidate.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 候補の尺が元と釣り合っているか。
     *
     * <h2>幅の決め方 (2026-08-18 に 8 曲で実測した比から)</h2>
     * 落としたいのは<b>レーベルが上げる 1:30 の販促版</b>。2 件出た —
     * Interscope の「LMFAO - Sorry For Party Rocking」(90秒 / 元 7:19 = <b>0.205</b>) と
     * 「OneRepublic - Counting Stars」(90秒 / 元 4:43 = <b>0.320</b>)。
     *
     * <p>通すべき候補の比は <b>0.767 / 0.881 / 0.998 / 1.027</b> だった。元が曲より長いのは普通で
     * (ミュージックビデオは寸劇や間奏を抱える)、Adele「Hello」は MV 6:07 に対して音源 4:41 = 0.767。
     *
     * <p>0.32 と 0.767 の間が空いているので、下限はそこに置く (元の 0.5 倍未満を落とす)。上限は 3.0 倍
     * — 寄せ集めやループを切る。
     *
     * <p><b>それでも分けきれない帯は残る</b>: 販促版はどの曲でもほぼ 90 秒固定なので、元が 3:00 の
     * 動画なら比は 0.5 に届く。<b>迷う帯では鳴らさない側に倒す</b>方針で下限を上に置いてあるが、
     * 幅は粗い足切りに留め、<b>通った候補の中では元の尺に一番近いものを採る</b>
     * ({@code MusicLoaderImpl#substitute})。
     *
     * @param sourceMs    元の尺 (ms)。{@code 0} 以下なら分からない
     * @param candidateMs 候補の尺 (ms)
     * @return 釣り合っていれば {@code true}。元の尺が分からない時は常に {@code true}
     */
    public static boolean durationFits(long sourceMs, long candidateMs) {
        if (sourceMs <= 0L) {
            return true; // 尺が取れない環境で機能を殺さない
        }
        if (candidateMs <= 0L) {
            return false;
        }
        return candidateMs >= sourceMs * MIN_DURATION_RATIO
                && candidateMs <= sourceMs * MAX_DURATION_RATIO;
    }

    /**
     * 尺の釣り合う候補のうち、採るべきものの位置を返す。
     *
     * <p>元の尺が分かっているなら<b>元に一番近いもの</b>を採る。3:30 の曲に対する 1:30 の販促版と、
     * 寸劇つき MV に対する正しい曲は{@link #durationFits 幅だけでは分けられない}ので、
     * 両方が並んだ時にここが正しい方を拾う。
     *
     * <p>元の尺が分からないなら<b>一番長いもの</b>を採る (短縮版はフル尺より短い)。
     *
     * @param candidateMs 候補の尺 (ms) を並び順のまま
     * @param sourceMs    元の尺 (ms)。{@code 0} 以下なら分からない
     * @return 採るべき候補の位置。釣り合うものが無ければ {@code -1}
     */
    public static int bestByDuration(long[] candidateMs, long sourceMs) {
        int best = -1;
        for (int i = 0; i < candidateMs.length; i++) {
            if (!durationFits(sourceMs, candidateMs[i])) {
                continue;
            }
            if (best < 0) {
                best = i;
            } else if (sourceMs > 0L) {
                if (Math.abs(candidateMs[i] - sourceMs) < Math.abs(candidateMs[best] - sourceMs)) {
                    best = i;
                }
            } else if (candidateMs[i] > candidateMs[best]) {
                best = i;
            }
        }
        return best;
    }

    /**
     * タイトルの括弧の中に現れた版の目印を集める。
     *
     * @param title 生タイトル ({@code null} 可)
     * @return 見つかった目印の集合 (無ければ空)
     */
    static Set<String> variantMarkers(String title) {
        final Set<String> found = new HashSet<>();
        if (title == null) {
            return found;
        }
        final String lower = fold(title);
        for (final String segment : bracketed(lower)) {
            for (final String marker : VARIANT_MARKERS) {
                if (containsWord(segment, marker)) {
                    found.add(marker);
                }
            }
        }
        return found;
    }

    /**
     * 括弧の中身を取り出す (閉じていない括弧は行末までを 1 つとみなす)。
     *
     * <p><b>隅付き括弧 {@code 【】} も見る</b>。全角の丸括弧・角括弧は {@link #fold} の NFKC が
     * 半角へ倒すので勝手に入るが、{@code 【】} は倒れないので明示が要る。
     *
     * <p>{@link MetadataCleaner} が {@code 【】} の中の注記を落とすようになったので、
     * ここが同じ括弧を見ないと<b>版の目印だけが片側から消える</b>
     * ({@code 【Official Live Video】} が消えてライブ音源がスタジオ版と一致する)。
     * 注記の除去と目印探しは同じ括弧の集合を見る必要がある。
     * あわせて {@code 【歌ってみた】} のようなカバーの目印も拾えるようになる (2026-08-18 実測)。
     */
    private static Set<String> bracketed(String folded) {
        final Set<String> segments = new LinkedHashSet<>();
        final StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < folded.length(); i++) {
            final char c = folded.charAt(i);
            if (c == '(' || c == '[' || c == '{' || c == '【') {
                depth++;
                continue;
            }
            if (c == ')' || c == ']' || c == '}' || c == '】') {
                if (depth > 0) {
                    depth--;
                    if (depth == 0 && current.length() > 0) {
                        segments.add(current.toString());
                        current.setLength(0);
                    }
                }
                continue;
            }
            if (depth > 0) {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            segments.add(current.toString()); // 閉じ括弧が無いまま終わった分も見る
        }
        return segments;
    }

    /**
     * 照合用に文字列を語の集合へ落とす。全角/半角を揃え、英数字と CJK 以外は区切りとして扱い、
     * 表記ゆれだけを生む語 ({@link #IGNORED_WORDS}) を捨てる。
     *
     * @param text 整形済みの曲名 / アーティスト名
     * @return 語の集合 (順序は持たない)
     */
    static Set<String> words(String text) {
        return new LinkedHashSet<>(wordList(text));
    }

    /**
     * 照合用に文字列を語へ落とす ({@link #words} と同じ規則で、<b>並びを保つ</b>)。
     * アーティスト名は先頭から突き合わせるので順序が要る。
     *
     * @param text 整形済みの曲名 / アーティスト名
     * @return 語 (現れた順)
     */
    static List<String> wordList(String text) {
        final List<String> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        for (final String token : fold(text).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
            if (!token.isEmpty() && !IGNORED_WORDS.contains(token)) {
                result.add(token);
            }
        }
        return result;
    }

    /** 全角/半角と大文字小文字を揃える (NFKC + 小文字化)。 */
    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    /**
     * 語として現れているか。目印は複数語 ({@code "off vocal"}) もあるので、単純な部分一致では
     * なく前後が英数字でないことを見る (CJK の目印は語境界を持たないので部分一致で足りる)。
     */
    private static boolean containsWord(String haystack, String needle) {
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            final int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            final int end = at + needle.length();
            final boolean leftOk = at == 0 || !isWordChar(haystack.charAt(at - 1));
            final boolean rightOk = end >= haystack.length() || !isWordChar(haystack.charAt(end));
            if ((leftOk && rightOk) || !isAscii(needle)) {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    private static boolean isAscii(String text) {
        return text.chars().allMatch(c -> c < 128);
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
