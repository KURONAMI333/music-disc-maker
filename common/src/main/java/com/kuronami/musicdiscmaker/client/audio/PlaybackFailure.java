package com.kuronami.musicdiscmaker.client.audio;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.network.UrlGuard;

/**
 * 再生が始まらなかった理由の<b>分類</b>だけを取り出した純ロジック (MC 非依存)。
 *
 * <h2>これが要る理由</h2>
 * 失敗は 3 つの形で来る — ガードが投げる拒否、ストリームを開く途中の例外、そして
 * ローダーが返す {@link FailureReason}。どれか 1 つでも取りこぼすと、その経路の失敗は
 * 「鳴りませんでした」の 1 文に潰れる。「再生中と出るのに鳴らない」「自分だけ無音」の報告に
 * 対して、client 側に理由を答える材料が残っている状態を保つのがこの型の役目。
 *
 * <p>{@link #ofReason} が受ける {@link FailureReason} は<b>境界を越えて運ばれてくる</b>
 * ({@code IMusicLoader#openStreamDetailed})。ここを {@code null} 判定 1 つに潰すと、
 * DNS 失敗も年齢制限も YouTube の bot 判定も同じ文面になる。
 *
 * <p>ストリームは client ごとに開くので、同じ音源でも<b>片方の client だけ</b>失敗しうる
 * (DNS・回線・ガード判定・同時再生上限はすべてローカル)。だからこそ理由は
 * <b>その client の画面とログの両方</b>に残す必要がある (画面は {@code GoldenJukeboxScreen}
 * の短い文、ログは {@link PlaybackFailureReport})。
 *
 * <p>分類はここに閉じてある。{@code Minecraft} を掴まないので headless テストに載る
 * ({@code PlaybackSessions} / {@code PlaybackPositions} と同じ seam の切り方)。
 *
 * @param kind   利用者に見せる粒度の分類
 * @param detail ログの角括弧に出す技術詳細 (空文字なら detail 無し)
 */
public record PlaybackFailure(Kind kind, String detail) {

    /** 原因のたどり方の上限 (循環した cause chain で回り続けないための歯止め)。 */
    private static final int MAX_CAUSE_DEPTH = 12;
    /** ログ 1 行に載せる技術詳細の上限。 */
    private static final int MAX_DETAIL_CHARS = 160;

    /** 利用者に見せる粒度の分類。 */
    public enum Kind {
        /** ストリームを開けなかった (ローダーが理由なしで null を返した)。 */
        STREAM_UNAVAILABLE("stream"),
        /** SSRF ガードが URL を拒否した (scheme 違反・解析不能・内部 IP)。 */
        BLOCKED_URL("blocked"),
        /** ホストへ到達できなかった (DNS 解決失敗・接続タイムアウト・切断)。 */
        NETWORK("network"),
        /**
         * 相手は応答したが、要求を通さなかった (401/403/429 以外のステータス番号)。
         * 通信は成立しているので {@link #NETWORK} とは利用者に言うことが逆になる。
         */
        REFUSED("refused"),
        /** それ以外の例外 (デコード・ローダー内部)。 */
        OPEN_ERROR("error"),
        /** 同時再生上限に達したのでこの再生を起こさなかった。 */
        CONCURRENT_LIMIT("limit"),
        /** どの service にも一致しない URL 形式 (対応していないサイト)。 */
        UNSUPPORTED_URL("unsupported"),
        /** 動画が非公開・削除済み・存在しない。 */
        PRIVATE_OR_REMOVED("removed"),
        /** 地域制限。回線でも URL でもなく、その国から再生できない。 */
        REGION_LOCKED("region"),
        /** 年齢制限。 */
        AGE_RESTRICTED("age"),
        /** YouTube の bot 判定でログインを要求された。 */
        BOT_CHECK("bot_check"),
        /** sound engine が再生を受理しなかった (未登録 sound event・チャンネル枯渇・他 MOD の取り消し)。 */
        SOUND_ENGINE("sound_engine"),
        /** 音量が 0 なので engine が捨てた。engine の拒否のうち<b>利用者が自分で直せる</b>唯一のもの。 */
        MUTED("muted"),
        /**
         * 音源が冒頭の試聴版しか配信していない (SoundCloud GO+)。
         * {@link FailureReason#PREVIEW_ONLY} の再生側。
         */
        PREVIEW_ONLY("preview");

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

