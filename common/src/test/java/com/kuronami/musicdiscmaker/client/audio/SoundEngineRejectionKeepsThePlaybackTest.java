package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.minecraft.core.BlockPos;

/**
 * <b>これが今回の回帰テスト。</b> engine が受理しなかった時に、MDM が自分で音源を殺さないこと。
 * そして殺さなくても<b>垂れ流しにならない</b>こと。
 *
 * <h2>直した形</h2>
 * 拒否の後始末は {@code source.close()} を打っていた。ところが拒否は「もう鳴らない」の証明では
 * なく、{@code play} がチャンネルを作らずに返って数 ms 後に開栓する経路が実在する。そこで音源を
 * 閉じると、遅れて生まれたストリームは死んだソースから読んで 0 バイトを返し、{@code close} は
 * 理由を残さないので<b>理由の付かない完全な無音</b>になる (実機 2/2 で再現。
 * `_research/FIRST_PLAY_SILENT.md`)。無音は MDM の自傷だった。
 *
 * <h2>壊さない代わりに何が回収するか</h2>
 * {@link PlaybackSessions#FIRST_AUDIO_DEADLINE_MS} の期限。{@code ClientPlaybackManager} は
 * install の直後に張るので、受理されなかった再生もこの期限の下にいる。
 *
 * <p>その期限の入口 ({@link PlaybackSessions#firstAudioOverdue}) は<b>世代が生きていること</b>を
 * 要求する。だから拒否で世代を落としてはいけない — 落とすと期限が二度と発火せず、
 * 「拒否されたのに誰も閉じない音源」が残る。{@link #theDeadlineStillCollectsARejectedPlayback} が
 * そこを見張っている。
 */
class SoundEngineRejectionKeepsThePlaybackTest {

    private static final BlockPos KEY = new BlockPos(8, 64, -3);
    private static final CustomTrackData SONG =
            new CustomTrackData("http://example.invalid/song.mp3", "Song", "Artist", 40_000L, "", false);

    /** 停止されたかどうかだけを覚える音源。 */
    private static final class FakeVoice implements PlaybackVoice {

        private boolean stopped;

        @Override
        public boolean isStopped() {
            return stopped;
        }

        @Override
        public void setDirectional(boolean value) {
        }

        @Override
        public void setRangeBlocks(int value) {
        }

        @Override
        public void setVolumePercent(int value) {
        }

        @Override
        public void stopAndRelease() {
            stopped = true;
        }
    }

    /** 必ず受理しない engine (音量は 0 ではない = 利用者に直せる理由が無い側)。 */
    private static final SoundEngineAcceptance.Engine REJECTING = new SoundEngineAcceptance.Engine() {

        @Override
        public boolean playAndConfirm() {
            return false;
        }

        @Override
        public boolean mutedOut() {
            return false;
        }
    };

    /**
     * 受理せず、しかも<b>理由を聞かれると落ちる</b> engine。実機で起きた形の再現
     * ({@code play} が {@code resolve} へ到達せずに捨てた後、音量の参照が NPE になった)。
     */
    private static final SoundEngineAcceptance.Engine REJECTING_AND_BROKEN = new SoundEngineAcceptance.Engine() {

        @Override
        public boolean playAndConfirm() {
            return false;
        }

        @Override
        public boolean mutedOut() {
            throw new NullPointerException("Cannot invoke \"Sound.getVolume()\" because \"this.sound\" is null");
        }
    };

    private final AtomicLong clock = new AtomicLong();
    private final PlaybackSessions sessions = new PlaybackSessions(clock::get);
    private final PlaybackPrefetch<BlockPos> prefetch = new PlaybackPrefetch<>(clock::get);
    private final FakeVoice voice = new FakeVoice();
    private final List<PlaybackFailure> reported = new ArrayList<>();

    /** {@code ClientPlaybackManager} と同じ順番で 1 つの再生を登録する。 */
    private int install() {
        final PlaybackSessions.StartDecision decision = sessions.start(KEY, SONG, 0L, 0, 100, true);
        assertTrue(decision.load());
        assertTrue(sessions.install(KEY, decision.token(), SONG.url(), 0L, voice), "install が通っていない");
        return decision.token();
    }

    /** 受理されなかった時の経路を、後始末の選び方だけ変えて通す。 */
    private boolean play(int token, Runnable cleanup) {
        return play(REJECTING, token, cleanup);
    }

    /** {@code ClientPlaybackManager} の拒否経路と同じ繋ぎ方で engine を差し替えて通す。 */
    private boolean play(SoundEngineAcceptance.Engine engine, int token, Runnable cleanup) {
        return SoundEngineAcceptance.start(engine, cleanup, rejected -> {
            final PlaybackFailure show = sessions.engineRejected(KEY, token, rejected);
            if (show != null) {
                reported.add(show);
            }
        });
    }

    /**
     * 拒否されても音源を畳まないこと。<b>畳んだ瞬間に、遅れて開栓するストリームは死んだソースを
     * 読むしかなくなる。</b>
     */
    @Test
    void aRejectedPlaybackKeepsItsVoiceSoALatePlayCanStillBeHeard() {
        final int token = install();

        assertFalse(play(token, SoundEngineAcceptance.KEEP_UNTIL_DEADLINE),
                "受理されていないのに成功として返している (この後 Now Playing が出る)");

        assertFalse(voice.stopped,
                "拒否の後始末が音源を畳んでいる"
                        + " (遅れて開栓したストリームが死んだソースを読む = 理由の付かない無音)");
        assertEquals(1, reported.size(), "理由が報告されていない: " + reported);
        assertEquals(PlaybackFailure.Kind.SOUND_ENGINE, reported.get(0).kind(), "分類が化けている");
    }

