package com.kuronami.musicdiscmaker.gametest;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.depend.DependencyLoadGate;
import com.kuronami.musicdiscmaker.depend.ExtractionPlan;
import com.kuronami.musicdiscmaker.depend.ExtractionPlan.Entry;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 同梱依存のロードが「失敗を記憶する掛け金」になっていた欠陥の headless テスト。
 *
 * <p>壊れていた形: 仕事をする前に {@code loaded = true} を立てていたため、展開が一度でも
 * 失敗すると以降の呼び出しは全部素通りし、空の classloader のまま {@code Class.forName} に
 * 進む。利用者に見える症状は毎回「LavaPlayer loader を初期化できない」だけで、最初の 1 回に
 * 投げられた本当の原因はどこにも出てこない。
 *
 * <p>{@code DependencyManager} 本体は実ファイルシステムと mod jar を掴んでいて GameTest から
 * 直接は叩けないので、掛け金 ({@link DependencyLoadGate}) と再利用判定
 * ({@link ExtractionPlan}) を純ロジックとして切り出し、そこを固定する
 * ({@code PlaybackFailureGameTests} と同じ seam の切り方)。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class DependencyLoadGameTests {

    private static final String TEMPLATE = "empty8x3x8";

    /**
     * 失敗した後に<b>もう一度試せる</b>こと。元の失敗が一時的なもの (ウイルス対策の temp
     * スキャン等) なら、これだけで次の呼び出しが通る。ここが緩むと、最初の 1 回の失敗が
     * その起動の間ずっと「ロード済み」として固定されてしまう。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void retriesAfterAFailedAttempt(GameTestHelper helper) {
        final DependencyLoadGate gate = new DependencyLoadGate(3);
        final AtomicInteger runs = new AtomicInteger();

        boolean threw = false;
        try {
            gate.runOnce(() -> {
                runs.incrementAndGet();
                throw new java.io.IOException("temporary");
            });
        } catch (final IllegalStateException ex) {
            threw = true;
        }
        helper.assertTrue(threw, "a failing load did not report the failure to the caller");
        helper.assertTrue(!gate.succeeded(), "the gate latched as loaded even though the load failed");

        // 二度目は素通りせず、実際にもう一度仕事を走らせる。
        gate.runOnce(runs::incrementAndGet);
        helper.assertTrue(runs.get() == 2, "the gate did not retry after a failure (runs=" + runs.get() + ")");
        helper.assertTrue(gate.succeeded(), "the gate did not latch after a successful load");

        // 成功した後はもう走らせない。
        gate.runOnce(runs::incrementAndGet);
        helper.assertTrue(runs.get() == 2, "the gate re-ran the work after it had already succeeded");
        helper.succeed();
    }

    /**
     * 同じ失敗が無限に再試行されて重くならないこと。ただし打ち切った後も<b>黙って素通り
     * させない</b> — 毎回、覚えておいた原因を cause に付けて投げ直す。「黙って空で進む」が
     * 元の欠陥そのものなので、そこにだけは戻さない。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void stopsRetryingButKeepsReportingTheCause(GameTestHelper helper) {
        final DependencyLoadGate gate = new DependencyLoadGate(2);
        final AtomicInteger runs = new AtomicInteger();
        final DependencyLoadGate.Work alwaysFails = () -> {
            runs.incrementAndGet();
            throw new java.io.IOException("disk is on fire");
        };

        for (int i = 0; i < 5; i++) {
            IllegalStateException caught = null;
            try {
                gate.runOnce(alwaysFails);
            } catch (final IllegalStateException ex) {
                caught = ex;
            }
            helper.assertTrue(caught != null, "call " + (i + 1) + " returned normally instead of failing");
            helper.assertTrue(rootMessageOf(caught).contains("disk is on fire"),
                    "call " + (i + 1) + " lost the original cause of the failure");
        }
        helper.assertTrue(runs.get() == 2,
                "the work kept being retried past the attempt limit (runs=" + runs.get() + ")");
        helper.assertTrue(gate.exhausted(), "the gate did not report itself as exhausted");
        helper.succeed();
    }

    /**
     * 同梱 jar が 0 個だったとき、そこで原因の分かる例外になること。ここを素通りさせると
     * 空の classloader が組み上がり、後段が {@code ClassNotFoundException} という
     * 原因の読めない形で落ちる (報告者のログがまさにその形だった)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void anEmptyDependencySetFailsWithADiagnosableMessage(GameTestHelper helper) {
        IllegalStateException caught = null;
        try {
            ExtractionPlan.requireNonEmpty(List.of(), "the mod jar (/some/where/dependencies)");
        } catch (final IllegalStateException ex) {
            caught = ex;
        }
        helper.assertTrue(caught != null, "an empty dependency set was accepted");
        final String message = caught.getMessage();
        helper.assertTrue(message.contains("jar.packed"),
                "the message does not say what was being looked for: " + message);
        helper.assertTrue(message.contains("/some/where/dependencies"),
                "the message does not say where it looked: " + message);
        helper.succeed();
    }

    /**
     * 壊れた展開を再利用しないこと。完了マーカーは全ファイルの設置が済んだ後にしか
     * 書かれず、個々のファイルは一時名から原子的に移されるので「書きかけが最終名で
     * 見えている」状態は起こらない。それでもサイズを 1 件ずつ突き合わせる。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void reuseRequiresTheMarkerAndMatchingSizes(GameTestHelper helper) {
        final List<Entry> expected = List.of(new Entry("a.jar.packed", 100L), new Entry("b.jar.packed", 200L));

        helper.assertTrue(ExtractionPlan.canReuse(expected, probe(true, 100L, 200L)),
                "a complete extraction was not reused");
        helper.assertTrue(!ExtractionPlan.canReuse(expected, probe(false, 100L, 200L)),
                "an extraction without the completion marker was reused");
        helper.assertTrue(!ExtractionPlan.canReuse(expected, probe(true, 100L, 199L)),
                "an extraction with a truncated file was reused");
        helper.assertTrue(!ExtractionPlan.canReuse(expected, probe(true, 100L, -1L)),
                "an extraction with a missing file was reused");
        helper.assertTrue(!ExtractionPlan.canReuse(List.of(), probe(true, 0L, 0L)),
                "an empty expectation was treated as a reusable extraction");
        helper.succeed();
    }

    /**
     * 展開先の名前が同梱 jar 一式で決まること。順序が違っても同じ場所になり
     * (再利用が効く)、依存が入れ替われば別の場所になる (古い展開を掴まない)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void theExtractionDirectoryTracksTheDependencySet(GameTestHelper helper) {
        final List<Entry> one = List.of(new Entry("a.jar.packed", 100L), new Entry("b.jar.packed", 200L));
        final List<Entry> reordered = List.of(new Entry("b.jar.packed", 200L), new Entry("a.jar.packed", 100L));
        final List<Entry> resized = List.of(new Entry("a.jar.packed", 100L), new Entry("b.jar.packed", 201L));
        final List<Entry> renamed = List.of(new Entry("a.jar.packed", 100L), new Entry("c.jar.packed", 200L));

        helper.assertTrue(ExtractionPlan.fingerprint(one).equals(ExtractionPlan.fingerprint(reordered)),
                "the extraction directory changed just because the scan order changed");
        helper.assertTrue(!ExtractionPlan.fingerprint(one).equals(ExtractionPlan.fingerprint(resized)),
                "a changed dependency size did not move the extraction directory");
        helper.assertTrue(!ExtractionPlan.fingerprint(one).equals(ExtractionPlan.fingerprint(renamed)),
                "a changed dependency name did not move the extraction directory");
        helper.assertTrue(!ExtractionPlan.fingerprint(one).isBlank(),
                "the extraction directory name is empty");
        helper.succeed();
    }

    private static ExtractionPlan.DirectoryProbe probe(boolean marker, long sizeA, long sizeB) {
        return new ExtractionPlan.DirectoryProbe() {

            @Override
            public boolean markerPresent() {
                return marker;
            }

            @Override
            public long sizeOf(String name) {
                return "a.jar.packed".equals(name) ? sizeA : sizeB;
            }
        };
    }

    /** 原因の連鎖をたどって、根っこの例外のメッセージを取る。 */
    private static String rootMessageOf(Throwable t) {
        final StringBuilder sb = new StringBuilder();
        for (Throwable current = t; current != null; current = current.getCause()) {
            sb.append(current.getMessage()).append('\n');
        }
        return sb.toString();
    }
}
