package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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
    void serverSideStatusCodesStayConnectionFailures() {
        assertEquals(FailureReason.CONNECTION_FAILED, FailureClassifier.classifyMessage(HTTP_503));
        assertEquals(FailureReason.CONNECTION_FAILED, FailureClassifier.classifyMessage(PLAYLIST_400));
    }

    @Test
    void aStatusNumberNeverBecomesPrivateOrRemoved() {
        // HTTP の理由句は非公開/削除の語彙とそのまま重なる。番号が付いている失敗は
        // 動画の状態ではなく通信の結果なので、その語彙に落とさない。
        assertEquals(FailureReason.CONNECTION_FAILED,
                FailureClassifier.classifyMessage("Status code 503 Service Unavailable"));
        assertEquals(FailureReason.CONNECTION_FAILED,
                FailureClassifier.classifyMessage("Not success status code: 410 Gone, resource removed"));
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
    void aMissingSoundcloudTrackIsNotAnOfflineConnection() {
        // before: どの語句にも当たらず CONNECTION_FAILED = 画面に "Offline"。
        assertEquals(FailureReason.PRIVATE_OR_REMOVED,
                FailureClassifier.classifyMessage(SOUNDCLOUD_MISSING));
    }

    @Test
    void realConnectionFailuresStayConnectionFailures() {
        assertEquals(FailureReason.CONNECTION_FAILED, FailureClassifier.classifyMessage(CONNECT_FAILED));
        assertEquals(FailureReason.CONNECTION_FAILED, FailureClassifier.classifyMessage(UNKNOWN_HOST));
        assertEquals(FailureReason.CONNECTION_FAILED, FailureClassifier.classifyMessage(REFUSED));
        assertEquals(FailureReason.CONNECTION_FAILED, FailureClassifier.classifyMessage(NOT_PLAYABLE));
        assertEquals(FailureReason.CONNECTION_FAILED, FailureClassifier.classifyMessage(null));
    }

    /**
     * 語境界の単体テスト。<b>入力は合成</b> (この形の実文は採取できていない) だが、
     * 判定しているのは語彙ではなく境界の扱いなので、実測が無くても意味がある。
     */
    @Test
    void wordsInsideOtherWordsDoNotMatch() {
        assertEquals(FailureReason.CONNECTION_FAILED,
                FailureClassifier.classifyMessage("Failed to read the privatekey store"));
        assertEquals(FailureReason.CONNECTION_FAILED,
                FailureClassifier.classifyMessage("undeleted temp file left behind"));
        assertEquals(FailureReason.CONNECTION_FAILED,
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

    @Test
    void aSelfReferencingChainTerminates() {
        final Exception looping = new Exception("loop") {
            private static final long serialVersionUID = 1L;

            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertEquals(FailureReason.CONNECTION_FAILED, FailureClassifier.classify(looping));
        assertSame(looping, FailureClassifier.blamed(looping));
    }
}
