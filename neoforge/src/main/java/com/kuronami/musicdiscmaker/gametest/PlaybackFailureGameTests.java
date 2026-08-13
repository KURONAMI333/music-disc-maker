package com.kuronami.musicdiscmaker.gametest;

import java.util.HashSet;
import java.util.Set;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailure;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailure.Kind;
import com.kuronami.musicdiscmaker.network.UrlGuard;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 「なぜ鳴らないか」の分類 ({@link PlaybackFailure}) の headless テスト。
 *
 * <p>ストリームを開く経路は失敗の種類を全部 {@code null} に潰して返すので、分類を
 * <b>投げられた例外とガードの拒否理由から復元する</b>のがこのクラスの仕事。ここが緩むと、
 * 利用者に出る文面が「鳴りませんでした」1 種類に戻る = 片側だけ無音の切り分けができなくなる。
 *
 * <p>{@code ClientPlaybackManager} 本体は {@code Minecraft} を掴んでいて dedicated server では
 * 読めないので、分類だけを取り出した純ロジックを通して固定する ({@code PlaybackSessions} /
 * {@code PlaybackPositions} と同じ seam の切り方)。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class PlaybackFailureGameTests {

    private static final String TEMPLATE = "empty8x3x8";

    /**
     * ガードの拒否理由が分類へ落ちること。<b>{@code UNRESOLVABLE} だけは
     * {@link Kind#NETWORK}</b> — 「URL が悪い」ではなく「この client からホストが引けない」で、
     * 片方の player だけ無音になる典型がこれ。ここを {@code BLOCKED_URL} に混ぜると、
     * 利用者は自分の回線を疑う手がかりを失う。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void guardReasonsMapToKinds(GameTestHelper helper) {
        helper.assertTrue(PlaybackFailure.blocked(UrlGuard.Reason.INVALID_URL).kind() == Kind.BLOCKED_URL,
                "解析不能な URL が BLOCKED_URL に落ちていない");
        helper.assertTrue(
                PlaybackFailure.blocked(UrlGuard.Reason.DISALLOWED_SCHEME).kind() == Kind.BLOCKED_URL,
                "scheme 違反が BLOCKED_URL に落ちていない");
        helper.assertTrue(PlaybackFailure.blocked(UrlGuard.Reason.BLOCKED_HOST).kind() == Kind.BLOCKED_URL,
                "内部 IP 遮断が BLOCKED_URL に落ちていない");
        helper.assertTrue(PlaybackFailure.blocked(UrlGuard.Reason.UNRESOLVABLE).kind() == Kind.NETWORK,
                "DNS 解決失敗が NETWORK ではなく URL の問題として分類されている");
        // 理由が失われた経路でも分類は返る (文面を出さない = 無音に戻る、を防ぐ)。
        helper.assertTrue(PlaybackFailure.blocked(null).kind() == Kind.BLOCKED_URL,
                "理由不明のガード拒否で分類が落ちている");
        // 拒否理由そのものが詳細として残ること (利用者がそのまま報告に貼れる)。
        helper.assertTrue(PlaybackFailure.blocked(UrlGuard.Reason.BLOCKED_HOST).label().contains("BLOCKED_HOST"),
                "ラベルに拒否理由が残っていない");
        helper.succeed();
    }

    /**
     * ネットワーク由来の例外が {@link Kind#NETWORK} になること。JDK の型階層で拾える分
     * ({@code SocketTimeoutException} は {@code InterruptedIOException} の子、
     * {@code ConnectException} は {@code SocketException} の子) と、隔離 classloader 側の
     * 独自例外をクラス名で拾う分の両方を固定する。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void networkExceptionsAreClassifiedAsNetwork(GameTestHelper helper) {
        helper.assertTrue(PlaybackFailure.thrown(new java.net.UnknownHostException("example.invalid"))
                .kind() == Kind.NETWORK, "DNS 解決失敗が NETWORK に落ちていない");
        helper.assertTrue(PlaybackFailure.thrown(new java.net.SocketTimeoutException("Read timed out"))
                .kind() == Kind.NETWORK, "読み取りタイムアウトが NETWORK に落ちていない");
        helper.assertTrue(PlaybackFailure.thrown(new java.net.ConnectException("Connection refused"))
                .kind() == Kind.NETWORK, "接続拒否が NETWORK に落ちていない");
        // 原因の連鎖の奥にあっても拾う (ローダーは自前の例外で包んで投げてくる)。
        helper.assertTrue(PlaybackFailure.thrown(
                        new RuntimeException("load failed", new java.net.UnknownHostException("x")))
                .kind() == Kind.NETWORK, "包まれたネットワーク例外を拾えていない");
        // クラス名でも拾う (隔離 classloader 側の型は instanceof が効かない)。
        helper.assertTrue(PlaybackFailure.thrown(new HttpConnectionTimeoutException()).kind() == Kind.NETWORK,
                "クラス名によるネットワーク判定が効いていない");
        helper.succeed();
    }

    /** ネットワーク以外の例外は {@link Kind#OPEN_ERROR}。詳細に例外の実体が残ること。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void otherExceptionsCarryTheirRootCause(GameTestHelper helper) {
        final PlaybackFailure failure = PlaybackFailure.thrown(
                new RuntimeException("wrapper", new IllegalStateException("decoder gave up")));
        helper.assertTrue(failure.kind() == Kind.OPEN_ERROR, "一般の例外が OPEN_ERROR に落ちていない");
        helper.assertTrue(failure.detail().contains("decoder gave up"),
                "根本原因のメッセージが詳細から消えている: " + failure.detail());
        helper.assertTrue(PlaybackFailure.thrown(null).kind() == Kind.OPEN_ERROR,
                "例外が無い経路で分類が落ちている");
        helper.succeed();
    }

    /** 自己参照する cause chain で無限ループしないこと (ローダー由来の例外は形を保証できない)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void selfReferencingCauseDoesNotHang(GameTestHelper helper) {
        final SelfCausedException looping = new SelfCausedException();
        final PlaybackFailure failure = PlaybackFailure.thrown(looping);
        helper.assertTrue(failure.kind() == Kind.OPEN_ERROR, "循環した cause chain の分類が壊れている");
        helper.assertFalse(failure.detail().isBlank(), "循環した cause chain で詳細が空になっている");
        helper.succeed();
    }

    /**
     * 全ての分類が<b>互いに異なる翻訳キー</b>を持つこと。ここが崩れると、違う原因が画面上で
     * 同じ文面になって切り分けの役に立たなくなる ({@code ResolveFailureText} と同じ規律 —
     * 対応表は 1 箇所に閉じ、{@code name().toLowerCase()} を書かない)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void everyKindHasItsOwnTranslationKey(GameTestHelper helper) {
        final Set<String> keys = new HashSet<>();
        for (final Kind kind : Kind.values()) {
            final String key = kind.translationKey();
            helper.assertTrue(key != null && !key.isBlank(), kind + " に翻訳キーが無い");
            helper.assertTrue(key.startsWith("music_disc_maker.playback_failed.reason."),
                    kind + " の翻訳キーが規約から外れている: " + key);
            helper.assertTrue(keys.add(key), "翻訳キーが重複している: " + key);
        }
        helper.assertTrue(keys.size() == Kind.values().length, "分類の数と翻訳キーの数が合わない");
        // 同時再生上限は例外でもガード拒否でもない第 3 の無音経路。上限値が文面に残ること。
        helper.assertTrue(PlaybackFailure.concurrentLimit(4).label().contains("limit=4"),
                "同時再生上限のラベルに上限値が残っていない");
        helper.assertTrue(PlaybackFailure.streamUnavailable().kind() == Kind.STREAM_UNAVAILABLE,
                "理由なしの失敗が STREAM_UNAVAILABLE に落ちていない");
        helper.succeed();
    }

    /** 隔離 classloader 側の型を模したテスト用例外 (クラス名だけで判定されることの確認)。 */
    private static final class HttpConnectionTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** {@code getCause()} が自分自身を返す壊れた例外。 */
    private static final class SelfCausedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Throwable getCause() {
            return this;
        }
    }
}
