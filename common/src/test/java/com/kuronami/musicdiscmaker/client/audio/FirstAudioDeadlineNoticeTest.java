package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.component.CustomTrackData;

/**
 * 最初の実音が届かなかった失敗を、音源 (jukebox の位置) ごとに「実際に鳴るまで 1 回」で抑止する。
 *
 * <p>リンク切れの URL を並べたアルバムでは、曲が変わるたびに同じ座標で同じ
 * {@link PlaybackFailure#streamUnavailable()} が上がる。抑止しないとチャットが同じ行で埋まる。
 *
 * <p>この記憶は engine の受理 ({@link PlaybackSessions#engineAccepted}) でも
 * {@link PlaybackSessions#stop} でも消さない。受理は音が出た証明ではなく、停止は曲送りのたびに
 * 起きるため。消えるのは実 PCM ({@link PlaybackSessions#noteFirstAudio}) とワールド離脱だけ。
 */
class FirstAudioDeadlineNoticeTest {

    private static final long KEY = 3L;
    private static final long OTHER_KEY = 4L;
    private static final CustomTrackData SONG = new CustomTrackData(
            "https://example.invalid/dead", "song", "artist", 180_000L, "", false);
    private static final PlaybackFailure DEADLINE = PlaybackFailure.streamUnavailable();

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final PlaybackSessions<Long> sessions = new PlaybackSessions<>(clock::get, clock::get);

    @Test
    void engineAcceptanceDoesNotMakeTheNextSongReportAgain() {
        final int firstToken = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        assertSame(DEADLINE, sessions.firstAudioDeadlineFailure(KEY, firstToken, DEADLINE),
                "the first deadline must be reported");

        // SoundManager#play が通っただけ。実 PCM はまだ来ていない。
        sessions.engineAccepted(KEY);

        final int secondToken = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        assertNull(sessions.firstAudioDeadlineFailure(KEY, secondToken, DEADLINE),
                "engine acceptance is not audio; the next deadline at the same source stays quiet");
    }

    @Test
    void aRejectionBetweenDeadlinesDoesNotMakeTheNextDeadlineReport() {
        final int firstToken = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        assertSame(DEADLINE, sessions.firstAudioDeadlineFailure(KEY, firstToken, DEADLINE));

        final int secondToken = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        assertNotNull(sessions.engineRejected(KEY, secondToken, PlaybackFailure.soundMuted()),
                "the rejection itself is new information and is shown");

        assertNull(sessions.firstAudioDeadlineFailure(KEY, secondToken, DEADLINE),
                "MUTED and STREAM_UNAVAILABLE have separate memories; the deadline label is still remembered");
    }

    @Test
    void realAudioClearsTheDeadlineMemorySoLaterFailuresAreShownAgain() {
        final int firstToken = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        assertSame(DEADLINE, sessions.firstAudioDeadlineFailure(KEY, firstToken, DEADLINE));

        final int secondToken = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        assertNull(sessions.firstAudioDeadlineFailure(KEY, secondToken, DEADLINE));
        // 1.20.1 の帯は実音の通知で engineAccepted を呼ばない。実音だけで記憶が消えること。
        assertTrue(sessions.noteFirstAudio(KEY, secondToken), "the second generation must still be live");

        final int thirdToken = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        assertSame(DEADLINE, sessions.firstAudioDeadlineFailure(KEY, thirdToken, DEADLINE),
                "real audio must clear the memory so a later failure is shown again");
    }

    @Test
    void theDeadlineMustBeReadBeforeTheSessionIsStopped() {
        final int token = sessions.start(KEY, SONG, 0L, 64, 100, true).token();

        sessions.stop(KEY);

        assertNull(sessions.firstAudioDeadlineFailure(KEY, token, DEADLINE),
                "stop() advances the generation, so reading the deadline after the stop is always dropped");
    }

    @Test
    void aDeadlineReadBeforeTheStopStillSilencesTheNextSong() {
        final int firstToken = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        assertSame(DEADLINE, sessions.firstAudioDeadlineFailure(KEY, firstToken, DEADLINE),
                "the 1.21-1.21.1 band must decide before releaseOnFirstAudioDeadline stops the session");
        sessions.stop(KEY);

        final int secondToken = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        assertNull(sessions.firstAudioDeadlineFailure(KEY, secondToken, DEADLINE),
                "the memory survives the stop that follows the deadline");
    }

    @Test
    void aDifferentJukeboxStillReportsTheSameLabel() {
        final int token = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        assertSame(DEADLINE, sessions.firstAudioDeadlineFailure(KEY, token, DEADLINE));

        final int otherToken = sessions.start(OTHER_KEY, SONG, 0L, 64, 100, true).token();
        assertSame(DEADLINE, sessions.firstAudioDeadlineFailure(OTHER_KEY, otherToken, DEADLINE),
                "another jukebox has its own memory");
    }
}
