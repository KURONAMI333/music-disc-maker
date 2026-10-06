package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;

/**
 * 有限曲が再生の途中で一時的に途切れた時の扱いを固定する。
 *
 * <p>金ジュークの server は再生中に定期再送しない。途中で切れた client はそのままだと曲の残りを
 * 無音で過ごし、同じ曲を他の player は聴き続ける (CF 報告「一人だけ無音」)。
 * 一時的な失敗なら、server と同じ壁時計基準の現在位置から有限回だけ開き直す。
 */
class FiniteStreamResumeTest {

    private static final long KEY = 7L;
    private static final CustomTrackData SONG = new CustomTrackData("https://example.invalid/song",
            "song", "artist", 180_000L, "", false);

    private static final class Voice implements PlaybackVoice {
        boolean released;
        @Override public boolean isVoiceStopped() { return released; }
        @Override public void setDirectional(boolean value) { }
        @Override public void setRangeBlocks(int value) { }
        @Override public void setVolumePercent(int value) { }
        @Override public void stopAndRelease() { released = true; }
    }

    private static PlaybackFailure network() {
        return PlaybackFailure.ofReason(FailureReason.CONNECTION_FAILED, "starved");
    }

    @Test
    void transientMidStreamFailureResumesAtServerSyncedPosition() {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final PlaybackSessions<Long> sessions = new PlaybackSessions<>(clock::get, clock::get);
        final var start = sessions.start(KEY, SONG, 20_000L, 64, 100, true);
        final Voice voice = new Voice();
        assertTrue(sessions.install(KEY, start.token(), SONG.sourceKey(), 20_000L, voice));

        clock.addAndGet(45_000L); // 20 s 地点から 45 s 鳴った所で途切れた
        final var resume = sessions.finiteStreamFailed(KEY, start.token(), network());

        assertEquals(PlaybackSessions.ReconnectKind.RETRY, resume.kind());
        assertEquals(65_000L, resume.offsetMs(), "server と同じ壁時計の現在位置から開き直す");
        assertEquals(1, resume.attempt());
        assertTrue(voice.released, "途切れた voice は確実に畳む");
        assertNull(sessions.activeVoice(KEY));
        assertTrue(sessions.isLive(KEY, start.token()), "同じ世代のまま開き直す (後続の停止で捨てられる)");
    }

    @Test
    void resumedPlaybackKeepsChunkResendDedup() {
        final AtomicLong clock = new AtomicLong(0L);
        final PlaybackSessions<Long> sessions = new PlaybackSessions<>(clock::get, clock::get);
        final var start = sessions.start(KEY, SONG, 0L, 64, 100, true);
        sessions.install(KEY, start.token(), SONG.sourceKey(), 0L, new Voice());
        clock.addAndGet(30_000L);
        final var resume = sessions.finiteStreamFailed(KEY, start.token(), network());
        // 再接続のロード完了: 要求した位置で登録し直す。
        sessions.install(KEY, start.token(), SONG.sourceKey(), resume.offsetMs(), new Voice());
        clock.addAndGet(10_000L);

        // chunk 再入の再送は server の実経過 (40 s) を載せて来る。シークと誤判定しない。
        final var resend = sessions.start(KEY, SONG, 40_000L, 64, 100, true);
        assertEquals(false, resend.load(), "再接続後も推定位置が server と揃い、再送で鳴らし直さない");
    }

    @Test
    void retriesAreBoundedAndThenGiveUp() {
        final AtomicLong clock = new AtomicLong(0L);
        final PlaybackSessions<Long> sessions = new PlaybackSessions<>(clock::get, clock::get);
        final var start = sessions.start(KEY, SONG, 0L, 64, 100, true);
        long offset = 0L;
        for (int attempt = 1; attempt <= PlaybackSessions.MAX_FINITE_RESUME; attempt++) {
            sessions.install(KEY, start.token(), SONG.sourceKey(), offset, new Voice());
            clock.addAndGet(5_000L); // 安定前に再び落ちる
            final var resume = sessions.finiteStreamFailed(KEY, start.token(), network());
            assertEquals(PlaybackSessions.ReconnectKind.RETRY, resume.kind());
            assertEquals(attempt, resume.attempt());
            offset = resume.offsetMs();
        }
        sessions.install(KEY, start.token(), SONG.sourceKey(), offset, new Voice());
        clock.addAndGet(5_000L);
        assertEquals(PlaybackSessions.ReconnectKind.GIVE_UP,
                sessions.finiteStreamFailed(KEY, start.token(), network()).kind());
    }

    @Test
    void stablePlaybackRestoresTheRetryBudget() {
        final AtomicLong clock = new AtomicLong(0L);
        final PlaybackSessions<Long> sessions = new PlaybackSessions<>(clock::get, clock::get);
        final var start = sessions.start(KEY, SONG, 0L, 64, 100, true);
        long offset = 0L;
        for (int i = 0; i < PlaybackSessions.MAX_FINITE_RESUME + 2; i++) {
            sessions.install(KEY, start.token(), SONG.sourceKey(), offset, new Voice());
            sessions.noteFirstAudio(KEY, start.token());
            clock.addAndGet(PlaybackSessions.STABLE_MS + 1_000L); // 毎回しばらく鳴ってから落ちる
            final var resume = sessions.finiteStreamFailed(KEY, start.token(), network());
            assertEquals(PlaybackSessions.ReconnectKind.RETRY, resume.kind(), "安定して鳴った後の瞬断は数え直す");
            offset = resume.offsetMs();
        }
    }

    @Test
    void doesNotResumeRadioLocalPermanentNearEndOrStaleGeneration() {
        final AtomicLong clock = new AtomicLong(0L);
        final PlaybackSessions<Long> sessions = new PlaybackSessions<>(clock::get, clock::get);

        final CustomTrackData radio = new CustomTrackData("https://example.invalid/live", "live", "", 0L, "", true);
        final var radioStart = sessions.start(KEY, radio, 0L, 64, 100, true);
        sessions.install(KEY, radioStart.token(), radio.sourceKey(), 0L, new Voice());
        assertEquals(PlaybackSessions.ReconnectKind.NONE,
                sessions.finiteStreamFailed(KEY, radioStart.token(), network()).kind(), "ラジオは既存の再接続経路");

        final var permanent = sessions.start(KEY, SONG, 0L, 64, 100, true);
        sessions.install(KEY, permanent.token(), SONG.sourceKey(), 0L, new Voice());
        clock.addAndGet(10_000L);
        assertEquals(PlaybackSessions.ReconnectKind.NONE, sessions.finiteStreamFailed(KEY, permanent.token(),
                PlaybackFailure.ofReason(FailureReason.PRIVATE_OR_REMOVED, null)).kind(), "確定的な失敗は開き直さない");

        final var nearEnd = sessions.start(KEY, SONG, 177_000L, 64, 100, true);
        sessions.install(KEY, nearEnd.token(), SONG.sourceKey(), 177_000L, new Voice());
        assertEquals(PlaybackSessions.ReconnectKind.NONE,
                sessions.finiteStreamFailed(KEY, nearEnd.token(), network()).kind(), "残り僅かなら開き直さない");

        final var current = sessions.start(KEY, SONG, 0L, 64, 100, true);
        sessions.install(KEY, current.token(), SONG.sourceKey(), 0L, new Voice());
        sessions.stop(KEY);
        assertEquals(PlaybackSessions.ReconnectKind.NONE,
                sessions.finiteStreamFailed(KEY, current.token(), network()).kind(), "停止済みの世代は開き直さない");
    }
}
