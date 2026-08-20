package com.kuronami.musicdiscmaker.lavaplayer.api;

import java.util.Locale;

/**
 * 例外・エラーメッセージを {@link FailureReason} へ落とす<b>唯一の</b>分類器。
 *
 * <h2>ここに置く理由</h2>
 * 失敗は 2 つの時点で来る — URL を解決する同期経路と、再生スレッドの中で落ちる非同期経路。
 * どちらも投げてくるのは lavaplayer / youtube-source の型で、判別できるのは<b>英語固定の
 * メッセージだけ</b>。分類器を 2 つ持つと、片方だけに新しい語句が足された瞬間に、同じ失敗が
 * 経路によって違う分類になる。だから 1 つに閉じる。
 *
 * <p>置き場が api パッケージなのは、隔離 classloader の側 (lavaplayer impl) と mod 側の
 * 両方から同一クラスとして見える必要があるため。<b>JDK とこのパッケージ以外に依存を
 * 持たせないこと</b> (lavaplayer の型で {@code instanceof} を書けないのはこのため。
 * 判別は文字列だけで行う)。
 *
 * <h2>判別の順序 (2026-08-18 の実測で決めた)</h2>
 * <ol>
 * <li><b>HTTP ステータス</b>。数字は語句を持たないので、語句一致より先に見ないと必ず取り落とす</li>
 * <li>bot 判定 → 年齢 → 地域 → 非公開/削除 の語句一致 (具体的なものから)</li>
 * <li>語句が当たらず、それでもステータス番号を持つなら「相手が要求を通さなかった」</li>
 * <li>どれでもなければ「分類できなかった」。<b>接続失敗はここに落とさない</b> —
 *     本物の回線障害は例外の型で積極的に判別する ({@code hasNetworkCause})</li>
 * </ol>
 */
public final class FailureClassifier {

    /** 原因のたどり方の上限 (循環した cause chain で回り続けないための歯止め)。 */
    private static final int MAX_CAUSE_DEPTH = 12;

    /**
     * 「status code」から数字までの間に許す非数字の文字数。
     * 実測した 3 つの形をすべて覆う長さ ({@code "Status code 403"} /
     * {@code "Not success status code: 403"} / {@code "Invalid status code for playlist response: 400"})。
     * 無制限にすると、メッセージのはるか後ろにある無関係な 3 桁を拾う。
     */
    private static final int STATUS_GAP_LIMIT = 40;

    private FailureClassifier() {
    }

    /**
     * 投げられた例外を分類する。原因の連鎖をたどり、<b>判別できた最も内側の理由</b>を採る。
     *
     * <p>外側の包み紙で確定しないのは、lavaplayer が失敗を必ず定型文
     * ({@code "Connecting to the URL failed."} / {@code "That URL is not playable."} /
     * {@code "Something broke when playing the track."}) で包んで配るため。実際の理由は
     * 連鎖の奥にある。
     *
     * <p>文面がどれにも当たらなかった時だけ、例外の<b>型</b>で回線障害を判別する。逆順にしない —
     * 「ホストに繋がったが 503 を返した」を包む例外がタイムアウト型でありうるので、
     * 型を先に見ると相手の応答が回線障害に化ける。
     *
     * @param thrown 分類したい例外 ({@code null} 可)
     * @return 分類結果。どれにも当たらない失敗は {@link FailureReason#UNKNOWN}
     */
    public static FailureReason classify(Throwable thrown) {
        return resolve(thrown).reason();
    }

    /**
     * 分類の根拠になった例外を返す。<b>利用者に見せる技術詳細をここから採る</b>ための口。
     *
     * <p>lavaplayer は再生スレッドの例外を {@code FriendlyException} で包んで配るので、
     * 一番外側のメッセージは常に定型文になる。それをそのまま詳細として出すと、分類は
     * {@code BOT_CHECK} なのに添えられる文字列はどの失敗でも同じ = 利用者が報告に貼っても
     * 何も伝わらない。判別に使ったのと同じ例外を詳細にも使うことで、分類と文面が食い違わない
     * ようにする。
     *
     * <p>{@link #classify} と<b>同じ 1 回の走査</b>を共有する。別々にたどると、理由を採った
     * 例外と詳細を採った例外が食い違いうる。どれも判別できなければ連鎖の末端を返す
     * (包み紙より、実際に落ちた場所の文面のほうが役に立つ)。
     *
     * @param thrown たどりたい例外 ({@code null} 可)
     * @return 分類の根拠になった例外 ({@code thrown} が {@code null} なら {@code null})
     */
    public static Throwable blamed(Throwable thrown) {
        return resolve(thrown).blamed();
    }

