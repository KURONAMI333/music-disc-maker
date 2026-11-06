package com.kuronami.musicdiscmaker.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;

/** client が取った曲情報を受け取ってよいかの判定。焼く URL と尺が server 側の値に固定されることを見る。 */
class ClientResolvedTrackTest {

    private static final String WATCH = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final String SHORT = "https://youtu.be/dQw4w9WgXcQ";

    @Test void botCheckAsksClientAndBurnsClientDurationOnServerUrl() {
        assertTrue(ClientResolvedTrack.shouldAskClient(FailureReason.BOT_CHECK, WATCH));
        final CustomTrackData data = ClientResolvedTrack.accept(WATCH, WATCH, "曲名", "作者", 210_000L);
        assertNotNull(data);
        assertEquals(WATCH, data.url());
        assertEquals(210_000L, data.durationMs());
        assertEquals("曲名", data.title());
        assertEquals("作者", data.author());
        assertFalse(data.radio());
    }

    @Test void noAlternativeFoundAsksClientToo() {
        assertTrue(ClientResolvedTrack.shouldAskClient(FailureReason.NO_ALTERNATIVE_FOUND, WATCH));
        assertNotNull(ClientResolvedTrack.accept(WATCH, WATCH, "曲名", "作者", 210_000L));
    }

    @Test void otherFailuresNeverAskTheClient() {
        for (final FailureReason reason : FailureReason.values()) {
            if (reason == FailureReason.BOT_CHECK || reason == FailureReason.NO_ALTERNATIVE_FOUND) {
                continue;
            }
            assertFalse(ClientResolvedTrack.shouldAskClient(reason, WATCH), reason.name());
        }
    }

    @Test void nonYoutubeNeverAsksEvenForBotCheck() {
        final String soundcloud = "https://soundcloud.com/artist/track";
        assertFalse(ClientResolvedTrack.shouldAskClient(FailureReason.BOT_CHECK, soundcloud));
        assertFalse(ClientResolvedTrack.shouldAskClient(FailureReason.NO_ALTERNATIVE_FOUND, soundcloud));
        assertNull(ClientResolvedTrack.accept(soundcloud, soundcloud, "曲名", "作者", 210_000L));
    }

    @Test void differentVideoIsRejected() {
        final String other = "https://www.youtube.com/watch?v=aaaaaaaaaaa";
        assertNull(ClientResolvedTrack.accept(WATCH, other, "曲名", "作者", 210_000L));
        // 同じ動画を別の形式で指すのは通す (client は info.uri() を送ってくる)。
        assertNotNull(ClientResolvedTrack.accept(WATCH, SHORT, "曲名", "作者", 210_000L));
    }

    @Test void sameVideoInAnotherFormBurnsTheServerUrl() {
        final CustomTrackData data = ClientResolvedTrack.accept(SHORT, WATCH, "曲名", "作者", 210_000L);
        assertNotNull(data);
        assertEquals(SHORT, data.url());
    }

    @Test void blankOrMissingClientUrlIsRejected() {
        assertNull(ClientResolvedTrack.accept(WATCH, "", "曲名", "作者", 210_000L));
        assertNull(ClientResolvedTrack.accept(WATCH, null, "曲名", "作者", 210_000L));
        assertNull(ClientResolvedTrack.accept(null, WATCH, "曲名", "作者", 210_000L));
    }

    @Test void durationMustBeRealAndBelowTheRadioThreshold() {
        assertNull(ClientResolvedTrack.accept(WATCH, WATCH, "曲名", "作者", 0L));
        assertNull(ClientResolvedTrack.accept(WATCH, WATCH, "曲名", "作者", -1L));
        assertNull(ClientResolvedTrack.accept(WATCH, WATCH, "曲名", "作者",
                DiscFabrication.RADIO_DURATION_THRESHOLD_MS + 1L));
        assertNotNull(ClientResolvedTrack.accept(WATCH, WATCH, "曲名", "作者", 1_000L));
        // 境目ちょうどは「ラジオではない」側なので焼ける。
        assertNotNull(ClientResolvedTrack.accept(WATCH, WATCH, "曲名", "作者",
                DiscFabrication.RADIO_DURATION_THRESHOLD_MS));
    }

    /**
     * 動画 ID を持たない YouTube URL は「同じ動画か」を確かめられないので、client に取りに行かせず、
     * 送られてきても焼かない (確かめずに通すと client が任意の曲名と尺を元の URL に貼れる)。
     */
    @Test void playlistAndChannelUrlsAreNeverBurned() {
        for (final String url : new String[] {
                "https://www.youtube.com/playlist?list=PL1234567890",
                "https://www.youtube.com/@someone",
                "https://www.youtube.com/results?search_query=test",
                "https://www.youtube.com/watch?list=PL1234567890"}) {
            assertFalse(ClientResolvedTrack.shouldAskClient(FailureReason.BOT_CHECK, url), url);
            assertFalse(ClientResolvedTrack.shouldAskClient(FailureReason.NO_ALTERNATIVE_FOUND, url), url);
            // client が同じ URL をそのまま返しても、動画を指していないので通さない。
            assertNull(ClientResolvedTrack.accept(url, url, "曲名", "作者", 210_000L), url);
        }
    }

    @Test void blankTitleIsRejected() {
        assertNull(ClientResolvedTrack.accept(WATCH, WATCH, "", "作者", 210_000L));
        assertNull(ClientResolvedTrack.accept(WATCH, WATCH, "   ", "作者", 210_000L));
        assertNull(ClientResolvedTrack.accept(WATCH, WATCH, null, "作者", 210_000L));
    }

    @Test void titleAndAuthorAreClampedLikeAnyOtherTrack() {
        final String longText = "あ".repeat(400);
        final CustomTrackData data = ClientResolvedTrack.accept(WATCH, WATCH, longText, longText, 210_000L);
        assertNotNull(data);
        assertEquals(256, data.title().length());
        assertEquals(256, data.author().length());
    }
}
