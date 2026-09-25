package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link PlaybackVoice#voiceGone} の真偽表と、それを信じた session 側の回収を固定する。
 *
 * <p>vanilla は channel の自然終了で {@code SoundInstance#stop()} を呼ばない
 * (出荷対象の全 band の {@code SoundEngine#tickNonPaused} を実機 bytecode で確認)。
 * だから「engine が stream を作った後で channel を失った」を MOD 側で検出しないと、
 * 鳴り終わった voice が session に残り続けて同じ URL の再送が dedup で捨てられる。
 */
class VoiceLivenessTest {

    private static final long KEY = 42L;

    /** 「engine が channel を手放した」を再現するための可変の voice。 */
    private static final class FlaggedVoice implements PlaybackVoice {
        boolean gone;
        boolean released;

        @Override
        public boolean isVoiceStopped() {
            return gone;
        }

        @Override public void setDirectional(boolean value) { }
        @Override public void setRangeBlocks(int value) { }
        @Override public void setVolumePercent(int value) { }
        @Override public void stopAndRelease() { released = true; }
    }

    @Test
    void channelLostAfterAcceptanceIsGone() {
        // 自然終了・channel 奪取 — engine が一度 stream を作った後で登録が外れた。
        assertTrue(PlaybackVoice.voiceGone(true, false, false));
    }

    @Test
    void liveChannelIsNotGone() {
        assertFalse(PlaybackVoice.voiceGone(true, true, false));
    }

    @Test
    void neverAcceptedIsNotGone() {
        // play を投げたが engine が stream をまだ作っていない / 作らずに捨てた。
        // その区間は受理確認と first-audio deadline が管轄するので、ここでは「生きている」。
        assertFalse(PlaybackVoice.voiceGone(false, false, false));
        assertFalse(PlaybackVoice.voiceGone(false, true, false));
    }

    @Test
    void desyncedSpeakerChildIsNotGone() {
        // 子の channel 喪失 (desync) は SpeakerVoiceGroup が branch を張り替えて再建する。
        // 「止まった」と答えると group の掃除が branch ごと畳んで再建が二度と効かなくなる。
        assertFalse(PlaybackVoice.voiceGone(true, false, true));
    }

    @Test
    void sweptZombieVoiceFreesItsSlotAndKey() {
        // 「止まった」と答え始めた voice は sweep で active から外れ、同時再生枠を返す。
        final PlaybackSessions<Long> sessions = new PlaybackSessions<>(() -> 0L);
        final var decision = sessions.start(KEY, null, 0L, 0, 0, false);
        final FlaggedVoice voice = new FlaggedVoice();
        assertTrue(sessions.install(KEY, decision.token(), "https://example.invalid/a", 0L, voice));

        assertEquals(1, sessions.sweep(), "生きている voice は掃除されず枠を数える");
        voice.gone = true; // stream 終端で channel が外れた (vanilla は stop() を呼ばない)
        assertEquals(0, sessions.sweep(), "止まった voice は掃除されて枠を返す");
        assertNull(sessions.activeVoice(KEY));
    }

    @Test
    void sameUrlResendReloadsAfterVoiceGone() {
        // 実害の経路そのもの: voice が「止まった」と答え始めた後の同 URL 再送は
        // start の dedup で捨てず load=true を返す (さもないと chunk 再入の再送が
        // 飲まれて、その client だけ無音のままになる — 実機で観測した不具合)。
        // gate は isVoiceStopped() で short-circuit するため track=null でも到達できる。
        final PlaybackSessions<Long> sessions = new PlaybackSessions<>(() -> 0L);
        final var first = sessions.start(KEY, null, 0L, 0, 0, false);
        assertTrue(first.load());
        final FlaggedVoice voice = new FlaggedVoice();
        assertTrue(sessions.install(KEY, first.token(), "https://example.invalid/a", 0L, voice));

        voice.gone = true; // channel が自然終了 (vanilla は SoundInstance#stop を呼ばない)
        final var resend = sessions.start(KEY, null, 60_000L, 0, 0, false);
        assertTrue(resend.load(), "止まった voice への同 URL 再送が dedup に飲まれた");
        assertTrue(voice.released, "畳まれた voice が stopAndRelease されず残っている");
    }
}