    /** 1 回の走査で決めた「理由」と「その根拠になった例外」。 */
    private record Blame(Throwable blamed, FailureReason reason) {
    }

    /**
     * 連鎖をたどって理由と根拠を 1 組で決める。判別できた枠のうち<b>最も内側</b>を採る
     * (内側で上書きしていくので、最後に残るのが最深の判別結果)。
     */
    private static Blame resolve(Throwable thrown) {
        Throwable current = thrown;
        Throwable deepest = thrown;
        Throwable chosen = null;
        FailureReason chosenReason = FailureReason.UNKNOWN;
        Throwable refused = null;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            final FailureReason reason = classifyMessage(current.getMessage());
            if (isSpecific(reason)) {
                chosen = current;
                chosenReason = reason;
            } else if (reason == FailureReason.SOURCE_REFUSED) {
                refused = current;
            }
            deepest = current;
            final Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        if (chosen != null) {
            return new Blame(chosen, chosenReason);
        }
        if (refused != null) {
            return new Blame(refused, FailureReason.SOURCE_REFUSED);
        }
        return new Blame(deepest,
                hasNetworkCause(thrown) ? FailureReason.CONNECTION_FAILED : FailureReason.UNKNOWN);
    }

    /**
     * 連鎖のどこかが「ホストへ到達できなかった」型か。<b>本物の回線障害だけ</b>を
     * {@link FailureReason#CONNECTION_FAILED} に入れるための判定。
     *
     * <p>文面では判定しない。JDK が出す本文は<b>実行環境のロケールで変わる</b>ので
     * (日本語の JDK は「そのようなホストは不明です。」を出す)、語句マーカーを置いても
     * 環境によって当たらない。型とクラス名なら変わらない。
     *
     * <p>同じ判定が {@code PlaybackFailure#isNetworkFailure} にもある。あちらは client 側で
     * {@code PlaybackFailure.Kind} を決める用で、こちらはローダー側で {@link FailureReason} を
     * 決める用。<b>片方を直したらもう片方も見ること</b> (このクラスは JDK と自パッケージ以外に
     * 依存を持てないので、共有できない)。
     *
     * @param thrown たどりたい例外 ({@code null} 可)
     * @return 到達できなかった型が連鎖に居れば {@code true}
     */
    private static boolean hasNetworkCause(Throwable thrown) {
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof java.net.UnknownHostException
                    || current instanceof java.net.SocketException
                    || current instanceof java.io.InterruptedIOException) {
                // SocketTimeoutException は InterruptedIOException、ConnectException /
                // NoRouteToHostException は SocketException の子なのでここで全部拾える。
                return true;
            }
            final String name = current.getClass().getName();
            if (name.contains("Timeout") || name.contains("UnknownHost")
                    || name.contains("ConnectionClosed") || name.contains("NoHttpResponse")
                    || name.contains("SSLException") || name.contains("SSLHandshake")) {
                return true;
            }
            final Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        return false;
    }

    /**
     * 語句から判別できた分類か。既定値と {@link FailureReason#SOURCE_REFUSED} は含まない。
     *
     * <p>ステータス番号だけを根拠にした {@link FailureReason#SOURCE_REFUSED} を<b>弱い</b>分類として
     * 扱うのがこの述語の役目。集約例外は「番号を返した client」と「理由を語で返した client」を
     * 並べて持つので、番号を対等に扱うと<b>並び順だけで</b>分類が決まり、後ろの client の
     * 年齢制限や非公開が消える (この失敗は実測されていて、行単位の判別はそれを直すために入った)。
     *
     * @param reason 判定したい分類
     * @return 語句で判別できた分類なら {@code true}
     */
    public static boolean isSpecific(FailureReason reason) {
        return reason != FailureReason.UNKNOWN && reason != FailureReason.SOURCE_REFUSED;
    }

    /**
     * エラーメッセージ 1 本を分類する。lavaplayer / YouTube のメッセージは英語固定なので
     * 語句一致で判別する。判別できない失敗は {@link FailureReason#UNKNOWN} として扱う。
     *
     * <p><b>接続失敗をここへ落とさない</b>のは、文字列では本物の回線障害を確かめられないため。
     * lavaplayer が回線障害に付ける包み紙 ({@code "Connecting to the URL failed."}) は
     * 「URL 形式が読めなかった」にも付き、その下の JDK の本文は実行環境のロケールで変わる。
     * 既定値を接続失敗にすると、分類できなかった失敗まで「この端末がホストに到達できない」と
     * 名乗ることになる。
     *
     * <p>語句一致は<b>語境界つき</b>。{@code private} / {@code removed} / {@code deleted} /
     * {@code unavailable} のような単語は技術的な文面に部分文字列として紛れ込みやすい。
     *
     * <p>youtube-source の {@code AllClientsFailedException} は、各 client の失敗理由を
     * <b>自分のメッセージに改行で連結して</b>持つ (「(yts.version: x) All clients failed to load
     * the item.」の後ろに「Client [ANDROID_VR] failed: This video requires login.」が続く)。
     * そこで判別は<b>行単位</b>で行い、語句で判別できた最初の行を採る。全体を 1 本の文字列
     * として見ると、ある client の HTTP ステータスが別の client の理由を打ち消す。
     *
     * <p>ステータス番号だけを根拠にした {@link FailureReason#SOURCE_REFUSED} は<b>弱い</b>分類として
     * 扱い、どの行も語句を持たなかった時にだけ採る ({@link #isSpecific})。番号を対等に扱うと、
     * 行単位にした意味が無くなる (番号を返した client が先に並ぶだけで後ろの理由が消える)。
     *
     * <p>それでも「どの client の理由か」までは分からないので、<b>理由を知りたい呼び出し側は
     * client 単位に切ってからここへ渡す</b> ({@code ClientFailureDetails#classify})。
     * 現行構成は {@code AndroidVr} 単独なので出荷時のリストは常に 1 件。
     *
     * @param message 例外のメッセージ ({@code null} 可)
     * @return 分類結果。判別できない失敗は {@link FailureReason#UNKNOWN}
     */
    public static FailureReason classifyMessage(String message) {
        if (message == null || message.isEmpty()) {
            return FailureReason.UNKNOWN;
        }
        // 行ごとに判別して、語句で判別できた最初の行を採る。集約例外のメッセージは
        // client ごとの理由を改行で連ねた 1 本なので、全体を 1 つの文字列として見ると
        // ある client の HTTP ステータスが別の client の理由を打ち消しうる (実測: 400 を
        // 返した client と "This video is unavailable" を返した client が混ざると、
        // 全体では接続失敗に落ちていた)。
        FailureReason refused = null;
        for (final String line : message.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            final FailureReason reason = classifyLine(line.toLowerCase(Locale.ROOT));
            if (isSpecific(reason)) {
                return reason;
            }
            if (reason == FailureReason.SOURCE_REFUSED && refused == null) {
                refused = reason;
            }
        }
        return refused != null ? refused : FailureReason.UNKNOWN;
    }

    /**
     * 1 行を分類する。判別の順序は {@link #classifyMessage} の javadoc のとおり。
     *
     * @param m 小文字化済みの 1 行
     * @return 分類結果
     */
    private static FailureReason classifyLine(String m) {
        // 1. HTTP ステータス。数字だけの失敗は語句を持たないので、ここで見ないと
        //    「分類できなかった」に落ちる。実測した形:
        //      "Status code 403"                                  (直リンク HTTP の probe)
        //      "Not success status code: 403"                     (再生中のストリーム)
        //      "Invalid status code for playlist response: 400"   (youtube-source の API 呼び)
        final int status = httpStatus(m);
        if (status == 401 || status == 403 || status == 429) {
            // 相手がこの接続を名指しで拒んでいる。利用者が取るべき手 (別のソース / 別の回線) は
            // bot 判定と同じで、「自分の回線が落ちている」ではない。
            // 再試行の扱いも変わらない (MusicLoaderImpl#isRetryable は BOT_CHECK と
            // SOURCE_REFUSED をどちらも再試行対象にしている)。
            return FailureReason.BOT_CHECK;
        }

        // YouTube の bot 判定は「ログイン要求」として現れる (datacenter IP でよく起きる)。
        // youtube-source は "This video requires login." を投げ、UI 文言は "sign in to
        // confirm you're not a bot"。接続失敗ではないので age より前に専用分類する。
        //
        // 実測 (2026-08-18): 年齢制限動画も削除済み動画も同じ "This video requires login." を
        // 返す。AndroidVr 単独では両者を文面で区別できないので、順序を入れ替えても年齢制限を
        // 拾えるようにはならず、bot 判定を落とすだけになる。順序はこのままにする。
        if (hasWord(m, "requires login") || hasWord(m, "sign in to confirm")
                || hasWord(m, "not a bot")) {
            return FailureReason.BOT_CHECK;
        }
        // 年齢固有の語句のみ (bare "age" は message/page 等を誤って拾うため使わない)。
        // youtube-source は "This video requires age verification." を投げる ("requires
        // login" とは別語句なので BOT_CHECK と衝突しない)。
        if (hasWord(m, "confirm your age") || hasWord(m, "verify your age")
                || hasWord(m, "age verification")
                || hasWord(m, "age-restricted") || hasWord(m, "age restricted")
                || hasWord(m, "inappropriate for some users")) {
            return FailureReason.AGE_RESTRICTED;
        }
        if (hasWord(m, "region") || hasWord(m, "country") || hasWord(m, "not available in your")
                || hasWord(m, "blocked it in your")) {
            return FailureReason.REGION_LOCKED;
        }
        // ステータス番号を持つ失敗はここへ落とさない。HTTP の理由句 ("Service Unavailable" /
        // "Gone" 等) がこの語彙とそのまま重なるので、通信層の失敗が「非公開・削除済み」に
        // 化ける。番号が付いている時点で、判別しているのは動画ではなく通信の結果。
        if (status < 0 && (hasWord(m, "private") || hasWord(m, "removed") || hasWord(m, "deleted")
                || hasWord(m, "no longer available") || hasWord(m, "does not exist")
                || hasWord(m, "track is not available")
                || hasWord(m, "unavailable") || hasWord(m, "terminated"))) {
            return FailureReason.PRIVATE_OR_REMOVED;
        }
        // ここまでで語句が当たらず、それでもステータス番号が付いている = 相手は応答して要求を
        // 通さなかった。この判定を最後に置くのは、番号と語句を両方持つ行 (集約例外は
        // "Not success status code: 400" と "This video requires login." を 1 行に並べうる) の
        // 分類を今までどおり語句側に残すため。前へ移すと BOT_CHECK が減り、代替ソース探しの
        // 発火集合 (MDM_DECISIONS D11) が黙って縮む。
        return status >= 0 ? FailureReason.SOURCE_REFUSED : FailureReason.UNKNOWN;
    }

    /**
     * メッセージから HTTP ステータス番号を取り出す。{@code "status code"} の後ろに
     * {@link #STATUS_GAP_LIMIT} 文字以内で現れる最初の 3 桁を採る。
     *
     * @param lower 小文字化済みのメッセージ
     * @return ステータス番号。見つからなければ {@code -1}
     */
    private static int httpStatus(String lower) {
        final String marker = "status code";
        int from = 0;
        while (true) {
            final int at = lower.indexOf(marker, from);
            if (at < 0) {
                return -1;
            }
            final int scanFrom = at + marker.length();
            final int scanTo = Math.min(lower.length(), scanFrom + STATUS_GAP_LIMIT);
            for (int i = scanFrom; i < scanTo; i++) {
                if (Character.isDigit(lower.charAt(i))) {
                    if (i + 3 <= lower.length() && isThreeDigits(lower, i)) {
                        return Integer.parseInt(lower.substring(i, i + 3));
                    }
                    break; // 3 桁でない数字が先に来たらこの marker は諦める
                }
            }
            from = at + marker.length();
        }
    }

    private static boolean isThreeDigits(String text, int at) {
        for (int i = at; i < at + 3; i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return at + 3 >= text.length() || !Character.isDigit(text.charAt(at + 3));
    }

    /**
     * 語境界つきの部分一致。両端が英数字でない位置に現れた時だけ一致とみなす。
     *
     * <p>境界なしの {@code contains} は、たとえば {@code "privatekey"} や
     * {@code "undeleted"} や {@code "regional"} のような技術的な語に当たる。分類が
     * 「動画が非公開」へ化けると、利用者は自分の URL を疑って原因を追えなくなる。
     *
     * @param haystack 小文字化済みのメッセージ
     * @param needle   探す語 (小文字・語の内側の空白やハイフンは問わない)
     * @return 語として現れていれば {@code true}
     */
    private static boolean hasWord(String haystack, String needle) {
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            final int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            final int end = at + needle.length();
            final boolean leftOk = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            final boolean rightOk = end >= haystack.length()
                    || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = at + 1;
        }
        return false;
    }
}
