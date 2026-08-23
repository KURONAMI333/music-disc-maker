package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureClassifier;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;

/**
 * 分類器の判定を<b>実際に採取した例外文</b>で固定する。
 *
 * <h2>入力の出どころ</h2>
 * 特記の無いものはすべて 2026-08-18 に本物の相手から採ったもの (youtube-source 1.18.2 /
 * lavaplayer 2.2.4-fix-j8 / {@code AndroidVr} 単独構成)。作り方は各テストの注に書いてある。
 * <b>文面を creative に書き換えない</b> — 手元でだけ通るテストになる。
 */
class FailureClassifierTest {

    // ---- 実測した例外文 (作り方は各定数の注) ----

    /** 直リンク HTTP が 403 を返した時。ローカルの HttpServer に 403 を返させて採取。 */
    private static final String HTTP_403 = "Status code 403";
    /** 同 429。 */
    private static final String HTTP_429 = "Status code 429";
    /** 同 503。<b>"Service Unavailable" という理由句は付かない</b>。 */
    private static final String HTTP_503 = "Status code 503";
    /** 直リンク HTTP の失敗を包む定型文。 */
    private static final String NOT_PLAYABLE = "That URL is not playable.";
    /** 到達できないホスト / 接続拒否を包む定型文。 */
    private static final String CONNECT_FAILED = "Connecting to the URL failed.";
    /** 到達できないホスト (日本語ロケールの JDK が出す本文をそのまま)。 */
    private static final String UNKNOWN_HOST =
            "そのようなホストは不明です。 (kuronami-does-not-exist-42.invalid)";
    /** 接続拒否。 */
    private static final String REFUSED =
            "Connect to 127.0.0.1:1 [/127.0.0.1] failed: Connection refused: getsockopt";
    /** 存在しない video ID / 非公開動画 / 一部の年齢制限動画。 */
    private static final String VIDEO_UNAVAILABLE = "This video is unavailable";
    /** 削除済み動画と年齢制限動画。<b>両方が同じ文面になる</b>。 */
    private static final String REQUIRES_LOGIN = "This video requires login.";
    /** 存在しない SoundCloud トラック。 */
    private static final String SOUNDCLOUD_MISSING = "This track is not available";
    /** 存在しない playlist。 */
    private static final String PLAYLIST_400 = "Invalid status code for playlist response: 400";
    /** 集約例外のメッセージ全文 (先頭行 + 空行 2 つ + client ごとの理由)。 */
    private static final String AGGREGATE = "(yts.version: 1.18.2) All clients failed to load the item."
            + System.lineSeparator() + System.lineSeparator()
            + "Client [ANDROID_VR] failed: " + VIDEO_UNAVAILABLE;

    /**
     * 再生中のストリームが弾かれた時の文面。<b>これだけは手元で作れなかった</b> —
     * 出どころは youtube-source 1.18.2 の定数プール ({@code YoutubeAudioTrack} が
     * この文字列と {@code equals} で比較している) と、上流 issue #224 に貼られた実ログ。
     */
    private static final String STREAM_403 = "Not success status code: 403";

    // ---- HTTP ステータス ----

    @Test
    void refusedStatusCodesAreNotReportedAsAnOfflineConnection() {
        // before: どれも語句を持たないので CONNECTION_FAILED = 画面に "Offline"。
        assertEquals(FailureReason.BOT_CHECK, FailureClassifier.classifyMessage(HTTP_403));
        assertEquals(FailureReason.BOT_CHECK, FailureClassifier.classifyMessage(HTTP_429));
        assertEquals(FailureReason.BOT_CHECK, FailureClassifier.classifyMessage(STREAM_403));
    }

    @Test
    void serverSideStatusCodesAreReportedAsARefusal() {
        // 期待値の変更 (2026-08-21): 相手が番号を返した時点で通信は成立しているので、
        // 「この端末がホストに到達できない」という接続失敗の文面は嘘になる。SOURCE_REFUSED へ分ける。
        assertEquals(FailureReason.SOURCE_REFUSED, FailureClassifier.classifyMessage(HTTP_503));
        assertEquals(FailureReason.SOURCE_REFUSED, FailureClassifier.classifyMessage(PLAYLIST_400));
    }

