package com.kuronami.musicdiscmaker.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlaybackCursorTest {

    @Test
    @DisplayName("単曲A→playlist B1/B2→同じURLの単曲Aでも位置ごとに世代が進む")
    void generationFollowsPositionInsteadOfUrl() {
        PlaybackCursor cursor = PlaybackCursor.initial().startAt(0, 0); // 単曲 A
        assertCursor(cursor, 0, 0, PlaybackCursor.State.PLAYING, 1L);

        cursor = cursor.moveTo(1, 0); // playlist B1
        assertCursor(cursor, 1, 0, PlaybackCursor.State.PLAYING, 2L);

        cursor = cursor.moveTo(1, 1); // playlist B2
        assertCursor(cursor, 1, 1, PlaybackCursor.State.PLAYING, 3L);

        cursor = cursor.moveTo(2, 0); // URL は先頭の A と同じでも、別の盤
        assertCursor(cursor, 2, 0, PlaybackCursor.State.PLAYING, 4L);
    }

    @Test
    @DisplayName("同じ位置の通知はkeep-aliveとして世代を変えない")
    void samePositionIsKeepAlive() {
        final PlaybackCursor playing = PlaybackCursor.initial().startAt(0, 0);
        final PlaybackCursor kept = playing.moveTo(0, 0);

        assertSame(playing, kept);
        assertEquals(1L, kept.generation());
    }

    @Test
    @DisplayName("pause中の曲送りはpauseを保って世代だけ進める")
    void nextWhilePausedKeepsPausedState() {
        final PlaybackCursor paused = PlaybackCursor.initial().startAt(1, 0).pause();
        final PlaybackCursor next = paused.moveTo(1, 1);

        assertCursor(next, 1, 1, PlaybackCursor.State.PAUSED, 3L);
    }

    @Test
    @DisplayName("pauseとresumeは古い非同期完了を失効させるため世代を進める")
    void pauseAndResumeAdvanceGeneration() {
        final PlaybackCursor playing = PlaybackCursor.initial().startAt(0, 0);
        final PlaybackCursor paused = playing.pause();
        final PlaybackCursor resumed = paused.resume();

        assertCursor(paused, 0, 0, PlaybackCursor.State.PAUSED, 2L);
        assertCursor(resumed, 0, 0, PlaybackCursor.State.PLAYING, 3L);
    }

    @Test
    @DisplayName("stopは位置を保ち、遅れたロードを失効させる世代へ進む")
    void stopKeepsPositionAndInvalidatesOldLoad() {
        final PlaybackCursor playing = PlaybackCursor.initial().startAt(1, 1);
        final PlaybackCursor stopped = playing.stop();

        assertCursor(stopped, 1, 1, PlaybackCursor.State.STOPPED, 2L);
        assertSame(stopped, stopped.stop());
    }

    @Test
    @DisplayName("同じ位置の明示的な再開始は新しい世代になる")
    void restartAtSamePositionGetsNewGeneration() {
        final PlaybackCursor first = PlaybackCursor.initial().startAt(0, 0);
        final PlaybackCursor restarted = first.startAt(0, 0);

        assertNotSame(first, restarted);
        assertCursor(restarted, 0, 0, PlaybackCursor.State.PLAYING, 2L);
    }

    @Test
    @DisplayName("媒体を取り出すと位置を消して世代を進め、空状態のclearはno-opになる")
    void clearInvalidatesPlaybackWithoutReusingInitialGeneration() {
        final PlaybackCursor playing = PlaybackCursor.initial().startAt(1, 1);
        final PlaybackCursor cleared = playing.clear();

        assertCursor(cleared, PlaybackCursor.NO_INDEX, PlaybackCursor.NO_INDEX,
                PlaybackCursor.State.STOPPED, 2L);
        assertSame(cleared, cleared.clear());
    }

    @Test
    @DisplayName("世代上限では0へ戻さず1へ循環する")
    void generationWrapSkipsInitialGeneration() {
        final PlaybackCursor max = new PlaybackCursor(0, 0, PlaybackCursor.State.PLAYING, Long.MAX_VALUE);
        assertEquals(1L, max.moveTo(0, 1).generation());
        assertEquals(1L, max.stop().generation());
    }

    @Test
    @DisplayName("位置の片方だけが無い状態と負の世代を拒否する")
    void rejectsBrokenSavedValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackCursor(0, PlaybackCursor.NO_INDEX, PlaybackCursor.State.STOPPED, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackCursor(PlaybackCursor.NO_INDEX, PlaybackCursor.NO_INDEX,
                        PlaybackCursor.State.PLAYING, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackCursor(0, 0, PlaybackCursor.State.PLAYING, -1L));
    }

    private static void assertCursor(PlaybackCursor cursor, int discIndex, int trackIndex,
            PlaybackCursor.State state, long generation) {
        assertEquals(discIndex, cursor.discIndex());
        assertEquals(trackIndex, cursor.trackIndex());
        assertEquals(state, cursor.state());
        assertEquals(generation, cursor.generation());
    }
}
