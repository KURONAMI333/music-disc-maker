package com.kuronami.musicdiscmaker.gametest;

import java.util.UUID;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.PlaybackGenerations;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * バックパック系 compat の「ロード中に押された停止・再生」の扱い ({@link PlaybackGenerations}) を
 * headless で固定する。
 *
 * <p>compat の client 本体は {@code Minecraft} と TB / SC の型を掴んでいて dedicated server では
 * 読めないので、判断だけを取り出した純ロジックを通す ({@code PlaybackFailure} と同じ seam の
 * 切り方)。ここが緩むと、compat 側の症状は「停止を押したのに数秒後から鳴り出す」
 * 「再生を 2 回押すと 2 曲重なって片方が止まらない」という形で戻ってくる。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class PlaybackGenerationGameTests {

    private static final String TEMPLATE = "empty8x3x8";

    /** 再生 → 即停止。ロードが後から完了しても鳴らしてはならない。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void stopDuringLoadDiscardsThePendingSource(GameTestHelper helper) {
        final PlaybackGenerations<String> generations = new PlaybackGenerations<>();
        final int loading = generations.begin("slot");
        generations.invalidate("slot"); // 停止ボタン (この時点で鳴っている音源はまだ無い)
        helper.assertFalse(generations.isCurrent("slot", loading),
                "停止後に完了したロードが有効なままになっている (停止が空振りする)");
        helper.succeed();
    }

    /** 再生 A → 再生 B。両方のロードが完了しても、鳴ってよいのは B だけ。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void secondPlayCancelsTheFirstLoad(GameTestHelper helper) {
        final PlaybackGenerations<String> generations = new PlaybackGenerations<>();
        final int first = generations.begin("slot");
        final int second = generations.begin("slot");
        helper.assertFalse(generations.isCurrent("slot", first),
                "先の再生が生きたままで、音源が 2 つ鳴る");
        helper.assertTrue(generations.isCurrent("slot", second),
                "後の再生まで捨てられている (連打すると何も鳴らなくなる)");
        helper.succeed();
    }

    /**
     * 鍵が違えば干渉しないこと。SC は backpack(storageUuid) ごとに独立して鳴るので、
     * ここが混ざると別のバックパックの再生が互いを止め合う。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void differentKeysDoNotCancelEachOther(GameTestHelper helper) {
        final PlaybackGenerations<UUID> generations = new PlaybackGenerations<>();
        final UUID left = UUID.nameUUIDFromBytes("left".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final UUID right = UUID.nameUUIDFromBytes("right".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final int leftGeneration = generations.begin(left);
        generations.begin(right);
        helper.assertTrue(generations.isCurrent(left, leftGeneration),
                "別の鍵の再生が互いを打ち消している");
        helper.succeed();
    }

    /** 一度も再生していない鍵は「最新」を持たない (未初期化を有効と読まない)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void unknownKeyIsNeverCurrent(GameTestHelper helper) {
        final PlaybackGenerations<String> generations = new PlaybackGenerations<>();
        helper.assertFalse(generations.isCurrent("never-played", 0),
                "未使用の鍵が有効と判定されている");
        helper.assertFalse(generations.isCurrent("never-played", 1),
                "未使用の鍵が有効と判定されている");
        helper.succeed();
    }
}
