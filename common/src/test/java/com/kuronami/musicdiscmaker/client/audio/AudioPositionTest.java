package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.component.CustomTrackData;

/**
 * 実音の再生位置は、読み込みにかかった時間を含まないことを固定する。
 *
 * <p>server は要求時刻から曲尺を数えて停止を送る。client が URL の解決と読み込みに T 秒かけて
 * 要求された位置から鳴り始めると、実音は server より T 秒遅れる。曲の終わりの停止を鳴り終わりまで
 * 待つ ({@code ClientPlaybackManager#deferNaturalEndStop}) には、この遅れを含まない実音の位置が要る。
 */
class AudioPositionTest {

    private static final long KEY = 3L;
    private static final CustomTrackData SONG = new CustomTrackData("https://example.invalid/position",
            "song", "artist", 180_000L, "", false);

    private static final class Voice implements PlaybackVoice {
        @Override public boolean isVoiceStopped() { return false; }
        @Override public void setDirectional(boolean value) { }
        @Override public void setRangeBlocks(int value) { }
        @Override public void setVolumePercent(int value) { }
        @Override public void stopAndRelease() { }
    }

    @Test
    void positionStartsAtFirstAudioNotAtTheRequest() {
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final PlaybackSessions<Long> sessions = new PlaybackSessions<>(clock::get, clock::get);
        final var start = sessions.start(KEY, SONG, 0L, 64, 100, true);
        assertEquals(-1L, sessions.audioPositionMs(KEY), "鳴る前に位置を返している");

        clock.addAndGet(2_300L); // 解決と読み込み (2026-09-03 の実測で初回約2.3秒)
        assertTrue(sessions.install(KEY, start.token(), SONG.sourceKey(), 0L, new Voice()));
        assertEquals(-1L, sessions.audioPositionMs(KEY), "最初の実 PCM の前に位置を返している");
        assertTrue(sessions.noteFirstAudio(KEY, start.token()));

        clock.addAndGet(177_700L); // server の時計ではちょうど曲尺 (180 秒)
        assertEquals(177_700L, sessions.audioPositionMs(KEY),
                "実音の位置に読み込みの時間が入っている (曲の終わりの停止で残り 2.3 秒を切ってしまう)");
    }

    @Test
    void positionCountsFromTheOpenedOffset() {
        final AtomicLong clock = new AtomicLong(5_000L);
        final PlaybackSessions<Long> sessions = new PlaybackSessions<>(clock::get, clock::get);
        final var start = sessions.start(KEY, SONG, 60_000L, 64, 100, true);
        assertTrue(sessions.install(KEY, start.token(), SONG.sourceKey(), 60_000L, new Voice()));
        assertTrue(sessions.noteFirstAudio(KEY, start.token()));
        clock.addAndGet(1_500L);
        assertEquals(61_500L, sessions.audioPositionMs(KEY));
    }

    @Test
    void positionDoesNotAdvanceWhileThePlaybackClockIsPaused() {
        final AtomicLong wall = new AtomicLong(1_000L);
        final AtomicLong playback = new AtomicLong(1_000L);
        final PlaybackSessions<Long> sessions = new PlaybackSessions<>(wall::get, wall::get, playback::get);
        final var start = sessions.start(KEY, SONG, 0L, 64, 100, true);
        assertTrue(sessions.install(KEY, start.token(), SONG.sourceKey(), 0L, new Voice()));
        assertTrue(sessions.noteFirstAudio(KEY, start.token()));
        wall.addAndGet(10_000L);
        playback.addAndGet(4_000L); // シングルプレイで6秒一時停止していた
        assertEquals(4_000L, sessions.audioPositionMs(KEY), "一時停止の間も実音の位置が進んでいる");
    }
}
