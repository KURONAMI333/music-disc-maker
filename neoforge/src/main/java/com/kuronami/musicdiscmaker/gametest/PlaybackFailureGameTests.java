package com.kuronami.musicdiscmaker.gametest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.GoldenJukeboxFailures;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailure;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailure.Kind;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailureNotices;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureClassifier;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.IMusicLoader;
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;
import com.kuronami.musicdiscmaker.network.UrlGuard;

import net.minecraft.core.BlockPos;
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

    /**
     * 全ての分類が、<b>出荷している 14 本すべての翻訳ファイル</b>に GUI 用の文を
     * 持っていること ({@code FailureReasonLangCoverageTest} の {@link Kind} 版)。
     *
     * <p><b>なぜ要るか</b>: 分類を 1 つ増やした時にコンパイラが止めてくれるのは
     * {@link Kind#guiKey()} の網羅 switch だけで、翻訳ファイルは 14 本ある。1 本でも入れ忘れると、その言語の利用者には
     * <b>生の翻訳キーがそのまま画面に出る</b> (MC は未定義のキーをキー文字列として描く)。
     *
     * <p><b>読み方</b>: 翻訳ファイルは {@code common} 側にあり、GameTest は dedicated server の
     * 実行ディレクトリ ({@code neoforge/run}) から走るので<b>ファイルとして読む</b>。見つからない・数が足りないは
     * <b>失敗として扱う</b> — 黙って 0 件を検査して緑になると、このテストを足した意味が消える。
     * ロケールは決め打ちせずディレクトリを走査するので、15 番目の言語を足した時も自動で入る。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void everyKindHasItsGuiLabelInEveryLocale(GameTestHelper helper) {
        final List<Path> files = langFiles();
        helper.assertTrue(files.size() >= SHIPPED_LOCALES,
                "翻訳ファイルが " + files.size() + " 本しか見つからない: " + LANG_DIR);
        for (final Path file : files) {
            final String text = read(file);
            for (final Kind kind : Kind.values()) {
                helper.assertTrue(text.contains('"' + kind.guiKey() + '"'),
                        file.getFileName() + " に " + kind + " の GUI ラベルが無い: " + kind.guiKey());
            }
        }
        helper.succeed();
    }

    /**
     * GUI に出す文のキーが分類ごとに<b>違う</b>こと。ただし<b>利用者に打つ手が無い 3 つ</b>
     * ({@link Kind#STREAM_UNAVAILABLE} / {@link Kind#OPEN_ERROR} / {@link Kind#SOUND_ENGINE}) は
     * 1 つのキーを共有する。この 3 つを書き分けても利用者が取れる行動は同じで 1 つも無いので、
     * 言い分けは切り分けの役に立たず、狭い帯を 3 通りの文で埋めるだけになる。
     *
     * <p>「3 つだけ共有・他は全部別」を名指しで固定するのは、<b>うっかりの集約を素通りさせない</b>
     * ため。単に重複を許すと、地域制限と年齢制限が同じ文に寄るような編集が黙って通る。
     *
     * <p>あわせて共有キーが制作機の汎用キー {@code gui.music_disc_maker.failed} ("取得失敗") に
     * 寄っていないことを固定する。あちらは URL を解決できなかった状態で、こちらは解決できたのに
     * 鳴らせなかった状態 = 利用者に言うことが違う。名前空間だけを見る検査では、両者を束ねる
     * 編集が素通りする。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void everyKindHasItsOwnGuiKey(GameTestHelper helper) {
        final Set<Kind> shared = Set.of(Kind.STREAM_UNAVAILABLE, Kind.OPEN_ERROR, Kind.SOUND_ENGINE);
        final String sharedKey = Kind.STREAM_UNAVAILABLE.guiKey();
        final Set<String> keys = new HashSet<>();
        for (final Kind kind : Kind.values()) {
            final String key = kind.guiKey();
            helper.assertTrue(key != null && key.startsWith("gui.music_disc_maker.failed"),
                    kind + " の GUI ラベルのキーが規約から外れている: " + key);
            helper.assertTrue(!"gui.music_disc_maker.failed".equals(key),
                    kind + " が制作機の汎用キーに寄っている (分類の粒度が消える): " + key);
            if (shared.contains(kind)) {
                helper.assertTrue(sharedKey.equals(key),
                        kind + " が手の無い 3 つの共有キーから外れている: " + key);
                continue;
            }
            helper.assertTrue(!sharedKey.equals(key),
                    kind + " が手の無い 3 つの共有キーに寄っている (利用者の打つ手が消える): " + key);
            helper.assertTrue(keys.add(key), "GUI ラベルのキーが重複している: " + key);
        }
        keys.add(sharedKey);
        helper.assertTrue(keys.size() == Kind.values().length - shared.size() + 1,
                "分類の数とキーの数が合わない");
        // 制作機側に既訳がある 8 つは、同じ概念に 2 つの訳語を作らないよう同一キーを共有する。
        helper.assertTrue(Kind.NETWORK.guiKey().equals(FailureReason.CONNECTION_FAILED.guiKey()),
                "回線の分類が制作機側と別のキーになっている");
        helper.assertTrue(Kind.BOT_CHECK.guiKey().equals(FailureReason.BOT_CHECK.guiKey()),
                "bot 判定の分類が制作機側と別のキーになっている");
        helper.succeed();
    }

    /**
     * 金ジュークの失敗の入れ物 ({@link GoldenJukeboxFailures}) が、座標ごとに独立して覚え、
     * <b>消すと言われた座標だけ</b>を消すこと。
     *
     * <p>ここで固定するのは「時間で消さない」という性質でもある — 入れ物は時計を一切持たない
     * ので、覚えたものは上の 3 つの点で消されるまで残る。直っていない失敗が黙って消えると、
     * 利用者が画面を見に行った時に何も無い状態へ戻る。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void jukeboxFailuresAreRememberedPerPosition(GameTestHelper helper) {
        final GoldenJukeboxFailures failures = new GoldenJukeboxFailures();
        final BlockPos first = new BlockPos(1, 2, 3);
        final BlockPos second = new BlockPos(4, 5, 6);
        helper.assertTrue(failures.latest(first) == null, "何も起きていないのに失敗を覚えている");

        failures.record(first, PlaybackFailure.soundMuted());
        failures.record(second, PlaybackFailure.concurrentLimit(4));
        helper.assertTrue(failures.latest(first).kind() == Kind.MUTED, "座標ごとの記憶が混ざっている");
        helper.assertTrue(failures.latest(second).kind() == Kind.CONCURRENT_LIMIT,
                "座標ごとの記憶が混ざっている");

        // 同じ座標の新しい失敗は上書きする (古い理由を残すと、直った後も古い話が出る)。
        failures.record(first, PlaybackFailure.streamUnavailable());
        helper.assertTrue(failures.latest(first).kind() == Kind.STREAM_UNAVAILABLE,
                "同じ座標の失敗が上書きされていない");

        // 消える点 1・2 は座標を名指しする。他の jukebox の記憶を巻き添えにしない。
        failures.clear(first);
        helper.assertTrue(failures.latest(first) == null, "名指しした座標の記憶が消えていない");
        helper.assertTrue(failures.latest(second) != null, "他の座標の記憶まで消えている");

        // 消える点 3 (ワールド離脱) だけが全部消す。
        failures.clearAll();
        helper.assertTrue(failures.latest(second) == null, "ワールド離脱で記憶が残っている");
        helper.succeed();
    }

    /** 出荷している翻訳ファイルの数。これを下回ったら、読む場所を間違えたか lang が消えている。 */
    private static final int SHIPPED_LOCALES = 14;

    private static final String LANG_DIR =
            "common/src/main/resources/assets/music_disc_maker/lang";

    /** {@code lang} ディレクトリの {@code *.json} を全部。見つからなければ失敗させる。 */
    private static List<Path> langFiles() {
        Path here = Path.of("").toAbsolutePath();
        for (int up = 0; up < 6 && here != null; up++, here = here.getParent()) {
            final Path dir = here.resolve(LANG_DIR);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> found = Files.list(dir)) {
                    final List<Path> files = new ArrayList<>();
                    found.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .forEach(files::add);
                    return files;
                } catch (final IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
        }
        throw new IllegalStateException(
                "翻訳ファイルの置き場が見つからない (探した起点: "
                        + Path.of("").toAbsolutePath() + " から親へ 6 段): " + LANG_DIR);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * ローダーが持っている理由が、境界を越えて<b>分類まで届く</b>こと。
     *
     * <p>ここが緩むと、DNS 失敗・年齢制限・地域制限・YouTube の bot 判定がすべて
     * {@link Kind#STREAM_UNAVAILABLE} に潰れる (理由を持つ経路を作っただけで使っていない状態)。
     * 検証は <b>ローダー境界の型から</b>始める — {@code ClientAudioStreams} 経由だと、
     * キャッシュ命中の枝が理由を持たないぶんだけ検証が素通りしてしまう。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void loaderReasonsReachTheClassification(GameTestHelper helper) {
        final IMusicLoader loader = new FailingLoader(FailureReason.BOT_CHECK);
        final OpenStreamResult result = loader.openStreamDetailed("https://example.invalid/x", 0L);
        helper.assertFalse(result.isOk(), "失敗のはずが成功として返っている");
        final PlaybackFailure failure = PlaybackFailure.ofReason(result.reason(), result.detail());
        helper.assertTrue(failure.kind() == Kind.BOT_CHECK,
                "BOT_CHECK が " + failure.kind() + " に潰れている");
        helper.assertTrue(failure.label().contains("BOT_CHECK"),
                "ラベルに BOT_CHECK が残っていない: " + failure.label());
        helper.succeed();
    }

    /**
     * {@link FailureReason} の各値が、利用者の取れる行動ごとに分かれること。
     * 「対応外の URL」「動画が消えた」「地域制限」「年齢制限」「ログイン要求」は、
     * どれ 1 つとして {@link Kind#STREAM_UNAVAILABLE} に落ちてはならない。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void everyFailureReasonMapsToAnActionableKind(GameTestHelper helper) {
        helper.assertTrue(kindOf(FailureReason.UNSUPPORTED_URL) == Kind.UNSUPPORTED_URL,
                "対応外 URL の分類が違う");
        helper.assertTrue(kindOf(FailureReason.PRIVATE_OR_REMOVED) == Kind.PRIVATE_OR_REMOVED,
                "非公開/削除済みの分類が違う");
        helper.assertTrue(kindOf(FailureReason.REGION_LOCKED) == Kind.REGION_LOCKED,
                "地域制限の分類が違う");
        helper.assertTrue(kindOf(FailureReason.AGE_RESTRICTED) == Kind.AGE_RESTRICTED,
                "年齢制限の分類が違う");
        helper.assertTrue(kindOf(FailureReason.BOT_CHECK) == Kind.BOT_CHECK,
                "bot 判定の分類が違う");
        // 利用者の行動が既存の分類と同じものは寄せる (増やすこと自体が目的ではない)。
        helper.assertTrue(kindOf(FailureReason.CONNECTION_FAILED) == Kind.NETWORK,
                "接続失敗が NETWORK に寄っていない");
        helper.assertTrue(kindOf(FailureReason.BLOCKED_URL) == Kind.BLOCKED_URL,
                "ガード拒否が BLOCKED_URL に寄っていない");
        // 相手が応答して番号を返した失敗は、回線障害と逆のことを言う必要がある。
        helper.assertTrue(kindOf(FailureReason.SOURCE_REFUSED) == Kind.REFUSED,
                "相手の拒否が NETWORK に寄せられている");
        helper.assertTrue(kindOf(FailureReason.UNKNOWN) == Kind.STREAM_UNAVAILABLE,
                "理由不明が従来の粒度に落ちていない");
        helper.assertTrue(PlaybackFailure.ofReason(null, null).kind() == Kind.STREAM_UNAVAILABLE,
                "理由が null の経路で分類が壊れている");
        // 寄せた分類では、ローダー側の言い分をラベルに残す (利用者がそのまま報告に貼れる)。
        helper.assertTrue(PlaybackFailure.ofReason(FailureReason.CONNECTION_FAILED, null).label()
                .contains("CONNECTION_FAILED"), "寄せた分類でローダーの理由が消えている");
        helper.succeed();
    }

    /**
     * 橋渡し interface だけ新しく、impl が {@code openStreamDetailed} を持たない組み合わせでも
     * 壊れないこと。既定実装が {@code openStream} に落ちて、理由は従来どおりの粒度になる。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void oldImplementationsStillFailSoftly(GameTestHelper helper) {
        final IMusicLoader legacy = new LegacyLoader();
        final OpenStreamResult result = legacy.openStreamDetailed("https://example.invalid/x", 0L);
        helper.assertFalse(result.isOk(), "失敗のはずが成功として返っている");
        helper.assertTrue(result.reason() == FailureReason.UNKNOWN,
                "既定実装が理由を捏造している: " + result.reason());
        helper.assertTrue(PlaybackFailure.ofReason(result.reason(), result.detail()).kind()
                == Kind.STREAM_UNAVAILABLE, "既定実装の失敗が従来の粒度に落ちていない");
        helper.succeed();
    }

    /**
     * kura の実機ログに出た <b>そのままの文面</b>が bot 判定に落ちること。
     *
     * <p>ここが要点 — この失敗は URL 解決には成功していて、落ちるのは lavaplayer の再生スレッド
     * ({@code lava-daemon-pool-playback-*}) の中。同期経路しか見ていなかった頃は、この形の
     * 失敗は利用者に一言も出ないまま完全な無音になっていた (CF 報告 #15 の症状)。
     *
     * <p>文面は youtube-source の {@code AllClientsFailedException} が組み立てるもので、
     * 各 client の失敗理由を自分のメッセージへ連結して持つ。つまり「login 要求」は例外の
     * メッセージ 1 本の中に現れる = 隔離 classloader の型を触らずに分類できる。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void realBotCheckMessageIsClassifiedAsBotCheck(GameTestHelper helper) {
        final FailureReason reason = FailureClassifier.classify(new RuntimeException(ALL_CLIENTS_FAILED));
        helper.assertTrue(reason == FailureReason.BOT_CHECK,
                "実ログの login 要求が " + reason + " に落ちている");
        // 後続の client の言い分 (音声形式なし・player 設定エラー) に引きずられないこと。
        helper.assertTrue(FailureClassifier.classifyMessage("No supported audio streams available, available types: ")
                        != FailureReason.BOT_CHECK,
                "bot 判定と無関係な文面まで BOT_CHECK になっている");
        // 年齢制限は "requires login" と別語句なので衝突しない。
        helper.assertTrue(FailureClassifier.classifyMessage("This video requires age verification.")
                        == FailureReason.AGE_RESTRICTED, "年齢制限が bot 判定に吸われている");
        // 判別できない失敗は UNKNOWN。null でも壊れない。ここが CONNECTION_FAILED に戻ると、
        // 何も分かっていない失敗が「この端末がホストに到達できない」と名乗り始める。
        helper.assertTrue(FailureClassifier.classify(null) == FailureReason.UNKNOWN,
                "例外が無い経路で分類が壊れている");
        helper.succeed();
    }

    /**
     * 再生スレッドで拾った失敗が、<b>境界を越えて</b>分類まで届くこと。
     *
     * <p>lavaplayer / youtube-source の例外型は隔離 classloader の中にしか無いので、mod 側では
     * {@code instanceof} が効かない。だから境界を越えるのは {@link PlaybackFault}
     * (理由 + 短い文字列) だけ、というのがこの経路の設計。ここが緩むと、解決に成功して再生だけが
     * 落ちる失敗は再び無音に戻る。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void asyncFaultCrossesTheBoundary(GameTestHelper helper) {
        // 口を持たない実装は「壊れていない」と答える (v3 の派生ソースを壊さないための既定)。
        helper.assertTrue(new SilentSource().playbackFault() == null,
                "既定実装が失敗を捏造している");

        final IAudioSource source = new FaultySource(new PlaybackFault(
                FailureClassifier.classify(new RuntimeException(ALL_CLIENTS_FAILED)), ALL_CLIENTS_FAILED));
        final PlaybackFault fault = source.playbackFault();
        helper.assertTrue(fault != null, "再生中の失敗が境界の手前で消えている");
        helper.assertTrue(fault.reason() == FailureReason.BOT_CHECK,
                "境界を越えた理由が " + fault.reason() + " に化けている");
        // 詳細はチャット 1 行に載る形へ畳まれること (再生スレッドの例外はスタックトレースを抱える。
        // 畳まないと、理由を出せるようになった代わりにチャットがトレースで埋まる)。
        helper.assertFalse(fault.detail().contains("\n"), "詳細が 1 行に畳まれていない");
        helper.assertFalse(fault.detail().contains("\t"), "詳細にタブが残っている");
        helper.assertTrue(fault.detail().length() < ALL_CLIENTS_FAILED.length(),
                "長い詳細が切り詰められていない: " + fault.detail().length() + " 文字");
        helper.assertTrue(fault.detail().endsWith("…"), "切り詰めた印が付いていない: " + fault.detail());
        // 短い詳細はそのまま残る (何でも切るわけではない)。
        helper.assertTrue(new PlaybackFault(FailureReason.BOT_CHECK, "requires login").detail()
                .equals("requires login"), "短い詳細まで加工されている");

        final PlaybackFailure failure = PlaybackFailure.ofReason(fault.reason(), fault.detail());
        helper.assertTrue(failure.kind() == Kind.BOT_CHECK,
                "非同期の bot 判定が " + failure.kind() + " に潰れている");
        helper.assertTrue(failure.translationKey().endsWith(".bot_check"),
                "画面に出る文面が bot 判定のものになっていない: " + failure.translationKey());
        helper.succeed();
    }

    /**
     * 同じ音源の同じ失敗を二度報告しないこと。ラジオは終端を瞬断とみなして再接続するので、
     * 何度試しても同じ理由で落ちる失敗 (bot 判定が典型) は抑えないとチャットを埋める。
     * 忘れるのは停止した時だけ — 再接続のたびに忘れるなら何も抑えていない。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void sameFailureIsReportedOnlyOnce(GameTestHelper helper) {
        final PlaybackFailureNotices notices = new PlaybackFailureNotices();
        final Object key = new Object();
        final PlaybackFailure botCheck = PlaybackFailure.ofReason(FailureReason.BOT_CHECK, "requires login");

        helper.assertTrue(notices.shouldReport(key, botCheck), "最初の失敗が抑制されている");
        helper.assertFalse(notices.shouldReport(key, botCheck), "同じ失敗が二度報告されている");
        helper.assertFalse(notices.shouldReport(key, PlaybackFailure.ofReason(
                FailureReason.BOT_CHECK, "requires login")), "同値の失敗が別物として扱われている");
        // 理由が変わったら別の情報なので通す。
        helper.assertTrue(notices.shouldReport(key, PlaybackFailure.ofReason(
                FailureReason.CONNECTION_FAILED, null)), "違う失敗まで抑制されている");
        // 音源が違えば別勘定。
        helper.assertTrue(notices.shouldReport(new Object(), botCheck), "別の音源まで抑制されている");
        // 停止したら忘れる (次に同じ失敗が起きたら改めて出す)。
        notices.forget(key);
        helper.assertTrue(notices.shouldReport(key, botCheck), "停止後も抑制が残っている");
        notices.forgetAll();
        helper.assertTrue(notices.shouldReport(key, botCheck), "全消去が効いていない");
        helper.succeed();
    }

    /**
     * kura の実機 ({@code latest.log}) に出た {@code AllClientsFailedException} のメッセージ。
     * youtube-source が各 client の失敗を自分のメッセージへ連結した形をそのまま写している。
     */
    private static final String ALL_CLIENTS_FAILED =
            "(yts.version: 1.18.2) All clients failed to load the item.\n"
            + "\n"
            + "Client [ANDROID_VR] failed: This video requires login.\n"
            + "\tat dev.lavalink.youtube.clients.skeleton.Client.getPlayabilityStatus(Client.java:94)\n"
            + "\n"
            + "Client [WEB] failed: No supported audio streams available, available types: \n"
            + "\tat dev.lavalink.youtube.track.format.TrackFormats.getBestFormat(TrackFormats.java:47)\n"
            + "\n"
            + "Client [WEB_EMBEDDED_PLAYER] failed: Video player configuration error";

    private static Kind kindOf(FailureReason reason) {
        return PlaybackFailure.ofReason(reason, null).kind();
    }

    /** 再生中に壊れた理由を持つソース (隔離側の {@code LavaAudioSource} を模す)。 */
    private record FaultySource(PlaybackFault fault) implements IAudioSource {

        @Override
        public int sampleRate() {
            return 48000;
        }

        @Override
        public int channels() {
            return 1;
        }

        @Override
        public int bitsPerSample() {
            return 16;
        }

        @Override
        public boolean bigEndian() {
            return false;
        }

        @Override
        public int read(byte[] dst, int off, int len) {
            return -1;
        }

        @Override
        public PlaybackFault playbackFault() {
            return fault;
        }

        @Override
        public void close() {
        }
    }

    /** {@code playbackFault} を持たない実装 (v3 の派生ソース相当)。 */
    private static final class SilentSource implements IAudioSource {

        @Override
        public int sampleRate() {
            return 48000;
        }

        @Override
        public int channels() {
            return 1;
        }

        @Override
        public int bitsPerSample() {
            return 16;
        }

        @Override
        public boolean bigEndian() {
            return false;
        }

        @Override
        public int read(byte[] dst, int off, int len) {
            return -1;
        }

        @Override
        public void close() {
        }
    }

    /**
     * 理由つきで失敗するローダー (impl 側の {@code ResolveException} 経路を模す)。
     *
     * @param reason 返す失敗理由
     */
    private record FailingLoader(FailureReason reason) implements IMusicLoader {

        @Override
        public TrackInfo resolve(String url) {
            return null;
        }

        @Override
        public IAudioSource openStream(String url, long startMs) {
            return null;
        }

        @Override
        public OpenStreamResult openStreamDetailed(String url, long startMs) {
            return OpenStreamResult.failed(reason, null);
        }
    }

    /** {@code openStreamDetailed} を実装していない古い impl。 */
    private static final class LegacyLoader implements IMusicLoader {

        @Override
        public TrackInfo resolve(String url) {
            return null;
        }

        @Override
        public IAudioSource openStream(String url, long startMs) {
            return null;
        }
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