    @Test
    void aStatusNumberNeverBecomesPrivateOrRemoved() {
        // HTTP の理由句は非公開/削除の語彙とそのまま重なる。番号が付いている失敗は
        // 動画の状態ではなく通信の結果なので、その語彙に落とさない。
        assertEquals(FailureReason.SOURCE_REFUSED,
                FailureClassifier.classifyMessage("Status code 503 Service Unavailable"));
        assertEquals(FailureReason.SOURCE_REFUSED,
                FailureClassifier.classifyMessage("Not success status code: 410 Gone, resource removed"));
    }

    @Test
    void aReasonPhraseStillWinsOverTheStatusNumberOnTheSameLine() {
        // 番号の判定は語句の後ろに置いてある。ここが前へ動くと bot 判定が SOURCE_REFUSED に
        // 化けて、代替ソース探しの発火集合 (MDM_DECISIONS D11) が黙って縮む。
        assertEquals(FailureReason.BOT_CHECK,
                FailureClassifier.classifyMessage("Not success status code: 400. " + REQUIRES_LOGIN));
    }

    @Test
    void aThreeDigitNumberElsewhereIsNotReadAsAStatus() {
        assertEquals(FailureReason.PRIVATE_OR_REMOVED,
                FailureClassifier.classifyMessage("This video is unavailable (404 views)"));
    }

    // ---- 語句 ----

    @Test
    void youtubeContentReasonsKeepTheirClassification() {
        assertEquals(FailureReason.PRIVATE_OR_REMOVED,
                FailureClassifier.classifyMessage(VIDEO_UNAVAILABLE));
        assertEquals(FailureReason.BOT_CHECK, FailureClassifier.classifyMessage(REQUIRES_LOGIN));
        assertEquals(FailureReason.PRIVATE_OR_REMOVED, FailureClassifier.classifyMessage(AGGREGATE));
    }

    @Test
    void aStatusOnOneClientDoesNotSuppressAnotherClientsReason() {
        // 集約例外のメッセージは client ごとの理由を改行で連ねた 1 本。全体を 1 つの文字列と
        // して見ると、先頭の client の 400 が後ろの client の "unavailable" を打ち消していた。
        final String blob = "(yts.version: 1.18.2) All clients failed to load the item."
                + System.lineSeparator() + System.lineSeparator()
                + "Client [ANDROID_VR] failed: java.io.IOException: " + PLAYLIST_400
                + System.lineSeparator() + System.lineSeparator()
                + "Client [WEB] failed: " + VIDEO_UNAVAILABLE;
        assertEquals(FailureReason.PRIVATE_OR_REMOVED, FailureClassifier.classifyMessage(blob));
    }

    @Test
    void aMissingSoundcloudTrackIsNotAnOfflineConnection() {
        // before: どの語句にも当たらず CONNECTION_FAILED = 画面に "Offline"。
        assertEquals(FailureReason.PRIVATE_OR_REMOVED,
                FailureClassifier.classifyMessage(SOUNDCLOUD_MISSING));
    }

    /**
     * 期待値の変更 (2026-08-21): これらはどれも<b>積極的に接続失敗と判別されていたのではなく、
     * 既定値に落ちていただけ</b>だった。既定値が「この端末がホストに到達できない (回線か DNS)」を
     * 名乗ると、分類できなかった失敗まで回線を疑わせることになるので、既定値は UNKNOWN にした。
     *
     * <p>{@link #UNKNOWN_HOST} が文字列では判別できないことも、ここが証拠になっている —
     * この本文は日本語ロケールの JDK が出したもので、英語の語句マーカーはどれも当たらない。
     */
    @Test
    void connectionFailureTextAloneDoesNotProveANetworkProblem() {
        assertEquals(FailureReason.UNKNOWN, FailureClassifier.classifyMessage(CONNECT_FAILED));
        assertEquals(FailureReason.UNKNOWN, FailureClassifier.classifyMessage(UNKNOWN_HOST));
        assertEquals(FailureReason.UNKNOWN, FailureClassifier.classifyMessage(REFUSED));
        assertEquals(FailureReason.UNKNOWN, FailureClassifier.classifyMessage(NOT_PLAYABLE));
        assertEquals(FailureReason.UNKNOWN, FailureClassifier.classifyMessage(null));
    }

