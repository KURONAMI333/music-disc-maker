package com.kuronami.musicdiscmaker.gametest;

import java.util.ArrayList;
import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailure;
import com.kuronami.musicdiscmaker.client.audio.SoundEngineAcceptance;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * {@code SoundManager.play} が黙って捨てた再生を、利用者に届くところまで固定する。
 *
 * <h2>これが要る理由</h2>
 * {@code SoundEngine#play} は戻り値を持たず、8 通りの経路で黙って捨てる
 * ({@link SoundEngineAcceptance} の javadoc)。呼び出し側は受理を確認せずに session を install し
 * "Now Playing" まで出していたので、症状は報告そのもの — <b>再生中と出るのに鳴らない</b>。
 * 同じ URL の再通知は「既に鳴っている」で弾かれるため、停止か差し替えまで固定される。
 *
 * <p>engine そのもの ({@code Minecraft.getInstance().getSoundManager()}) は dedicated server では
 * 触れないので、ここが固定するのは<b>受理されなかった時に何をするか</b> — 音源を畳むこと、
 * 理由を 1 回だけ報告すること、そして呼び出し側に「先へ進むな」と答えること。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class SoundEngineAcceptanceGameTests {

    private static final String TEMPLATE = "empty8x3x8";

    /** engine が受理した時は何も報告せず、音源も畳まないこと。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void anAcceptedSoundIsLeftAlone(GameTestHelper helper) {
        final FakeVoice voice = new FakeVoice("accepted");
        final List<PlaybackFailure> reports = new ArrayList<>();

        final boolean started = SoundEngineAcceptance.start(
                new FakeEngine(true, false), voice, reports::add);

        helper.assertTrue(started, "受理された再生を失敗として扱っている");
        helper.assertTrue(reports.isEmpty(), "鳴っているのに失敗を報告している: " + reports);
        helper.assertTrue(!voice.stopped, "受理された音源を畳んでいる");
        helper.succeed();
    }

    /**
     * <b>これが今回の回帰テスト。</b> engine が捨てたら、呼び出し側は先へ進めず、音源は畳まれ、
     * 理由が報告されること。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void aRejectedSoundIsReportedInsteadOfShowingNowPlaying(GameTestHelper helper) {
        final FakeVoice voice = new FakeVoice("rejected");
        final List<PlaybackFailure> reports = new ArrayList<>();

        final boolean started = SoundEngineAcceptance.start(
                new FakeEngine(false, false), voice, reports::add);

        helper.assertTrue(!started,
                "engine が捨てたのに再生成功として返している (この後 Now Playing が出る)");
        helper.assertTrue(voice.stopped,
                "鳴っていない音源を掴んだままにしている (同時再生数を消費し、再通知も弾かれる)");
        helper.assertTrue(reports.size() == 1, "報告が 1 件でない: " + reports.size());
        helper.assertTrue(reports.get(0).kind() == PlaybackFailure.Kind.SOUND_ENGINE,
                "分類が化けている: " + reports.get(0).kind());
        helper.succeed();
    }

    /**
     * 音量 0 で捨てられた時は、そう名指しすること。engine の拒否のうち
     * <b>利用者が自分で直せる唯一のもの</b>で、「原因不明」と出しても何の助けにもならない。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void aSoundDroppedForZeroVolumeSaysSo(GameTestHelper helper) {
        final FakeVoice voice = new FakeVoice("muted");
        final List<PlaybackFailure> reports = new ArrayList<>();

        SoundEngineAcceptance.start(new FakeEngine(false, true), voice, reports::add);

        helper.assertTrue(reports.size() == 1, "報告が 1 件でない: " + reports.size());
        helper.assertTrue(reports.get(0).kind() == PlaybackFailure.Kind.MUTED,
                "音量 0 を原因不明として出している: " + reports.get(0).kind());
        helper.succeed();
    }

    /** engine の代役。本物は {@code Minecraft} を掴んでいて dedicated server では読めない。 */
    private record FakeEngine(boolean accept, boolean muted) implements SoundEngineAcceptance.Engine {

        @Override
        public boolean playAndConfirm() {
            return accept;
        }

        @Override
        public boolean mutedOut() {
            return muted;
        }
    }
}
