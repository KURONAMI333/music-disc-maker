package com.kuronami.musicdiscmaker.component;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GoldenDeck} の保存書式と識別子境界の検証。
 * entity/item を介さず Map 面だけで往復する (共通 test は Minecraft classpath を持たない)。
 */
class GoldenDeckTest {

    @Test
    @DisplayName("encode→decode で全状態が保存値を通して戻る (再設置往復の核)")
    void roundTripPreservesState() {
        final GoldenDeck deck = new GoldenDeck();
        final UUID source = deck.ensureSourceId();
        deck.setCursor(PlaybackCursor.initial().startAt(2, 1).pause());
        deck.setPausedOffsetMs(12345L);
        deck.setPlaybackStartGameTime(-1L);
        deck.setRangeBlocks(128);
        deck.setVolumePercent(80);
        deck.setRepeat(true);
        deck.setShuffle(true, 987654321L, 2, 1);
        deck.setDirectional(false);
        deck.setAutomationFinished(false);

        final GoldenDeck restored = GoldenDeck.decode(deck.encode(), true);

        assertEquals(source, restored.peekSourceId());
        assertEquals(2, restored.cursor().discIndex());
        assertEquals(1, restored.cursor().trackIndex());
        assertEquals(PlaybackCursor.State.PAUSED, restored.cursor().state());
        assertEquals(deck.cursor().generation(), restored.cursor().generation());
        assertEquals(12345L, restored.pausedOffsetMs());
        assertEquals(-1L, restored.playbackStartGameTime());
        assertEquals(128, restored.rangeBlocks());
        assertEquals(80, restored.volumePercent());
        assertTrue(restored.isRepeat());
        assertTrue(restored.isShuffle());
        assertEquals(987654321L, restored.shuffleSeed());
        assertEquals(2, restored.shuffleAnchorDiscIndex());
        assertEquals(1, restored.shuffleAnchorTrackIndex());
        assertFalse(restored.isDirectional());
    }

    @Test
    @DisplayName("媒体があっても未選択の停止カーソルを初期位置へ捏造しない")
    void roundTripPreservesStoppedCursorWithoutPosition() {
        final GoldenDeck deck = new GoldenDeck();
        deck.setCursor(PlaybackCursor.initial());

        final GoldenDeck restored = GoldenDeck.decode(deck.encode(), true);

        assertEquals(PlaybackCursor.NO_INDEX, restored.cursor().discIndex());
        assertEquals(PlaybackCursor.NO_INDEX, restored.cursor().trackIndex());
        assertEquals(PlaybackCursor.State.STOPPED, restored.cursor().state());
    }

    @Test
    @DisplayName("停止で保持した選択位置は復元しても維持する")
    void roundTripPreservesStoppedCursorPosition() {
        final GoldenDeck deck = new GoldenDeck();
        deck.setCursor(PlaybackCursor.initial().startAt(2, 1).stop());

        final GoldenDeck restored = GoldenDeck.decode(deck.encode(), true);

        assertEquals(2, restored.cursor().discIndex());
        assertEquals(1, restored.cursor().trackIndex());
        assertEquals(PlaybackCursor.State.STOPPED, restored.cursor().state());
    }

    @Test
    @DisplayName("空の保存値は BE の loadAdditional と同じ既定へ倒す")
    void emptyValuesFallBackToBlockDefaults() {
        final GoldenDeck deck = GoldenDeck.decode(Map.of(), true);

        assertNull(deck.peekSourceId());
        assertEquals(GoldenDeck.RANGE_DEFAULT, deck.rangeBlocks());
        assertEquals(GoldenDeck.VOLUME_DEFAULT, deck.volumePercent());
        assertTrue(deck.isDirectional());
        assertFalse(deck.isRepeat());
        assertFalse(deck.isShuffle());
        assertEquals(PlaybackCursor.State.STOPPED, deck.cursor().state());
        assertEquals(0, deck.cursor().discIndex());
        assertEquals(0, deck.cursor().trackIndex());
    }

    @Test
    @DisplayName("未採番の deck は source_id を保存値へ書かない (新設置直後)")
    void unmintedSourceIdIsNotWritten() {
        assertFalse(new GoldenDeck().encode().containsKey(GoldenDeck.KEY_SOURCE_ID));
    }

    @Test
    @DisplayName("decode で source_id を消えずに戻す (拾い直し後も同じ権威)")
    void restoredSourceIdKeepsIdentity() {
        final GoldenDeck deck = new GoldenDeck();
        final UUID source = deck.ensureSourceId();

        final GoldenDeck restored = GoldenDeck.decode(deck.encode(), true);
        assertEquals(source, restored.peekSourceId());
        // 復元側は ensureSourceId しても採番し直さない
        assertEquals(source, restored.ensureSourceId());
    }

    @Test
    @DisplayName("不正な source_id 文字列は未採番として読む (例外で落ちない)")
    void malformedSourceIdDecodesAsUnminted() {
        final GoldenDeck deck = GoldenDeck.decode(
                Map.of(GoldenDeck.KEY_SOURCE_ID, "not-a-uuid"), true);
        assertNull(deck.peekSourceId());
    }

