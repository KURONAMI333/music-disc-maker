package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.component.CustomTrackData;

/**
 * engine の拒否を期限まで保留し、実際に鳴っている音源へ「再生拒否」を付けない (#7-1)。
 *
 * <p>{@code SoundManager#play} の直後に {@code isActive} が偽でも、数 ms 遅れて開栓する経路が
 * 実在する。拒否をその場で報告すると、鳴っている音源に失敗ラベルが付く。だから判定は
 * {@link PlaybackSessions#FIRST_AUDIO_DEADLINE_MS} の期限で行い、その時点で engine が
 * 音源を保持していれば報告しない。
 */
class DeferredEngineRejectionTest {

    private static final long KEY = 7L;
    private static final CustomTrackData SONG = new CustomTrackData(
            "https://example.invalid/song", "song", "artist", 180_000L, "", false);
    private static final PlaybackFailure REJECTED = PlaybackFailure.soundEngineRejected();

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final PlaybackSessions<Long> sessions = new PlaybackSessions<>(clock::get, clock::get);

    @Test
    void aPlainRejectionIsHeldUntilTheDeadline() {
        final int token = sessions.start(KEY, SONG, 0L, 64, 100, true).token();

        assertNull(sessions.engineRejected(KEY, token, REJECTED),
                "the rejection must not be shown while a late channel allocation is still possible");
        assertEquals(REJECTED, sessions.heldEngineRejection(KEY, token),
                "the rejection must be kept for the deadline");
        assertEquals(REJECTED, sessions.resolveHeldEngineRejection(KEY, token, false),
                "if the engine never opened, the rejection was real and is reported at the deadline");
    }

    @Test
    void aRejectionThatOpensLateIsNeverReported() {
        final int token = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        sessions.engineRejected(KEY, token, REJECTED);

        assertNull(sessions.resolveHeldEngineRejection(KEY, token, true),
                "the engine holds the instance, so the sound is playing; the rejection was wrong");
        assertNull(sessions.heldEngineRejection(KEY, token), "the wrong rejection must be discarded");
    }

    @Test
    void mutedIsReportedImmediately() {
        final int token = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        final PlaybackFailure muted = PlaybackFailure.soundMuted();

        assertEquals(muted, sessions.engineRejected(KEY, token, muted),
                "volume 0 cannot resolve itself late; the user must see it at once");
        assertNull(sessions.heldEngineRejection(KEY, token), "MUTED must not be deferred");
    }

    @Test
    void realAudioDiscardsTheHeldRejection() {
        final int token = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        sessions.engineRejected(KEY, token, REJECTED);

        sessions.noteFirstAudio(KEY, token);

        assertNull(sessions.heldEngineRejection(KEY, token),
                "real PCM is the proof that the sound plays; the rejection is no longer true");
        assertNull(sessions.resolveHeldEngineRejection(KEY, token, false),
                "nothing may be reported after real audio arrived");
    }

    @Test
    void acceptingTheSoundDiscardsTheHeldRejection() {
        final int token = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        sessions.engineRejected(KEY, token, REJECTED);

        sessions.engineAccepted(KEY);

        assertNull(sessions.resolveHeldEngineRejection(KEY, token, false),
                "an accepted sound must not report the earlier rejection");
    }

    @Test
    void stopDiscardsTheHeldRejection() {
        final int token = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        sessions.engineRejected(KEY, token, REJECTED);

        sessions.stop(KEY);

        assertNull(sessions.resolveHeldEngineRejection(KEY, token, false),
                "a stopped generation must not report anything");
        assertNull(sessions.heldEngineRejection(KEY, token));
    }

    @Test
    void theReasonIsReportedOnceUntilTheSoundActuallyPlays() {
        final int first = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        sessions.engineRejected(KEY, first, REJECTED);
        assertEquals(REJECTED, sessions.resolveHeldEngineRejection(KEY, first, false));

        final int second = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        sessions.engineRejected(KEY, second, REJECTED);
        assertNull(sessions.resolveHeldEngineRejection(KEY, second, false),
                "the same reason must stay quiet until the sound actually plays");

        sessions.engineAccepted(KEY);

        final int third = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        sessions.engineRejected(KEY, third, REJECTED);
        assertEquals(REJECTED, sessions.resolveHeldEngineRejection(KEY, third, false),
                "a genuine acceptance resets the frequency memory");
    }

    @Test
    void aHeldRejectionDoesNotLeakIntoTheNextGeneration() {
        final int first = sessions.start(KEY, SONG, 0L, 64, 100, true).token();
        sessions.engineRejected(KEY, first, REJECTED);

        sessions.stop(KEY);
        final int second = sessions.start(KEY, SONG, 0L, 64, 100, true).token();

        assertNull(sessions.heldEngineRejection(KEY, second),
                "the next generation must start without the previous rejection");
        assertNull(sessions.resolveHeldEngineRejection(KEY, first, false),
                "a stale token must never resolve");
    }
}