    /**
     * <b>垂れ流さないことの固定。</b> 拒否された再生も 30 秒の期限の下にいて、実 PCM が一度も
     * 来なければ音源ごと畳まれること。
     *
     * <p>ここが落ちる時に壊れているのは期限そのものではなく<b>世代</b>である可能性が高い
     * ({@link PlaybackSessions#engineRejected} が {@code abandon} を呼ぶと
     * {@link PlaybackSessions#firstAudioOverdue} が入口で偽を返し、期限は永久に発火しない)。
     */
    @Test
    void theDeadlineStillCollectsARejectedPlayback() {
        final int token = install();
        play(token, SoundEngineAcceptance.KEEP_UNTIL_DEADLINE);

        assertTrue(sessions.isLive(KEY, token),
                "拒否で世代を落としている (この時点で 30 秒の期限は二度と発火しない = 誰も閉じない)");

        clock.set(PlaybackSessions.FIRST_AUDIO_DEADLINE_MS);
        assertTrue(sessions.firstAudioOverdue(KEY, token), "期限が発火していない");

        assertFalse(ClientPlaybackManager.releaseOnFirstAudioDeadline(sessions, prefetch, KEY, false));
        assertTrue(voice.stopped, "期限が来ても音源を畳んでいない (鳴らない音源を掴んだまま残る)");
    }

    /**
     * 拒否の後で実際に鳴り始めたら、期限は畳まないこと。<b>これが「壊さない」で救いたい当のもの。</b>
     *
     * <p>{@link PlaybackSessions#noteFirstAudio} が {@code true} を返すこと自体が、拒否の後も
     * 世代が生きている証拠になっている ("Now Playing" と失敗ラベルの取り消しはここに乗る)。
     */
    @Test
    void aRejectedPlaybackThatStartsMakingSoundIsLeftAlone() {
        final int token = install();
        play(token, SoundEngineAcceptance.KEEP_UNTIL_DEADLINE);

        assertTrue(sessions.noteFirstAudio(KEY, token),
                "遅れて鳴り始めた音が「停止済み / 世代違い」として捨てられている");

        clock.set(PlaybackSessions.FIRST_AUDIO_DEADLINE_MS * 2);
        assertFalse(sessions.firstAudioOverdue(KEY, token), "鳴っている再生を期限が畳もうとしている");
        assertFalse(voice.stopped);
    }

    /**
     * <b>理由の判定が落ちても、F1 の安全網が働くこと。</b>
     *
     * <p>実機で踏んだ形: 他 MOD が {@code play} を取り消したため {@code resolve} が走らず、
     * 拒否の理由を聞いた所 ({@code mutedOut}) が NPE を投げて、それが呼び出し元の task を
     * 中断させた。中断は<b>拒否より後ろにある処理を丸ごと落とす</b>ので、そこに回収の仕掛けが
     * 乗っていたら「誰も閉じない音源」に戻る。
     *
     * <p>ここが見るのは 3 つ。例外を外に出さないこと・報告を落とさないこと・そして
     * 30 秒の期限が変わらず効くこと。
     */
    @Test
    void aRejectionWhoseReasonCannotBeToldStillLeavesTheSafetyNetWorking() {
        final int token = install();

        assertFalse(play(REJECTING_AND_BROKEN, token, SoundEngineAcceptance.KEEP_UNTIL_DEADLINE),
                "理由の判定が落ちたせいで例外が外へ出ている (呼び出し元の task ごと中断する)");

        assertEquals(1, reported.size(), "理由が分からないことを口実に報告ごと捨てている: " + reported);
        assertEquals(PlaybackFailure.Kind.SOUND_ENGINE, reported.get(0).kind(),
                "分からない時は一般の拒否として出すこと");

        clock.set(PlaybackSessions.FIRST_AUDIO_DEADLINE_MS);
        assertTrue(sessions.firstAudioOverdue(KEY, token), "期限が効かなくなっている");
        ClientPlaybackManager.releaseOnFirstAudioDeadline(sessions, prefetch, KEY, false);
        assertTrue(voice.stopped, "期限が来ても音源を畳んでいない");
    }

    /** 理由の判定が落ちても、期限を持たない呼び出し側の後始末は先に走り切っていること。 */
    @Test
    void aCallerWithoutADeadlineStillFoldsTheVoiceWhenTheReasonCannotBeTold() {
        final int token = install();

        assertFalse(play(REJECTING_AND_BROKEN, token, voice::stopAndRelease));

        assertTrue(voice.stopped, "理由の判定が落ちた時に後始末が飛ばされている");
    }

    /**
     * 期限を持たない呼び出し側 (compat 経路 = Create / Sable) は、従来どおり自分で畳むこと。
     * <b>{@link SoundEngineAcceptance#KEEP_UNTIL_DEADLINE} を渡してよいのは、回収する仕組みを
     * 持っている側だけ。</b>
     */
    @Test
    void aCallerWithoutADeadlineStillFoldsTheVoiceItself() {
        final int token = install();

        assertFalse(play(token, voice::stopAndRelease));

        assertTrue(voice.stopped, "期限を持たない経路で音源を掴んだまま残している");
    }
}
