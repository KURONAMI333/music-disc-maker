package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;

import dev.lavalink.youtube.AllClientsFailedException;
import dev.lavalink.youtube.ClientException;
import dev.lavalink.youtube.clients.AndroidVr;
import dev.lavalink.youtube.clients.Web;

/**
 * client ごとの失敗理由の取り出しを、<b>実際に採取した例外文</b>で固定する。
 *
 * <p>入力の文字列はすべて 2026-08-18 に本物の YouTube / lavaplayer から採ったもの
 * ({@code AndroidVr} 単独構成・youtube-source 1.18.2)。文面を creative に書き換えると、
 * 「手元では通るが実環境では当たらない」テストになるので<b>逐語で置く</b>。
 */
class ClientFailureDetailsTest {

    /** 存在しない video ID / 非公開動画 (実測)。 */
    private static final String UNAVAILABLE = "This video is unavailable";
    /** 削除済み動画 / 年齢制限動画 (実測。<b>両方が同じ文面になる</b>)。 */
    private static final String REQUIRES_LOGIN = "This video requires login.";
    /** 存在しない playlist (実測)。 */
    private static final String PLAYLIST_400 =
            "java.io.IOException: Invalid status code for playlist response: 400";

    @Test
    void shortDetailCarriesTheClientReasonInsteadOfTheAggregateHeadline() {
        final AllClientsFailedException all = aggregate(clientFailure(UNAVAILABLE));
        // 先頭行だけを採ると "(yts.version: ...) All clients failed to load the item." になる。
        assertTrue(all.getMessage().startsWith("(yts.version:"),
                "採取した集約メッセージの形が変わっている: " + all.getMessage());
        assertEquals("ANDROID_VR: " + UNAVAILABLE, ClientFailureDetails.shortDetail(all));
    }

    @Test
    void shortDetailFindsTheAggregateThroughTheCauseChain() {
        final AllClientsFailedException all = aggregate(clientFailure(REQUIRES_LOGIN));
        final Throwable wrapped = new RuntimeException("wrapper", new IllegalStateException(all));
        assertEquals("ANDROID_VR: " + REQUIRES_LOGIN, ClientFailureDetails.shortDetail(wrapped));
        assertNotNull(ClientFailureDetails.aggregate(wrapped));
    }

    @Test
    void classifyUsesTheClientReason() {
        assertEquals(FailureReason.PRIVATE_OR_REMOVED,
                ClientFailureDetails.classify(aggregate(clientFailure(UNAVAILABLE))));
        assertEquals(FailureReason.BOT_CHECK,
                ClientFailureDetails.classify(aggregate(clientFailure(REQUIRES_LOGIN))));
    }

    @Test
    void multipleClientsAreJoinedInClientOrder() {
        final AllClientsFailedException all = new AllClientsFailedException(Arrays.asList(
                new ClientException(PLAYLIST_400, new AndroidVr(), new RuntimeException(PLAYLIST_400)),
                new ClientException(REQUIRES_LOGIN, new Web(),
                        new FriendlyException(REQUIRES_LOGIN, FriendlyException.Severity.COMMON, null))));
        assertEquals("ANDROID_VR: " + PLAYLIST_400 + " | WEB: " + REQUIRES_LOGIN,
                ClientFailureDetails.shortDetail(all));
        // 既定値以外に落ちた最初の client の理由を採る (先頭の 400 は既定値のまま)。
        assertEquals(FailureReason.BOT_CHECK, ClientFailureDetails.classify(all));
    }

    @Test
    void verboseKeepsTheFullPerClientText() {
        final AllClientsFailedException all = aggregate(clientFailure(REQUIRES_LOGIN));
        final String verbose = ClientFailureDetails.verbose(all);
        assertTrue(verbose.startsWith("Client [ANDROID_VR] failed: " + REQUIRES_LOGIN), verbose);
    }

    @Test
    void nonAggregateFailuresFallBackToTypeAndFirstLine() {
        // 直リンク HTTP が 403 を返した時の実測チェーン。
        final Throwable thrown = new FriendlyException("That URL is not playable.",
                FriendlyException.Severity.COMMON, new IllegalStateException("Status code 403"));
        assertEquals("", ClientFailureDetails.verbose(thrown));
        assertNull(ClientFailureDetails.aggregate(thrown));
        // 一番外側 ("That URL is not playable.") ではなく、分類の根拠になった内側を採る。
        assertEquals("IllegalStateException: Status code 403",
                ClientFailureDetails.shortDetail(thrown));
    }

    @Test
    void aConnectionFailureShowsTheInnermostFrameRatherThanTheLavaplayerBoilerplate() {
        // 実測チェーン: FriendlyException("Connecting to the URL failed.")
        //                 <- UnknownHostException("そのようなホストは不明です。 (...)")
        final Throwable thrown = new FriendlyException("Connecting to the URL failed.",
                FriendlyException.Severity.COMMON,
                new java.net.UnknownHostException(
                        "そのようなホストは不明です。 (kuronami-does-not-exist-42.invalid)"));
        assertEquals("UnknownHostException: そのようなホストは不明です。"
                + " (kuronami-does-not-exist-42.invalid)",
                ClientFailureDetails.shortDetail(thrown));
    }

    private static ClientException clientFailure(String reason) {
        return new ClientException(reason, new AndroidVr(),
                new FriendlyException(reason, FriendlyException.Severity.COMMON, null));
    }

    private static AllClientsFailedException aggregate(ClientException failure) {
        return new AllClientsFailedException(Collections.singletonList(failure));
    }
}
