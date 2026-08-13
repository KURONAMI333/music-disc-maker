package com.kuronami.musicdiscmaker.client.audio;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.network.UrlGuard;

/**
 * 再生が始まらなかった理由の<b>分類</b>だけを取り出した純ロジック (MC 非依存)。
 *
 * <h2>これが要る理由</h2>
 * ストリームを開く経路 ({@code ClientAudioStreams#open}) は、失敗の種類を
 * <b>すべて {@code null} に潰して</b>返す。呼び出し側はどれも {@code resolved == null} としか
 * 読めないので、利用者に出せるのは「鳴りませんでした」の 1 文だけになる。実際、
 * 「再生中と出るのに鳴らない」「自分だけ無音」の報告に対して、client 側には理由を答える材料が
 * 何も残っていなかった。
 *
 * <p>ストリームは client ごとに開くので、同じ音源でも<b>片方の client だけ</b>失敗しうる
 * (DNS・回線・ガード判定・同時再生上限はすべてローカル)。だからこそ理由は
 * <b>その client の画面とログの両方</b>に残す必要がある ({@link PlaybackFailureReport})。
 *
 * <p>分類はここに閉じてある。{@code Minecraft} を掴まないので headless テストに載る
 * ({@code PlaybackSessions} / {@code PlaybackPositions} と同じ seam の切り方)。
 *
 * @param kind   利用者に見せる粒度の分類
 * @param detail ログ・チャットの角括弧に出す技術詳細 (空文字なら detail 無し)
 */
public record PlaybackFailure(Kind kind, String detail) {

    /** 原因のたどり方の上限 (循環した cause chain で回り続けないための歯止め)。 */
    private static final int MAX_CAUSE_DEPTH = 12;
    /** チャット 1 行に載せる技術詳細の上限。 */
    private static final int MAX_DETAIL_CHARS = 160;

    /** 利用者に見せる粒度の分類。 */
    public enum Kind {
        /** ストリームを開けなかった (ローダーが理由なしで null を返した)。 */
        STREAM_UNAVAILABLE("stream"),
        /** SSRF ガードが URL を拒否した (scheme 違反・解析不能・内部 IP)。 */
        BLOCKED_URL("blocked"),
        /** ホストへ到達できなかった (DNS 解決失敗・接続タイムアウト・切断)。 */
        NETWORK("network"),
        /** それ以外の例外 (デコード・ローダー内部)。 */
        OPEN_ERROR("error"),
        /** 同時再生上限に達したのでこの再生を起こさなかった。 */
        CONCURRENT_LIMIT("limit");

        private final String suffix;

        Kind(String suffix) {
            this.suffix = suffix;
        }

        /**
         * 表示文の翻訳キー。<b>enum 名の機械変換ではない</b>ので、呼び出し側で
         * {@code name().toLowerCase()} を書かないこと (存在しないキーを引いて画面に生キーが出る。
         * {@code ResolveFailureText} と同じ規律)。
         */
        public String translationKey() {
            return "music_disc_maker.playback_failed.reason." + suffix;
        }
    }

    /** ローダーが理由なしで {@code null} を返した。 */
    public static PlaybackFailure streamUnavailable() {
        return new PlaybackFailure(Kind.STREAM_UNAVAILABLE, "");
    }

    /**
     * SSRF ガードの拒否。{@link UrlGuard.Reason#UNRESOLVABLE} だけは
     * 「URL が悪い」ではなく「この client からホストが引けない」なので {@link Kind#NETWORK} に振る
     * (片方の client だけ無音になる典型がここ)。
     */
    public static PlaybackFailure blocked(@Nullable UrlGuard.Reason reason) {
        final UrlGuard.Reason r = reason == null ? UrlGuard.Reason.INVALID_URL : reason;
        final Kind kind = r == UrlGuard.Reason.UNRESOLVABLE ? Kind.NETWORK : Kind.BLOCKED_URL;
        return new PlaybackFailure(kind, r.name());
    }

    /** 同時再生上限で起こさなかった。 */
    public static PlaybackFailure concurrentLimit(int limit) {
        return new PlaybackFailure(Kind.CONCURRENT_LIMIT, "limit=" + limit);
    }

    /**
     * 投げられた例外から分類する。ローダーは隔離 classloader の中に居て独自の例外型を投げるので、
     * JDK 型の {@code instanceof} だけでは足りない。クラス名でも拾う。
     */
    public static PlaybackFailure thrown(@Nullable Throwable thrown) {
        if (thrown == null) {
            return new PlaybackFailure(Kind.OPEN_ERROR, "");
        }
        final Kind kind = isNetworkFailure(thrown) ? Kind.NETWORK : Kind.OPEN_ERROR;
        return new PlaybackFailure(kind, describe(rootCause(thrown)));
    }

    /** 原因の連鎖のどこかがネットワーク由来か。 */
    private static boolean isNetworkFailure(Throwable thrown) {
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

    /** 連鎖の末端 (循環していたら見た範囲の末端)。 */
    private static Throwable rootCause(Throwable thrown) {
        Throwable current = thrown;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            final Throwable next = current.getCause();
            if (next == null || next == current) {
                return current;
            }
            current = next;
        }
        return current;
    }

    /** 1 行に載る長さへ畳んだ技術詳細。 */
    private static String describe(Throwable thrown) {
        String text = thrown.toString();
        if (text == null || text.isBlank()) {
            text = thrown.getClass().getName();
        }
        text = text.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() <= MAX_DETAIL_CHARS ? text : text.substring(0, MAX_DETAIL_CHARS) + "…";
    }

    /** 表示文の翻訳キー。 */
    public String translationKey() {
        return kind.translationKey();
    }

    /** ログ・チャットの角括弧に出す機械可読なラベル (利用者がそのまま報告に貼れる)。 */
    public String label() {
        return detail == null || detail.isBlank() ? kind.name() : kind.name() + " " + detail;
    }
}
