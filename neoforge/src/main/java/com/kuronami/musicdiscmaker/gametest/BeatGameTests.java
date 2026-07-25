package com.kuronami.musicdiscmaker.gametest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.function.LongSupplier;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.beat.BeatBand;
import com.kuronami.musicdiscmaker.beat.BeatMap;
import com.kuronami.musicdiscmaker.beat.BeatMaps;
import com.kuronami.musicdiscmaker.beat.BeatOutput;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.util.WallClock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.EitherHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * ビート連動コンパレータの headless テスト。
 *
 * <p><b>assert するのは「解析がどこまで進んだか」ではなく、コンパレータが実際に返す 0..15 の値</b>。
 * 合成ビートマップ (前半 5 秒が無音・後半 5 秒がフルスケール) を差し込み、再生位置を動かして
 * 出力の時系列を固定する。再生位置は主に {@code seekTo} で動かす (実際の頭出し経路をそのまま
 * 通せるため)。実時間基準であることだけは {@link WallClock} を差し替えて確かめる。
 *
 * <p>アサーションは GameTest のシーケンスで <b>1 tick に 1 つずつ</b>置く。コンパレータ更新には
 * {@code beatMinUpdateTicks} の間引きが掛かっており、同一 tick 内で複数回進めると本番には無い
 * 抑止が混ざるため。BE の tick は実際の ticker に任せる。
 *
 * <p>実音・client の校正報告の実タイミングは client 側なのでここでは扱わない (実機確認帯)。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class BeatGameTests {

    private static final String TEMPLATE = "empty8x3x8";
    private static final String URL = "https://example.invalid/beat-fixture";
    private static final String LOUD_URL = "https://example.invalid/beat-loud";
    private static final String EMPTY_URL = "https://example.invalid/beat-unanalysed";
    private static final String RADIO_URL = "https://example.invalid/beat-radio";
    private static final String PROGRESS_URL = "https://example.invalid/beat-progressive";
    /** 合成マップの尺。前半 {@link #QUIET_MS} が無音、そこから終端までフルスケール。 */
    private static final long TRACK_MS = 10_000L;
    private static final long QUIET_MS = 5_000L;
    /** アサーションの間に置く tick 数。 */
    private static final int SETTLE = 2;
    /** BE の tick 開始を待つ上限 (tick)。GameTest の timeout (100) の内側に置く。 */
    private static final int TICK_WAIT_LIMIT = 60;

    // ── フィクスチャ ────────────────────────────────────────────────────

    /**
     * 無音 → フルスケールの 2 段の合成ビートマップ。
     *
     * <p>95 パーセンタイルは 0dB 側に付くので、無音側は {@code beatFloorDb}(-36) を大きく下回って 0、
     * 大音量側は基準ちょうどで 15 になる。値が両端に張り付くので、ヒステリシス (±1) が
     * 判定に混ざらない。
     */
    private static BeatMap fixture(long quietMs, long totalMs) {
        final BeatMap map = BeatMap.forDuration(totalMs);
        appendFrames(map, 0, framesFor(totalMs), framesFor(quietMs));
        map.markComplete();
        return map;
    }

    private static int framesFor(long ms) {
        return (int) (ms / BeatMap.HOP_MS);
    }

    /** {@link #QUIET_MS} を境に無音 → フルスケールとなる frame を {@code [from, to)} に足す。 */
    private static void appendFrames(BeatMap map, int from, int to) {
        appendFrames(map, from, to, framesFor(QUIET_MS));
    }

    private static void appendFrames(BeatMap map, int from, int to, int quietFrames) {
        final double[] silent = {-60.0, -60.0, -60.0, -60.0};
        final double[] loud = {0.0, 0.0, 0.0, 0.0};
        for (int i = from; i < to; i++) {
            final boolean quiet = i < quietFrames;
            map.append(quiet ? silent : loud, quiet ? -60.0 : 0.0);
        }
    }

    private static ItemStack customDisc(String url, long durationMs, boolean radio) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(),
                new CustomTrackData(url, "beat", "tester", radio ? 0L : durationMs, "", radio));
        disc.set(DataComponents.JUKEBOX_PLAYABLE,
                new JukeboxPlayable(new EitherHolder<>(SilentSongs.pick(durationMs, radio)), false));
        return disc;
    }

    /**
     * 強化版ジュークボックスを置き、ディスクを入れて再生を始める。
     * ディスクが入ると {@code HAS_RECORD} が立って ticker が付くので、以後は実際の server tick が回る。
     */
    private static GoldenJukeboxBlockEntity playing(GameTestHelper helper, BlockPos rel, ItemStack disc) {
        helper.setBlock(rel, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity be = helper.getBlockEntity(rel);
        if (be == null) {
            helper.fail("GoldenJukeboxBlockEntity が生成されていない", rel);
            return null;
        }
        be.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, disc); // → startPlayback(0)
        return be;
    }

    private static void assertSignal(GameTestHelper helper, int actual, int expected, String what) {
        if (actual != expected) {
            helper.fail(what + ": コンパレータ出力が " + expected + " でなく " + actual);
        }
    }

    private static void assertSignal(GameTestHelper helper, GoldenJukeboxBlockEntity be, int expected,
            String what) {
        assertSignal(helper, be.getComparatorOutput(), expected, what);
    }

    // ── テスト ──────────────────────────────────────────────────────────

    /** 停止中は 0。ディスクを入れていないジュークボックスは何も出さない。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void beatIdleJukeboxReadsZero(GameTestHelper helper) {
        final BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity be = helper.getBlockEntity(rel);
        if (be == null) {
            helper.fail("BE が生成されていない", rel);
            return;
        }
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 0, "空のジュークボックス"))
                .thenSucceed();
    }

    /**
     * 本命: 再生位置に対応した値が出る。無音区間で 0 / 大音量区間で 15。
     * 同時に「シークでビート位置が追従する」ことも固定する (どちらも startPlayback を通る)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void beatFollowsPlaybackPosition(GameTestHelper helper) {
        BeatMaps.install(URL, fixture(QUIET_MS, TRACK_MS));
        final BlockPos rel = new BlockPos(1, 1, 1);
        final GoldenJukeboxBlockEntity be = playing(helper, rel, customDisc(URL, TRACK_MS, false));
        if (be == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 0, "無音区間 (先頭)"))
                .thenExecute(() -> be.seekTo(QUIET_MS + 2_000L))
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 15, "大音量区間 (7000ms へ頭出し)"))
                .thenExecute(() -> be.seekTo(1_000L))
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 0, "無音区間へ戻した後 (1000ms)"))
                .thenSucceed();
    }

    /**
     * ビート位置は wall-clock で進む (gameTime ではない)。時刻源だけを 7 秒進めると、
     * gameTime が数 tick しか進んでいなくても大音量区間の値が出る。
     *
     * <p>これを gameTime 基準にすると、TPS が落ちた server でビートが恒久的に遅れて戻らない。
     *
     * <p><b>専用 batch に隔離してある。</b> {@link WallClock} は プロセス全体で 1 つなので、
     * 時刻を飛ばすと同時に走っている他のテストの再生位置まで動いてしまう
     * ({@code GameTestRunner} は batch を 1 つずつ順に走らせ、batch 内だけを並列に回す)。
     *
     * <p><b>固定 tick 数で読まない。</b> テスト本体が走り出す条件
     * ({@code ServerLevel#isPositionEntityTicking}) と BlockEntity が tick される条件
     * ({@code LevelChunk#isTicking} = {@code areEntitiesLoaded} を含む) は別物で、後者だけが
     * 非同期のエンティティチャンク読み込み完了を待つ。GameTestServer は 20tps ではなく全力で
     * 回る (49 tests が 2.4 秒) ので、固定 2 tick は<b>実時間で数 ms</b> しかなく、起動直後に
     * この隙間へ当たると BE が一度も tick せず初期値 0 が読まれる。
     *
     * <p><b>時刻源は凍結する。</b> {@code System.currentTimeMillis()} に足し込む形だと、待つほど
     * 再生位置が実時間ぶん進み、今度は解析範囲 ({@link #TRACK_MS}) の外へ出て 0 に落ちる。
     * 凍結しても gameTime は進むので「gameTime 基準の実装なら 0 になる」という証明力は変わらない。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = "beat_wallclock")
    public static void beatPositionAdvancesWithWallClock(GameTestHelper helper) {
        BeatMaps.install(URL, fixture(QUIET_MS, TRACK_MS));
        final BlockPos rel = new BlockPos(1, 1, 1);
        // 時刻源の差し替えは<b>再生を起こしてから</b>。playing() は BE が無いと helper.fail() で
        // throw するので、先に差し替えると凍結した時計を戻せないまま process 全体に漏れる。
        // 起点 (startMillis) は実時計で入り、この直後に凍結するので位置の計算はずれない。
        final GoldenJukeboxBlockEntity be = playing(helper, rel, customDisc(URL, TRACK_MS, false));
        if (be == null) {
            return;
        }
        // gameTime は据え置いたまま実時間だけを進める。
        final long[] nowMs = {System.currentTimeMillis() + QUIET_MS + 2_000L};
        final LongSupplier previousClock = WallClock.swap(() -> nowMs[0]);
        // 上限。凍結した時計を漏らすとプロセス全体で時間が止まり、後続のビートテストが道連れに
        // なるので、成功経路と上限の両方で必ず戻す (swap は冪等)。
        helper.runAtTickTime(TICK_WAIT_LIMIT, () -> {
            final int actual = be.getComparatorOutput();
            final boolean entitiesLoaded =
                    helper.getLevel().areEntitiesLoaded(new ChunkPos(helper.absolutePos(rel)).toLong());
            WallClock.swap(previousClock);
            helper.fail("実時間を 7 秒進めた後: コンパレータ出力が 15 でなく " + actual
                    + " (areEntitiesLoaded=" + entitiesLoaded + ")");
        });
        helper.startSequence()
                // thenWaitUntil は失敗を握りつぶして次 tick で再試行する (thenExecute と違う)。
                // BE が tick を始めた最初の tick で通る = tick 数の仮定そのものを外す。
                .thenWaitUntil(() -> assertSignal(helper, be, 15, "実時間を 7 秒進めた後"))
                .thenExecute(() -> WallClock.swap(previousClock))
                .thenSucceed();
    }

    /**
     * その場解析が再生位置に追いついていない間は 0。初回再生 (キャッシュ未命中) の挙動。
     * 解析済み frame が 0 のマップを載せておくと、実際の解析を起こさずにこの状態を作れる。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void beatUnanalysedRangeReadsZero(GameTestHelper helper) {
        BeatMaps.install(EMPTY_URL, BeatMap.forDuration(TRACK_MS)); // ready = 0
        final BlockPos rel = new BlockPos(1, 1, 1);
        final GoldenJukeboxBlockEntity be = playing(helper, rel, customDisc(EMPTY_URL, TRACK_MS, false));
        if (be == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 0, "未解析の先頭"))
                .thenExecute(() -> be.seekTo(QUIET_MS + 2_000L))
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 0, "未解析のまま大音量区間へ頭出し"))
                .thenSucceed();
    }

    /** ラジオ / ライブは尺が無くビートマップを完成させられないので、再生中でも常に 0。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void beatRadioReadsZeroWhilePlaying(GameTestHelper helper) {
        // 万一ラジオ判定が抜けても 0 にならないよう、全区間フルスケールのマップを載せておく。
        BeatMaps.install(RADIO_URL, fixture(0L, TRACK_MS));
        final BlockPos rel = new BlockPos(1, 1, 1);
        final GoldenJukeboxBlockEntity be = playing(helper, rel, customDisc(RADIO_URL, TRACK_MS, true));
        if (be == null) {
            return;
        }
        helper.assertTrue(be.isLiveStream(), "ラジオとして扱われていない");
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 0, "ラジオ再生中"))
                .thenSucceed();
    }

    /** 一時停止・ディスク取り出しで 0 に戻る。信号が鳴りっぱなしにならないこと。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void beatReturnsToZeroWhenStopped(GameTestHelper helper) {
        BeatMaps.install(LOUD_URL, fixture(0L, TRACK_MS)); // 全区間フルスケール
        final BlockPos rel = new BlockPos(1, 1, 1);
        final GoldenJukeboxBlockEntity be = playing(helper, rel, customDisc(LOUD_URL, TRACK_MS, false));
        if (be == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 15, "再生中"))
                .thenExecute(() -> be.setPaused(true))
                .thenExecute(() -> assertSignal(helper, be, 0, "一時停止直後 (間引きを待たずに 0)"))
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 0, "一時停止中"))
                .thenExecute(() -> be.setPaused(false))
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 15, "再開後"))
                .thenExecute(() -> be.removeItemNoUpdate(GoldenJukeboxBlockEntity.SLOT_DISC))
                .thenExecute(() -> assertSignal(helper, be, 0, "ディスク取り出し直後"))
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 0, "ディスク取り出し後"))
                .thenSucceed();
    }

    /**
     * client の校正報告で位相が動く。「いま 7 秒地点が鳴っている」と報告すると、server が
     * 先頭だと思っていた再生位置がその位置へ寄り、出力が大音量区間の値に変わる。
     *
     * <p>採用は first-wins。2 件目以降を採ると再生中に信号が跳ぶので、無視されることも固定する。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void beatCalibrationShiftsPhaseOnceOnly(GameTestHelper helper) {
        BeatMaps.install(URL, fixture(QUIET_MS, TRACK_MS));
        final BlockPos rel = new BlockPos(1, 1, 1);
        final GoldenJukeboxBlockEntity be = playing(helper, rel, customDisc(URL, TRACK_MS, false));
        if (be == null) {
            return;
        }
        final long session = be.beatPlaybackId();
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 0, "校正前 (先頭)"))
                .thenExecute(() -> helper.assertTrue(
                        be.onPlaybackStarted(session, QUIET_MS + 2_000L), "校正が採用されない"))
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 15, "校正後"))
                .thenExecute(() -> {
                    // 2 件目・別セッションは無視される (採ると再生中に位相が跳ぶ)。
                    helper.assertFalse(be.onPlaybackStarted(session, 0L), "2 件目の校正を採用している");
                    helper.assertFalse(be.onPlaybackStarted(session + 99L, 0L),
                            "別セッションの校正を採用している");
                })
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 15, "無視した後も校正が維持されている"))
                .thenSucceed();
    }

    /** 細工された報告で位相を飛ばされない (server 自身の再生起点から ±10 秒の外は捨てる)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void beatCalibrationRejectsOutOfRangeReport(GameTestHelper helper) {
        BeatMaps.install(URL, fixture(QUIET_MS, TRACK_MS));
        final BlockPos rel = new BlockPos(1, 1, 1);
        final GoldenJukeboxBlockEntity be = playing(helper, rel, customDisc(URL, TRACK_MS, false));
        if (be == null) {
            return;
        }
        helper.assertFalse(be.onPlaybackStarted(be.beatPlaybackId(), 60_000L),
                "±10 秒を超える報告を採用している");
        helper.startSequence()
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 0, "不正な校正を捨てた後 (先頭のまま)"))
                .thenSucceed();
    }

    /**
     * その場解析が進むと、追いついた時点から値が出始める。<b>初回再生 (キャッシュ未命中) の本筋</b>で、
     * 「追い越すまで 0 / 追い越したら出る」の遷移そのものを固定する。
     *
     * <p>解析スレッドを回す代わりに、テストが {@code append} で frame を足して解析の進行を再現する。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void beatCatchesUpAsAnalysisProgresses(GameTestHelper helper) {
        // 先頭 2 秒ぶんだけ解析済みのマップ。基準は既定 (0dBFS) のまま = fixture と同じ写像になる。
        final BeatMap map = BeatMap.forDuration(TRACK_MS);
        appendFrames(map, 0, framesFor(2_000L));
        BeatMaps.install(PROGRESS_URL, map);

        final BlockPos rel = new BlockPos(1, 1, 1);
        final GoldenJukeboxBlockEntity be = playing(helper, rel, customDisc(PROGRESS_URL, TRACK_MS, false));
        if (be == null) {
            return;
        }
        helper.startSequence()
                .thenExecute(() -> be.seekTo(QUIET_MS + 2_000L)) // 解析済み範囲より先へ
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 0, "解析が再生位置に届いていない"))
                // 解析が 8 秒地点まで進む = 再生位置を追い越す
                .thenExecute(() -> appendFrames(map, framesFor(2_000L), framesFor(8_000L)))
                .thenIdle(SETTLE)
                .thenExecute(() -> assertSignal(helper, be, 15, "解析が追い越した後"))
                .thenSucceed();
    }

    /**
     * ビートマップがディスクを往復しても同じ値を返す。
     *
     * <p>キャッシュ命中 (= 2 回目以降の再生) は必ずこの経路を通るので、ここが壊れると
     * 「初回だけ動いて 2 回目から無反応」になる。他のテストは全部 {@code install} で
     * 直接載せていて直列化を一度も通らないため、ここだけが唯一の防波堤。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void beatMapSurvivesSerializationRoundTrip(GameTestHelper helper) {
        final BeatMap original = fixture(QUIET_MS, TRACK_MS);
        final BeatMap restored;
        try {
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            original.write(buffer);
            restored = BeatMap.read(new ByteArrayInputStream(buffer.toByteArray()));
        } catch (final IOException ex) {
            helper.fail("ビートマップの直列化で例外: " + ex);
            return;
        }
        if (restored == null) {
            helper.fail("読み戻したビートマップが null (マジック / 版の不一致)");
            return;
        }
        helper.assertTrue(restored.hopMs() == original.hopMs(), "hopMs が変わった");
        helper.assertTrue(restored.ready() == original.ready(),
                "frame 数が変わった: " + original.ready() + " → " + restored.ready());
        helper.assertTrue(restored.isComplete(), "読み戻したマップが complete でない");
        helper.assertTrue(restored.referenceDb(BeatBand.LOW) == original.referenceDb(BeatBand.LOW),
                "基準レベルが変わった");
        // 値そのものが一致すること (無音区間・境界・大音量区間)。
        for (final long t : new long[] {0L, 1_000L, QUIET_MS - 100L, QUIET_MS + 100L, 7_000L, 9_900L}) {
            final double before = original.peakDb(BeatBand.LOW, t, t + 50L);
            final double after = restored.peakDb(BeatBand.LOW, t, t + 50L);
            if (Double.compare(before, after) != 0) {
                helper.fail(t + "ms のレベルが変わった: " + before + " → " + after);
                return;
            }
        }
        // 往復後は capacity == ready になる (write は ready ぶんしか書かない)。読み出しが
        // capacity でなく ready を見ていることの確認も兼ねる。
        helper.assertTrue(restored.capacity() == restored.ready(),
                "往復後の capacity が ready と一致しない");
        helper.assertTrue(Double.isNaN(restored.peakDb(BeatBand.LOW, TRACK_MS + 1_000L, TRACK_MS + 1_050L)),
                "解析範囲の外が NaN でない");
        helper.succeed();
    }

    /**
     * <b>再送にも現在のセッション ID が載る</b>。chunk 再ロードの再送を受けた client は音を鳴らし直さない
     * （同じ曲・同じ位置なので dedup する）が、server は新しいセッションを起こして校正を待っている。
     * ここが 0 だと client は報告しようがなく、一度離れて戻るたびに校正が失われて、以後その曲は
     * バッファ遅延ぶんずれたまま戻らない。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void beatResendCarriesCurrentSession(GameTestHelper helper) {
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            BeatMaps.install(URL, fixture(QUIET_MS, TRACK_MS));
            final BlockPos rel = new BlockPos(1, 1, 1);
            final GoldenJukeboxBlockEntity be = playing(helper, rel, customDisc(URL, TRACK_MS, false));
            if (be == null) {
                return;
            }
            net.clear();
            be.resendTo(player);
            final List<CapturingNetwork.Sent> sent = net.of(PlayDiscPayload.class);
            helper.assertTrue(sent.size() == 1, "再送 packet が 1 通でない: " + sent.size());
            final PlayDiscPayload payload = (PlayDiscPayload) sent.get(0).payload();
            helper.assertTrue(payload.playbackId() == be.beatPlaybackId(),
                    "再送のセッション ID が現在のものでない: " + payload.playbackId()
                            + " / " + be.beatPlaybackId());
            helper.assertTrue(payload.playbackId() != 0L, "再送のセッション ID が 0 (校正できない)");
        } finally {
            Services.swapNetwork(previous);
        }
        helper.succeed();
    }

    /** ラジオ / ライブはビート対象外なので、client に校正を求めない (セッション ID = 0)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void beatRadioCarriesNoSession(GameTestHelper helper) {
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            final BlockPos rel = new BlockPos(1, 1, 1);
            final GoldenJukeboxBlockEntity be = playing(helper, rel, customDisc(RADIO_URL, TRACK_MS, true));
            if (be == null) {
                return;
            }
            final List<CapturingNetwork.Sent> sent = net.of(PlayDiscPayload.class);
            helper.assertTrue(!sent.isEmpty(), "ラジオの再生 packet が出ていない");
            for (final CapturingNetwork.Sent s : sent) {
                helper.assertTrue(((PlayDiscPayload) s.payload()).playbackId() == 0L,
                        "ラジオなのにセッション ID が載っている");
            }
        } finally {
            Services.swapNetwork(previous);
        }
        helper.succeed();
    }

    /** ビートマップが無い (解析が起きていない) 時は 0 を出す。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void beatOutputWithoutMapIsZero(GameTestHelper helper) {
        final BeatOutput output = new BeatOutput();
        if (output.update(null, 3_000L) != 0) {
            helper.fail("ビートマップ無しで 0 以外が出ている");
            return;
        }
        helper.succeed();
    }
}
