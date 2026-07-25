package com.kuronami.musicdiscmaker.gametest;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.PlaybackSessions;
import com.kuronami.musicdiscmaker.client.audio.PlaybackSessions.LoadOutcome;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * client 再生ライフサイクルの headless テスト。
 *
 * <p>{@code ClientPlaybackManager} 本体は {@code Minecraft} を掴んでいて dedicated server では
 * 読めないので、世代管理だけを取り出した {@link PlaybackSessions} を通して固定する
 * （{@code SpeakerSelection} と同じ seam の切り方）。
 *
 * <p>ここが無いと「再生要求から音が立つまでの窓に 2 通目が来る」経路が一切機械判定されない。
 * 実際にその窓を通り抜けた 2 本目が 1 本目を bookkeeping から押し出し、押し出された方が
 * 停止できない孤児として鳴り続ける、という形で出荷版に載った。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class PlaybackLifecycleGameTests {

    private static final String TEMPLATE = "empty8x3x8";
    private static final BlockPos KEY = new BlockPos(10, 64, 10);
    private static final BlockPos OTHER = new BlockPos(20, 64, 20);

    /**
     * 停止を挟まない 2 連続の再生要求（＝同一 payload の重複配送・連続シーク）で、
     * 設置されるインスタンスは 1 本だけであること。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void backToBackRequestsInstallOnlyOne(GameTestHelper helper) {
        final PlaybackSessions<BlockPos> sessions = new PlaybackSessions<>();
        final long first = sessions.begin(KEY);
        final long second = sessions.begin(KEY);

        helper.assertTrue(first != second, "2 つの要求に同じトークンが配られている");
        helper.assertTrue(sessions.onLoadComplete(KEY, first) == LoadOutcome.DISCARD,
                "追い越された 1 本目のロードが設置されようとしている（孤児の作り方そのもの）");
        helper.assertTrue(sessions.onLoadComplete(KEY, second) == LoadOutcome.INSTALL,
                "最新の要求のロードが設置されない");
        helper.succeed();
    }

    /**
     * ロード完了が<b>逆順</b>で返っても、生き残るのは最後の要求のインスタンスだけであること。
     * URL 解決の所要時間はソース側の都合で決まるので、着地順は要求順と一致しない。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void outOfOrderCompletionKeepsNewest(GameTestHelper helper) {
        final PlaybackSessions<BlockPos> sessions = new PlaybackSessions<>();
        final long first = sessions.begin(KEY);
        final long second = sessions.begin(KEY);

        // 2 本目が先に着地 → 設置される。
        helper.assertTrue(sessions.onLoadComplete(KEY, second) == LoadOutcome.INSTALL,
                "先に着地した最新のロードが設置されない");
        // 後から 1 本目が着地しても、最新は 2 本目のままなので捨てられる。
        helper.assertTrue(sessions.onLoadComplete(KEY, first) == LoadOutcome.DISCARD,
                "遅れて着地した古いロードが最新を上書きしようとしている");
        helper.assertTrue(sessions.isCurrent(KEY, second), "最新のトークンが入れ替わっている");
        helper.succeed();
    }

    /** 停止した後に古いロードが完了しても再生が始まらないこと。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void loadCompletingAfterStopDoesNotStart(GameTestHelper helper) {
        final PlaybackSessions<BlockPos> sessions = new PlaybackSessions<>();
        final long token = sessions.begin(KEY);
        sessions.cancel(KEY);

        helper.assertFalse(sessions.isWanted(KEY), "停止後も再生要求が生きている");
        helper.assertTrue(sessions.onLoadComplete(KEY, token) == LoadOutcome.DISCARD,
                "停止後に着地したロードが再生を始めようとしている");
        helper.assertTrue(sessions.currentToken(KEY) == 0L, "停止後にトークンが残っている");
        helper.succeed();
    }

    /**
     * 停止 (と切断) は、その位置の飛行中のロードを全て無効化すること。位置ごとに独立であること。
     * 「取り出しても止まらない」「切断してもゾンビが鳴る」はここが緩いと必ず出る。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void cancelIsPerPositionAndCancelAllClearsEverything(GameTestHelper helper) {
        final PlaybackSessions<BlockPos> sessions = new PlaybackSessions<>();
        final long here = sessions.begin(KEY);
        final long there = sessions.begin(OTHER);

        sessions.cancel(KEY);
        helper.assertTrue(sessions.onLoadComplete(KEY, here) == LoadOutcome.DISCARD,
                "停止した位置のロードが生きている");
        helper.assertTrue(sessions.onLoadComplete(OTHER, there) == LoadOutcome.INSTALL,
                "別の位置の再生要求まで巻き込んで止めている");

        sessions.cancelAll();
        helper.assertFalse(sessions.isWanted(OTHER), "切断後も再生要求が生きている");
        helper.assertTrue(sessions.onLoadComplete(OTHER, there) == LoadOutcome.DISCARD,
                "切断後に着地したロードが再生を始めようとしている");
        helper.succeed();
    }

    /**
     * ラジオ再接続は「同じ要求の続き」なので新しい世代を起こさないこと。起こすと、server 由来の
     * 新しい再生要求が来た後の再接続ロードが勝ってしまい、古い曲が鳴り直す。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void radioReconnectReusesTokenAndLosesToNewerRequest(GameTestHelper helper) {
        final PlaybackSessions<BlockPos> sessions = new PlaybackSessions<>();
        final long token = sessions.begin(KEY);

        // 再接続はトークンを取り直さない。
        helper.assertTrue(sessions.currentToken(KEY) == token, "再接続の基準トークンが取れない");
        helper.assertTrue(sessions.onLoadComplete(KEY, token) == LoadOutcome.INSTALL,
                "再接続のロードが設置されない");

        // server 由来の新しい再生要求が割り込むと、飛行中の再接続ロードは自滅する。
        sessions.begin(KEY);
        helper.assertTrue(sessions.onLoadComplete(KEY, token) == LoadOutcome.DISCARD,
                "新しい再生要求より後に着地した再接続ロードが勝っている");
        helper.succeed();
    }
}
