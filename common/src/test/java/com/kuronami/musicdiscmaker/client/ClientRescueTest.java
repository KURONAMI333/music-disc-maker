package com.kuronami.musicdiscmaker.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;

/**
 * client が server の代わりに引き直すかの判定。単体ワールドのホストは server と同じ回線なので
 * 引き直さない (失敗の理由が変わらない)。LAN で他人のワールドに入っている時は引き直す。
 */
class ClientRescueTest {

    private static final String WATCH = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final String SOUNDCLOUD = "https://soundcloud.com/artist/track";

    @Test void rescuesWhenTheClientHasItsOwnConnection() {
        assertTrue(ClientTrackFallback.shouldRescue(FailureReason.BOT_CHECK, WATCH, false));
        assertTrue(ClientTrackFallback.shouldRescue(FailureReason.NO_ALTERNATIVE_FOUND, WATCH, false));
    }

    @Test void doesNotRescueInASingleplayerWorld() {
        assertFalse(ClientTrackFallback.shouldRescue(FailureReason.BOT_CHECK, WATCH, true));
        assertFalse(ClientTrackFallback.shouldRescue(FailureReason.NO_ALTERNATIVE_FOUND, WATCH, true));
    }

    /** 単体ワールドの判定を足しても、server 側の絞り込み (理由・URL) はそのまま効く。 */
    @Test void keepsTheServerSideReasonAndUrlRules() {
        for (final FailureReason reason : FailureReason.values()) {
            if (reason == FailureReason.BOT_CHECK || reason == FailureReason.NO_ALTERNATIVE_FOUND) {
                continue;
            }
            assertFalse(ClientTrackFallback.shouldRescue(reason, WATCH, false), reason.name());
        }
        assertFalse(ClientTrackFallback.shouldRescue(FailureReason.BOT_CHECK, SOUNDCLOUD, false));
        assertFalse(ClientTrackFallback.shouldRescue(FailureReason.NO_ALTERNATIVE_FOUND, SOUNDCLOUD, false));
    }
}