    /** 本物の回線障害は例外の<b>型</b>で判別する (文面はロケールで変わるが型は変わらない)。 */
    @Test
    void realConnectionFailuresStayConnectionFailures() {
        assertEquals(FailureReason.CONNECTION_FAILED, FailureClassifier.classify(
                new java.net.UnknownHostException("kuronami-does-not-exist-42.invalid")));
        assertEquals(FailureReason.CONNECTION_FAILED, FailureClassifier.classify(
                new java.net.ConnectException("Connection refused: getsockopt")));
        assertEquals(FailureReason.CONNECTION_FAILED, FailureClassifier.classify(
                new java.net.SocketTimeoutException("Read timed out")));
        assertEquals(FailureReason.CONNECTION_FAILED, FailureClassifier.classify(
                new javax.net.ssl.SSLHandshakeException("handshake_failure")));
        // 包み紙の下にある時も拾う (lavaplayer は必ず定型文で包んで配る)。
        assertEquals(FailureReason.CONNECTION_FAILED, FailureClassifier.classify(
                new FriendlyException(CONNECT_FAILED, FriendlyException.Severity.COMMON,
                        new java.net.UnknownHostException("kuronami-does-not-exist-42.invalid"))));
    }

    /**
     * 型の判定は<b>文面がどれにも当たらなかった時だけ</b>効くこと。
     * 逆順にすると、相手が 503 を返した失敗をタイムアウト型が包んでいるだけで回線障害に化ける。
     */
    @Test
    void theExceptionTypeDoesNotOverrideAClassifiedMessage() {
        final Throwable thrown = new java.net.SocketTimeoutException(NOT_PLAYABLE);
        thrown.initCause(new java.io.IOException(HTTP_503));
        assertEquals(FailureReason.SOURCE_REFUSED, FailureClassifier.classify(thrown));
    }

    /**
     * 語境界の単体テスト。<b>入力は合成</b> (この形の実文は採取できていない) だが、
     * 判定しているのは語彙ではなく境界の扱いなので、実測が無くても意味がある。
     */
    @Test
    void wordsInsideOtherWordsDoNotMatch() {
        // 期待値の変更 (2026-08-21): 語句が当たらなかった時の落とし所が UNKNOWN になった。
        assertEquals(FailureReason.UNKNOWN,
                FailureClassifier.classifyMessage("Failed to read the privatekey store"));
        assertEquals(FailureReason.UNKNOWN,
                FailureClassifier.classifyMessage("undeleted temp file left behind"));
        assertEquals(FailureReason.UNKNOWN,
                FailureClassifier.classifyMessage("regionalization table missing"));
        // 語として現れていれば従来どおり当たる。
        assertEquals(FailureReason.REGION_LOCKED,
                FailureClassifier.classifyMessage("blocked in this region"));
    }

    // ---- 原因チェーン ----

    @Test
    void theInnermostClassifiableCauseWins() {
        // 実測チェーン: FriendlyException("That URL is not playable.")
        //                 <- IllegalStateException("Status code 403")
        final Throwable inner = new IllegalStateException(HTTP_403);
        final Throwable thrown = new FriendlyException(NOT_PLAYABLE,
                FriendlyException.Severity.COMMON, inner);
        assertEquals(FailureReason.BOT_CHECK, FailureClassifier.classify(thrown));
        assertSame(inner, FailureClassifier.blamed(thrown));
    }

