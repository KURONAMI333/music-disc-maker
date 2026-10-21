package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.component.CustomTrackData;

/**
 * 最初の実音が届かなかった失敗を、engine の拒否と同じ「鳴り始めるまで 1 回」で抑止する。
 *
 * <p>リンク切れの URL を並べたアルバムでは、曲が変わるたびに同じ座標で同じ
 * {@link PlaybackFailure#streamUnavailable()} が上がる。抑止しないとチャットが同じ行で埋まる。
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
    void theNextSongAtTheSameJukeboxDoesNotRepeatTheSameLabel() {
        final int firstToken = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        assertSame(DEADLINE, sessions.firstAudioDeadlineFailure(KEY, firstToken, DEADLINE),
                "the first deadline must be reported");

        final int secondToken = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        assertNull(sessions.firstAudioDeadlineFailure(KEY, secondToken, DEADLINE),
                "the next song at the same jukebox repeats the same label and must be suppressed");
    }

    @Test
    void realAudioClearsTheMemorySoTheNextFailureIsShown() {
        final int firstToken = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        assertSame(DEADLINE, sessions.firstAudioDeadlineFailure(KEY, firstToken, DEADLINE));

        final int secondToken = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        assertNull(sessions.firstAudioDeadlineFailure(KEY, secondToken, DEADLINE));
        assertTrue(sessions.noteFirstAudio(KEY, secondToken), "the second generation must still be live");
        sessions.engineAccepted(KEY);

        final int thirdToken = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        assertSame(DEADLINE, sessions.firstAudioDeadlineFailure(KEY, thirdToken, DEADLINE),
                "real audio must clear the memory so a later failure is shown again");
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