    @Test
    @DisplayName("メディア無し復元は再生状態を持たない (redstonePaused/automationFinished も消える)")
    void medialessRestoreCarriesNoPlayback() {
        final GoldenDeck deck = new GoldenDeck();
        deck.ensureSourceId();
        deck.setCursor(PlaybackCursor.initial().startAt(0, 0));
        deck.setAutomationFinished(true);
        deck.setRedstonePaused(true);

        final GoldenDeck restored = GoldenDeck.decode(deck.encode(), false);
        assertEquals(PlaybackCursor.initial(), restored.cursor());
        assertFalse(restored.isRedstonePaused());
        assertFalse(restored.isAutomationFinished());
        // 識別子はメディアではなく機体のものなので残る
        assertNotNull(restored.peekSourceId());
    }

    @Test
    @DisplayName("旧形式: playbackState 欠損時は paused フラグで PAUSED を復元する")
    void legacyPausedFlagRestoresPausedState() {
        final Map<String, Object> values = new HashMap<>();
        values.put(GoldenDeck.KEY_PAUSED, true);
        values.put(GoldenDeck.KEY_ALBUM_TRACK, 0);
        values.put(GoldenDeck.KEY_PLAYLIST_TRACK, 0);

        final GoldenDeck deck = GoldenDeck.decode(values, true);
        assertEquals(PlaybackCursor.State.PAUSED, deck.cursor().state());
    }

    @Test
    @DisplayName("freezeForCarry: PLAYING を PAUSED へ確定し、世代を進める")
    void freezeForCarryPausesPlaying() {
        final GoldenDeck deck = new GoldenDeck();
        deck.setCursor(PlaybackCursor.initial().startAt(0, 0));
        final long before = deck.cursor().generation();

        deck.freezeForCarry(4321L);

        assertEquals(PlaybackCursor.State.PAUSED, deck.cursor().state());
        assertEquals(4321L, deck.pausedOffsetMs());
        assertEquals(-1L, deck.playbackStartGameTime());
        assertEquals(before + 1L, deck.cursor().generation());
    }

    @Test
    @DisplayName("freezeForCarry: STOPPED は変化しない (空車の drop)")
    void freezeForCarryKeepsStopped() {
        final GoldenDeck deck = new GoldenDeck();
        deck.freezeForCarry(999L);
        assertEquals(PlaybackCursor.State.STOPPED, deck.cursor().state());
        assertEquals(0L, deck.pausedOffsetMs());
    }

    @Test
    @DisplayName("onMediaChanged は位置と再生起点を消し、機器設定を残す")
    void mediaChangeClearsPlaybackKeepsSettings() {
        final GoldenDeck deck = new GoldenDeck();
        final UUID source = deck.ensureSourceId();
        deck.setCursor(PlaybackCursor.initial().startAt(1, 0));
        deck.setPausedOffsetMs(500L);
        deck.setPlaybackStartGameTime(777L);
        deck.setRangeBlocks(200);
        deck.setRepeat(true);

        deck.onMediaChanged();

        assertEquals(PlaybackCursor.initial().discIndex(), deck.cursor().discIndex());
        assertEquals(PlaybackCursor.State.STOPPED, deck.cursor().state());
        assertEquals(0L, deck.pausedOffsetMs());
        assertEquals(-1L, deck.playbackStartGameTime());
        assertEquals(200, deck.rangeBlocks());
        assertTrue(deck.isRepeat());
        assertEquals(source, deck.peekSourceId());
    }

    @Test
    @DisplayName("範囲・音量は BE と同じ min/max に収める")
    void clampsRangeAndVolume() {
        final GoldenDeck deck = GoldenDeck.decode(Map.of(
                GoldenDeck.KEY_RANGE, 9999, GoldenDeck.KEY_VOLUME, -50), true);
        assertEquals(GoldenDeck.RANGE_MAX, deck.rangeBlocks());
        assertEquals(GoldenDeck.VOLUME_MIN, deck.volumePercent());
    }

    @Test
    @DisplayName("デッキ保存キーは entity 予約キーと重ならない (drop item→再設置で位置/UUID を奪わない)")
    void deckKeysNeverTouchReservedEntityKeys() {
        for (String key : GoldenDeck.KEYS) {
            assertFalse(GoldenDeck.RESERVED_ENTITY_KEYS.contains(key),
                    "deck key collides with entity key: " + key);
        }
        // encode() が実際に書くキーも同じ境界を守る
        final GoldenDeck deck = new GoldenDeck();
        deck.ensureSourceId();
        deck.encode().keySet().forEach(key ->
                assertFalse(GoldenDeck.RESERVED_ENTITY_KEYS.contains(key),
                        "encoded key collides with entity key: " + key));
    }

    @Test
    @DisplayName("encode() は KEYS で宣言したキーだけを書く (スロット外の entity NBT へ触れない)")
    void encodeOnlyWritesDeclaredKeys() {
        final GoldenDeck deck = new GoldenDeck();
        deck.ensureSourceId();
        deck.encode().keySet().forEach(key ->
                assertTrue(GoldenDeck.KEYS.contains(key), "undeclared key written: " + key));
    }
}
