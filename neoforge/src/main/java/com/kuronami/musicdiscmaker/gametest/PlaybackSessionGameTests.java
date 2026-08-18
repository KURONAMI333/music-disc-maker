package com.kuronami.musicdiscmaker.gametest;

import java.util.concurrent.atomic.AtomicLong;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailure;
import com.kuronami.musicdiscmaker.client.audio.PlaybackSessions;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 同じ座標でディスクを差し替えたときに 2 曲同時に鳴らないことを固定する。
 *
 * <h2>なぜ既存の {@code PlaybackGenerationGameTests} では捕まらなかったか</h2>
 * あちらは世代という<b>部品</b>が正しく動くことを見ており、実際ずっと緑だった。それでも
 * {@code ClientPlaybackManager} は世代を使わず「座標が {@code wanted} にあるか」で
 * 「まだ鳴らしていいか」を答えていたので、バグは残った。<b>部品のテストは、部品が使われて
 * いることを保証しない。</b>
 *
 * <p>だからここでは {@code ClientPlaybackManager} が実際に呼ぶ順番のまま
 * {@link PlaybackSessions} を叩く。差し替え・停止・シーク・ラジオ再接続の判断は全部
 * このクラスに移してあるので、ここが緑ならその判断は現実の経路のものになる。
 *
 * <p>時計は差し替える (ラジオの安定判定を実時間で待たない)。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class PlaybackSessionGameTests {

    private static final String TEMPLATE = "empty8x3x8";
    private static final BlockPos JUKEBOX = new BlockPos(3, 1, 3);

    /**
     * 偽時計の起点。<b>0 にしない</b> — 0 は「再生開始時刻が未記録」の sentinel なので、
     * 0 から始めると実機では起きない「常に未記録」の条件でテストすることになる
     * (実際、安定判定のテストがそれで空振りした)。
     */
    private static final long CLOCK_START = 1_700_000_000_000L;

    private static final CustomTrackData TRACK_A =
            new CustomTrackData("https://example.invalid/a.mp3", "A", "artist", 180_000L, "", false);
    private static final CustomTrackData TRACK_B =
            new CustomTrackData("https://example.invalid/b.mp3", "B", "artist", 180_000L, "", false);
    private static final CustomTrackData RADIO =
            new CustomTrackData("https://example.invalid/live", "Radio", "station", 0L, "", true);

    /**
     * <b>これが今回の回帰テスト (完了順 A → B)。</b> A のロード中に B へ差し替え、A が先に
     * 完了してから B が完了する順番。
     *
     * <p>座標だけで判定していた頃はここで A も鳴り始め、{@code active} は後から書いた B しか
     * 覚えていないので A は二度と止められなかった。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void swappingTheDiscMidLoadDropsTheOldLoadWhenItFinishesFirst(GameTestHelper helper) {
        final PlaybackSessions sessions = new PlaybackSessions(() -> CLOCK_START);

        final PlaybackSessions.StartDecision first = sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, true);
        helper.assertTrue(first.load(), "最初の再生がロードに進んでいない");

        // まだ A のロードが終わらないうちにディスクを差し替える。
        final PlaybackSessions.StartDecision second = sessions.start(JUKEBOX, TRACK_B, 0L, 0, 100, true);
        helper.assertTrue(second.load(), "差し替えがロードに進んでいない");

        helper.assertFalse(sessions.isLive(JUKEBOX, first.token()),
                "差し替え後も古いロードが有効なままになっている (= 座標だけで判定している)");

        // A が先に完了する。
        final FakeVoice voiceA = new FakeVoice("A");
        helper.assertFalse(sessions.install(JUKEBOX, first.token(), TRACK_A.url(), 0L, voiceA),
                "差し替えられた古い曲が登録を受け付けられている (2 曲同時に鳴る形)");

        // 続いて B が完了する。
        final FakeVoice voiceB = new FakeVoice("B");
        helper.assertTrue(sessions.install(JUKEBOX, second.token(), TRACK_B.url(), 0L, voiceB),
                "差し替え後の曲まで捨てられている (差し替えると何も鳴らなくなる)");
        helper.assertFalse(voiceB.stopped, "鳴らすべき曲が止められている");
        helper.succeed();
    }

    /**
     * <b>完了順の逆 (B → A)。</b> 差し替え後の B が先に鳴り始め、遅れて A が完了する順番。
     * A は捨てられ、鳴っている B は止められてはならない。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void swappingTheDiscMidLoadDropsTheOldLoadWhenItFinishesLast(GameTestHelper helper) {
        final PlaybackSessions sessions = new PlaybackSessions(() -> CLOCK_START);

        final PlaybackSessions.StartDecision first = sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, true);
        final PlaybackSessions.StartDecision second = sessions.start(JUKEBOX, TRACK_B, 0L, 0, 100, true);

        final FakeVoice voiceB = new FakeVoice("B");
        helper.assertTrue(sessions.install(JUKEBOX, second.token(), TRACK_B.url(), 0L, voiceB),
                "差し替え後の曲が登録できていない");

        final FakeVoice voiceA = new FakeVoice("A");
        helper.assertFalse(sessions.install(JUKEBOX, first.token(), TRACK_A.url(), 0L, voiceA),
                "遅れて完了した古い曲が登録を受け付けられている (鳴っている曲に重なる)");
        helper.assertFalse(voiceB.stopped,
                "遅れて完了した古い曲が、鳴っている曲を止めている (差し替えると無音になる形)");
        helper.succeed();
    }

    /**
     * 世代判定をすり抜けた同時完了の防波堤。同じ世代で 2 つ登録されたら、<b>前のものは必ず
     * 止めてから</b>置き換えること。ここが素の上書きだと、覚えていない方が鳴りっぱなしになる。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void displacedVoiceIsStoppedNotJustForgotten(GameTestHelper helper) {
        final PlaybackSessions sessions = new PlaybackSessions(() -> CLOCK_START);
        final PlaybackSessions.StartDecision start = sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, true);

        final FakeVoice firstVoice = new FakeVoice("first");
        final FakeVoice secondVoice = new FakeVoice("second");
        helper.assertTrue(sessions.install(JUKEBOX, start.token(), TRACK_A.url(), 0L, firstVoice), "1 本目");
        helper.assertTrue(sessions.install(JUKEBOX, start.token(), TRACK_A.url(), 0L, secondVoice), "2 本目");

        helper.assertTrue(firstVoice.stopped,
                "上書きされた音源が止められていない (管理外で鳴り続ける = 止める手段が無くなる)");
        helper.assertFalse(secondVoice.stopped, "残すべき音源まで止めている");
        helper.succeed();
    }

    /** 停止したら、ロード中の要求も完了時点で捨てられること (停止が空振りしない)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void stoppingDuringLoadDiscardsTheSourceOnArrival(GameTestHelper helper) {
        final PlaybackSessions sessions = new PlaybackSessions(() -> CLOCK_START);
        final PlaybackSessions.StartDecision start = sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, true);

        sessions.stop(JUKEBOX); // ディスク撤去 (この時点で鳴っている音源はまだ無い)

        helper.assertFalse(sessions.isLive(JUKEBOX, start.token()),
                "停止後もロードが有効なまま (数秒後に鳴り出す)");
        helper.assertFalse(sessions.install(JUKEBOX, start.token(), TRACK_A.url(), 0L, new FakeVoice("late")),
                "停止したのにロード完了で鳴り始めている");
        helper.succeed();
    }

    /**
     * chunk 再入の再送は鳴らし直さず、聴取モデルだけその場で取り込むこと。
     * ここが「毎回ロードし直す」に倒れると、遠くから戻るたびに音が飛ぶ。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void chunkResendKeepsPlayingAndAppliesTheListeningModel(GameTestHelper helper) {
        final PlaybackSessions sessions = new PlaybackSessions(() -> CLOCK_START);
        final PlaybackSessions.StartDecision start = sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, true);
        final FakeVoice voice = new FakeVoice("playing");
        sessions.install(JUKEBOX, start.token(), TRACK_A.url(), 0L, voice);

        // 同じ曲・同じ推定位置での再送 (server が現在の経過 ms を載せて送り直す形)。
        final PlaybackSessions.StartDecision resend = sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, false);
        helper.assertFalse(resend.load(), "chunk 再入の再送で鳴らし直している (音飛び・無駄な再バッファ)");
        helper.assertFalse(voice.directional, "再送で届いた聴取モデルが反映されていない");
        helper.assertFalse(voice.stopped, "再送で鳴っている音を止めている");
        helper.succeed();
    }

    /** GUI のシーク (現在位置から大きく離れた頭出し) は dedup せず、鳴らし直すこと。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void seekingRestartsInsteadOfDeduplicating(GameTestHelper helper) {
        final PlaybackSessions sessions = new PlaybackSessions(() -> CLOCK_START);
        final PlaybackSessions.StartDecision start = sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, true);
        final FakeVoice voice = new FakeVoice("playing");
        sessions.install(JUKEBOX, start.token(), TRACK_A.url(), 0L, voice);

        final PlaybackSessions.StartDecision seek = sessions.start(JUKEBOX, TRACK_A, 60_000L, 0, 100, true);
        helper.assertTrue(seek.load(), "シークが dedup されている (シークバーが効かない)");
        helper.assertTrue(voice.stopped, "シークで前の再生が止められていない (2 箇所から鳴る)");
        helper.succeed();
    }

    /**
     * ラジオの瞬断は上限まで再接続し、超えたら諦めて理由を 1 度だけ出すこと。
     * 諦めた後は世代が進むので、遅れて届く報告は黙る。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void radioReconnectsUpToTheLimitThenGivesUpWithAReason(GameTestHelper helper) {
        final AtomicLong now = new AtomicLong(CLOCK_START);
        final PlaybackSessions sessions = new PlaybackSessions(now::get);
        final PlaybackSessions.StartDecision start = sessions.start(JUKEBOX, RADIO, 0L, 0, 100, true);
        sessions.install(JUKEBOX, start.token(), RADIO.url(), 0L, new FakeVoice("radio"));

        // 瞬断のたびに拾った理由は、諦めるまでは黙って抱えておく (再接続表示と二重にしない)。
        final PlaybackFailure why = PlaybackFailure.ofReason(FailureReason.CONNECTION_FAILED, "read timed out");
        helper.assertTrue(sessions.lateFailure(JUKEBOX, start.token(), RADIO, why) == null,
                "再接続する気がある間にラジオの瞬断をチャットへ出している");

        for (int attempt = 1; attempt <= PlaybackSessions.MAX_RECONNECT; attempt++) {
            final PlaybackSessions.Reconnect next = sessions.radioStreamEnded(JUKEBOX, start.token());
            helper.assertTrue(next.kind() == PlaybackSessions.ReconnectKind.RETRY,
                    attempt + " 回目で再接続をやめている: " + next.kind());
            helper.assertTrue(next.attempt() == attempt, "再接続の回数が " + next.attempt() + " になっている");
            // 再接続のロードは同じ世代のまま進む (ここで世代を落とすと自分自身を打ち消す)。
            helper.assertTrue(sessions.isLive(JUKEBOX, start.token()), "再接続の途中で世代が落ちている");
            sessions.install(JUKEBOX, start.token(), RADIO.url(), 0L, new FakeVoice("radio" + attempt));
        }

        final PlaybackSessions.Reconnect giveUp = sessions.radioStreamEnded(JUKEBOX, start.token());
        helper.assertTrue(giveUp.kind() == PlaybackSessions.ReconnectKind.GIVE_UP,
                "上限を超えても再接続し続けている: " + giveUp.kind());
        helper.assertTrue(giveUp.why() != null, "諦めた理由が出ていない (なぜ止まったか分からない)");
        helper.assertTrue(giveUp.why().kind() == PlaybackFailure.Kind.NETWORK,
                "抱えていた理由が化けている");
        helper.assertFalse(sessions.isLive(JUKEBOX, start.token()),
                "諦めた後も世代が生きている (後から届く報告が漏れる)");
        helper.succeed();
    }

    /** 安定して鳴っていた後の瞬断は、試行回数をリセットして数え直すこと。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void aStableStretchResetsTheReconnectCounter(GameTestHelper helper) {
        final AtomicLong now = new AtomicLong(CLOCK_START);
        final PlaybackSessions sessions = new PlaybackSessions(now::get);
        final PlaybackSessions.StartDecision start = sessions.start(JUKEBOX, RADIO, 0L, 0, 100, true);
        sessions.install(JUKEBOX, start.token(), RADIO.url(), 0L, new FakeVoice("radio"));

        helper.assertTrue(sessions.radioStreamEnded(JUKEBOX, start.token()).attempt() == 1, "1 回目");
        sessions.install(JUKEBOX, start.token(), RADIO.url(), 0L, new FakeVoice("radio2"));
        helper.assertTrue(sessions.radioStreamEnded(JUKEBOX, start.token()).attempt() == 2, "2 回目");

        // 今度は長く安定して鳴った後で切れる。
        sessions.install(JUKEBOX, start.token(), RADIO.url(), 0L, new FakeVoice("radio3"));
        now.addAndGet(PlaybackSessions.STABLE_MS);
        helper.assertTrue(sessions.radioStreamEnded(JUKEBOX, start.token()).attempt() == 1,
                "安定再生の後なのに試行回数が繰り越されている (長時間つけっぱなしで必ず諦める形)");
        helper.succeed();
    }

    /**
     * ワールド退出 (切断) で、まだロード中の要求も含めて全部無効化されること。
     *
     * <p>ここが漏れると、client が畳まれた後にロードが完了して {@code Minecraft} を触りに行く。
     * 一度も {@code install} されていない = どの再生マップにも現れない要求が対象なので、
     * 「鳴っているものを畳む」だけの実装では取り落とす。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void stopAllInvalidatesLoadsThatNeverStartedPlaying(GameTestHelper helper) {
        final PlaybackSessions sessions = new PlaybackSessions(() -> CLOCK_START);
        final PlaybackSessions.StartDecision start = sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, true);

        sessions.stopAll(); // ワールド退出 (この時点で鳴っている音源は 1 つも無い)

        helper.assertFalse(sessions.isLive(JUKEBOX, start.token()),
                "切断後もロードが有効なまま (完了時に畳まれた client を触りに行く)");
        helper.assertFalse(sessions.install(JUKEBOX, start.token(), TRACK_A.url(), 0L, new FakeVoice("late")),
                "切断後に完了したロードが鳴り始めている");
        helper.succeed();
    }

    /**
     * 同じ失敗の重複抑止が、chunk 再入の再送で忘れられないこと。
     *
     * <p>再送は「鳴らし直さない」経路なので、ここで抑止の記憶を消してはいけない。消すと、
     * ラジオが同じ理由で落ち直すたびにチャットへ同じ行が積まれる (再送は 1 秒間隔で来る)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void aChunkResendDoesNotForgetTheDuplicateSuppression(GameTestHelper helper) {
        final PlaybackSessions sessions = new PlaybackSessions(() -> CLOCK_START);
        final PlaybackSessions.StartDecision start = sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, true);
        sessions.install(JUKEBOX, start.token(), TRACK_A.url(), 0L, new FakeVoice("playing"));
        final PlaybackFailure failure = PlaybackFailure.ofReason(FailureReason.BOT_CHECK, "requires login");

        helper.assertTrue(sessions.lateFailure(JUKEBOX, start.token(), TRACK_A, failure) == failure, "初回は出す");
        helper.assertTrue(sessions.lateFailure(JUKEBOX, start.token(), TRACK_A, failure) == null, "2 回目は黙る");

        // chunk 再入の再送 (鳴らし直さない経路)。
        helper.assertFalse(sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, true).load(), "再送が dedup されていない");

        helper.assertTrue(sessions.lateFailure(JUKEBOX, start.token(), TRACK_A, failure) == null,
                "再送で重複抑止が忘れられている (同じ行がチャットに積まれる)");
        helper.succeed();
    }

    /** 止めた再生の後始末では黙ること (鳴らしていない再生の失敗をチャットに出さない)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void failuresFromAStoppedPlaybackStaySilent(GameTestHelper helper) {
        final PlaybackSessions sessions = new PlaybackSessions(() -> CLOCK_START);
        final PlaybackSessions.StartDecision start = sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, true);
        final PlaybackFailure failure = PlaybackFailure.ofReason(FailureReason.BOT_CHECK, "requires login");

        helper.assertTrue(sessions.lateFailure(JUKEBOX, start.token(), TRACK_A, failure) == failure,
                "鳴らしている再生の失敗が握り潰されている");
        // 2 度目は重複抑止で黙る (ラジオが同じ理由で落ち直しても行が積まれない)。
        helper.assertTrue(sessions.lateFailure(JUKEBOX, start.token(), TRACK_A, failure) == null,
                "同じ失敗が繰り返しチャットに出ている");

        sessions.stop(JUKEBOX);
        helper.assertTrue(sessions.lateFailure(JUKEBOX, start.token(), TRACK_A, failure) == null,
                "止めた再生の失敗がチャットに出ている");
        helper.succeed();
    }

    /**
     * <b>これが今回の回帰テスト。</b> engine が受理しなかった理由は、鳴り始めるまで 1 回だけ
     * 出すこと。
     *
     * <p>音量 0 のような理由は直るまで<b>何度やっても同じところで弾かれる</b>。出すこと自体は
     * 正しい (誤って 0 にしている人には必要な情報) が、毎回出すと意図してミュートしている人には
     * ただのノイズになる。だから頻度の問題として扱う。
     *
     * <p>既存の {@code notices} に乗せてもこれは実現しない — あちらは {@code stop} で忘れ、
     * 再生要求は必ず {@code stop} を通ってから新しい世代を起こすので、chunk 再入のたびに
     * 「初めての失敗」に戻る。ここではその再入をそのまま再現している。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void anEngineRejectionIsReportedOnceUntilItActuallyPlays(GameTestHelper helper) {
        final PlaybackSessions sessions = new PlaybackSessions(() -> CLOCK_START);
        final PlaybackFailure muted = PlaybackFailure.soundMuted();

        final PlaybackSessions.StartDecision first = sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, true);
        helper.assertTrue(sessions.engineRejected(JUKEBOX, first.token(), muted) == muted,
                "最初の拒否が黙っている (誤って音量を 0 にしている人が気づけない)");

        // chunk 再入の再送。stop -> 新しい世代、を通る現実の順番。
        final PlaybackSessions.StartDecision second = sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, true);
        helper.assertTrue(sessions.engineRejected(JUKEBOX, second.token(), muted) == null,
                "同じ理由が再生要求のたびにチャットへ積まれている");

        // 理由が変われば別の情報なので通す。
        final PlaybackSessions.StartDecision third = sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, true);
        final PlaybackFailure engine = PlaybackFailure.soundEngineRejected();
        helper.assertTrue(sessions.engineRejected(JUKEBOX, third.token(), engine) == engine,
                "理由が変わったのに黙っている");
        helper.succeed();
    }

    /** 一度鳴り始めたら記憶を捨てること (直った後にまた弾かれたら、それは新しい出来事)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void anEngineRejectionIsReportedAgainAfterPlaybackSucceeded(GameTestHelper helper) {
        final PlaybackSessions sessions = new PlaybackSessions(() -> CLOCK_START);
        final PlaybackFailure muted = PlaybackFailure.soundMuted();

        final PlaybackSessions.StartDecision first = sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, true);
        helper.assertTrue(sessions.engineRejected(JUKEBOX, first.token(), muted) == muted, "最初の拒否");

        // 音量を戻して鳴った。
        final PlaybackSessions.StartDecision second = sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, true);
        helper.assertTrue(sessions.install(JUKEBOX, second.token(), TRACK_A.url(), 0L, new FakeVoice("A")),
                "install が通っていない");
        sessions.engineAccepted(JUKEBOX);

        // また 0 にした。同じラベルでも新しい出来事なので出す。
        final PlaybackSessions.StartDecision third = sessions.start(JUKEBOX, TRACK_A, 0L, 0, 100, true);
        helper.assertTrue(sessions.engineRejected(JUKEBOX, third.token(), muted) == muted,
                "鳴った後にまた弾かれたのに黙っている (抑止が解けていない)");
        helper.succeed();
    }
}