    @Test
    void classifyAndBlamedAgreeOnTheSameFrame() {
        final Throwable inner = new java.io.IOException("Connection reset");
        final Throwable thrown = new FriendlyException(VIDEO_UNAVAILABLE,
                FriendlyException.Severity.COMMON, inner);
        // 内側は判別できないので、判別できた外側が理由と詳細の両方の根拠になる。
        assertEquals(FailureReason.PRIVATE_OR_REMOVED, FailureClassifier.classify(thrown));
        assertSame(thrown, FailureClassifier.blamed(thrown));
    }

    @Test
    void anUnclassifiableChainBlamesTheDeepestFrame() {
        final Throwable deepest = new java.net.ConnectException("Connection refused: getsockopt");
        final Throwable middle = new java.io.IOException(REFUSED, deepest);
        final Throwable thrown = new FriendlyException(CONNECT_FAILED,
                FriendlyException.Severity.COMMON, middle);
        assertEquals(FailureReason.CONNECTION_FAILED, FailureClassifier.classify(thrown));
        assertSame(deepest, FailureClassifier.blamed(thrown));
    }

    /**
     * 試聴版だけの曲は<b>専用の理由</b>に落ちること (C17)。
     *
     * <p>これが崩れると {@link FailureReason#UNSUPPORTED_URL} = 「非対応のリンクです」に戻る。
     * 正当な SoundCloud のリンクにその文面を出すのが C17 の直そうとしている嘘そのものなので、
     * ここは分類の中で最も戻ってはいけない一点になる。
     *
     * <p>目印は MDM 自身が立てる文字列なので、投げる側 ({@code PreviewAwareSoundCloud}) と
     * 読む側を同じ定数に縛ってある。テストでも文面を直書きせず定数を使う — 直書きすると
     * 「両方が同時に壊れているのに緑」が作れてしまう。
     */
    @Test
    void aPreviewOnlyTrackIsClassifiedFromItsOwnMarker() {
        final Throwable thrown = new FriendlyException(FailureClassifier.PREVIEW_MARKER,
                FriendlyException.Severity.COMMON, null);
        assertEquals(FailureReason.PREVIEW_ONLY, FailureClassifier.classify(thrown));
        // loadOnce が実際に呼ぶのはこちら (集約例外でなければ classify に委ねる経路)。
        assertEquals(FailureReason.PREVIEW_ONLY, ClientFailureDetails.classify(thrown));
        // 理由に添える技術詳細も loadOnce が同じ例外から採る。空だと画面とログから
        // 「何を見て試聴版だと判じたか」が消えるので、目印がそのまま残ることを固定する。
        assertTrue(ClientFailureDetails.shortDetail(thrown)
                .contains(FailureClassifier.PREVIEW_MARKER),
                "詳細から目印が消えている: " + ClientFailureDetails.shortDetail(thrown));
        // lavaplayer が定型文で包んでも、連鎖の奥から拾えること。
        final Throwable wrapped = new FriendlyException(NOT_PLAYABLE,
                FriendlyException.Severity.COMMON, thrown);
        assertEquals(FailureReason.PREVIEW_ONLY, FailureClassifier.classify(wrapped));
    }

    /**
     * 存在しない SoundCloud トラックは試聴版<b>ではない</b>こと。
     *
     * <p>lavaplayer は track の JSON が取れない時に "This track is not available" を投げる。
     * これを試聴版と同じ理由に寄せると、消えたリンクに「試聴版しか配信されていません」と出る。
     */
    @Test
    void aMissingSoundCloudTrackIsNotAPreview() {
        final Throwable thrown = new FriendlyException(SOUNDCLOUD_MISSING,
                FriendlyException.Severity.COMMON, null);
        assertNotEquals(FailureReason.PREVIEW_ONLY, FailureClassifier.classify(thrown));
    }

    @Test
    void aSelfReferencingChainTerminates() {
        final Exception looping = new Exception("loop") {
            private static final long serialVersionUID = 1L;

            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        // 期待値の変更 (2026-08-21): 既定値が UNKNOWN になった。根拠を持たない失敗が
        // 「回線か DNS を見ろ」と言わなくなる。
        assertEquals(FailureReason.UNKNOWN, FailureClassifier.classify(looping));
        assertSame(looping, FailureClassifier.blamed(looping));
    }
}