        /**
         * GUI に出す<b>短い文</b>の翻訳キー ({@link FailureReason#guiKey()} と同じ役割・同じ名前空間)。
         *
         * <p>{@link #translationKey()} と分けてあるのは宛先が違うから。あちらはログとチャットに
         * 出る技術寄りの 1 文で、こちらは画面の帯に折り返して収まる利用者向けの文。
         * 金ジュークの画面に出るのは<b>こちら</b> ({@code GoldenJukeboxScreen})。
         *
         * <p>制作機側と<b>同じキーを共有する</b> — 制作機が「取得できない」と言う理由と、
         * 金ジュークが「鳴らせない」と言う理由は利用者にとって同じ概念なので、
         * 訳語を 2 つ作ると同じ状態が画面ごとに違う言葉で出る。共有できるのは
         * {@link FailureReason} 側に既訳がある 8 つ。
         *
         * <p><b>手の打ちようが無い 3 つ</b> ({@link #STREAM_UNAVAILABLE} / {@link #OPEN_ERROR} /
         * {@link #SOUND_ENGINE}) は 1 つのキーに集約する。分類を分けて持つ意味はログ側にあり
         * ({@link #translationKey()} は 13 種のまま)、画面に「音源なし」「再生エラー」
         * 「再生拒否」と書き分けても<b>利用者が取れる行動は同じで 1 つも無い</b>。
         * 言い分けは切り分けの役に立たず、狭い帯を 3 通りの文で埋めるだけになる。
         *
         * <p>対応表をここに置いて {@code default} を書かないのは {@link FailureReason#guiKey()}
         * と同じ理由 — 分類を 1 つ増やした時に<b>コンパイラに検出させる</b>ため。lang への
         * 追記漏れは {@code PlaybackFailureGameTests#everyKindHasItsGuiLabelInEveryLocale} が、
         * 集約してよい 3 つとそれ以外の区別は {@code everyKindHasItsOwnGuiKey} が見る。
         *
         * @return 翻訳キー
         */
        public String guiKey() {
            return switch (this) {
                // 制作機側に既訳があるもの (FailureReason.guiKey() と同一キー)。
                case UNSUPPORTED_URL -> "gui.music_disc_maker.failed.unsupported";
                case PRIVATE_OR_REMOVED -> "gui.music_disc_maker.failed.private";
                case REGION_LOCKED -> "gui.music_disc_maker.failed.region";
                case AGE_RESTRICTED -> "gui.music_disc_maker.failed.age";
                case NETWORK -> "gui.music_disc_maker.failed.connection";
                case REFUSED -> "gui.music_disc_maker.failed.refused";
                case BLOCKED_URL -> "gui.music_disc_maker.failed.blocked";
                case BOT_CHECK -> "gui.music_disc_maker.failed.botcheck";
                case PREVIEW_ONLY -> "gui.music_disc_maker.failed.preview";
                // 再生時にしか起きないもの (制作機は URL を解決するだけなので相当する語が無い)。
                case CONCURRENT_LIMIT -> "gui.music_disc_maker.failed.limit";
                case MUTED -> "gui.music_disc_maker.failed.muted";
                // 利用者に打つ手が無い 3 つ。制作機の汎用キー gui.music_disc_maker.failed
                // ("取得失敗") には寄せない — あちらは URL を解決できなかった状態で、
                // こちらは解決できたのに鳴らせなかった状態なので、言うことが違う。
                case STREAM_UNAVAILABLE, OPEN_ERROR, SOUND_ENGINE ->
                        "gui.music_disc_maker.failed.generic";
            };
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
     * sound engine が {@code play} を受理しなかった ({@link SoundEngineAcceptance})。
     * 音源のロードは成功しているので、原因は MC 側 (sound event・チャンネル・他 MOD の取り消し)。
     */
    public static PlaybackFailure soundEngineRejected() {
        return new PlaybackFailure(Kind.SOUND_ENGINE, "play rejected");
    }

    /** 音量が 0 なので engine が捨てた ({@link SoundEngineAcceptance})。 */
    public static PlaybackFailure soundMuted() {
        return new PlaybackFailure(Kind.MUTED, "volume=0");
    }

    /**
     * ローダーが返した理由から分類する。
     *
     * <p>対応表をここ 1 箇所に閉じるのは {@link Kind#translationKey()} と同じ理由 —
     * 呼び出し側で {@code FailureReason.valueOf(kind.name())} のような機械変換を書くと、
     * 片方だけ増えた瞬間に黙って {@code UNKNOWN} へ落ちる。
     *
     * @param reason ローダーが返した理由 ({@code null} なら {@link FailureReason#UNKNOWN} 扱い)
     * @param detail 技術詳細 (空なら理由名で補う)
     * @return 分類済みの失敗
     */
    public static PlaybackFailure ofReason(@Nullable FailureReason reason, @Nullable String detail) {
        final FailureReason r = reason == null ? FailureReason.UNKNOWN : reason;
        final Kind kind = kindOf(r);
        // 分類名と理由名が同じ時に "BOT_CHECK BOT_CHECK" と二度書かない。違う時 (CONNECTION_FAILED
        // → NETWORK 等) だけ、ローダー側の言い分をラベルに残す。
        final String text = detail == null || detail.isBlank()
                ? (kind.name().equals(r.name()) ? "" : r.name())
                : detail;
        return new PlaybackFailure(kind, text);
    }

    /** {@link FailureReason} → {@link Kind} の唯一の対応表。 */
    private static Kind kindOf(FailureReason reason) {
        return switch (reason) {
            // 利用者が取れる行動が違うものは分けて出す。
            case UNSUPPORTED_URL -> Kind.UNSUPPORTED_URL;
            case PRIVATE_OR_REMOVED -> Kind.PRIVATE_OR_REMOVED;
            case REGION_LOCKED -> Kind.REGION_LOCKED;
            case AGE_RESTRICTED -> Kind.AGE_RESTRICTED;
            case BOT_CHECK -> Kind.BOT_CHECK;
            // 制作機で弾いた曲でも、既に作られたディスクは再生時にここへ来る (再生も同じ
            // 解決経路を通るため。MusicLoaderImpl#openStreamDetailed)。
            case PREVIEW_ONLY -> Kind.PREVIEW_ONLY;
            // 行動が既存の分類と同じものは寄せる (ガード拒否・回線)。
            case BLOCKED_URL -> Kind.BLOCKED_URL;
            case CONNECTION_FAILED -> Kind.NETWORK;
            case SOURCE_REFUSED -> Kind.REFUSED;
            // 理由を持たない失敗は従来どおりの粒度。
            case UNKNOWN -> Kind.STREAM_UNAVAILABLE;
        };
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

    /** GUI に出す短い文の翻訳キー ({@link Kind#guiKey()})。 */
    public String guiKey() {
        return kind.guiKey();
    }

    /** ログの角括弧に出す機械可読なラベル (利用者がそのまま報告に貼れる)。 */
    public String label() {
        return detail == null || detail.isBlank() ? kind.name() : kind.name() + " " + detail;
    }
}
