package com.kuronami.musicdiscmaker.gametest;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Music Disc Maker の URL 解決失敗が、GUI を閉じて開き直しても (= chunk のアンロード/再ロードを
 * 挟んでも) 見えること、そして決めた条件でだけ消えることを headless で固定する。
 *
 * <p>直すまでは {@code resolveFailed}/{@code failureReason} が {@code getUpdateTag} 経由の同期
 * にしか乗らず {@code saveAdditional} に書かれていなかった。同期パケットが届く相手 (chunk を
 * 見ている client) がいない間に chunk がアンロード → 再ロードされると、失敗した事実ごと消える
 * (「URL を貼ってすぐ GUI を閉じたら、30 秒後の失敗を一度も見ない」)。ここで固定するのは
 * {@link MusicDiscMakerBlockEntity#saveAdditional} が実際に書くこと、そして
 * {@link MusicDiscMakerBlockEntity#onContentsChanged() 消えるべき所でだけ}消えることの両方。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class FailurePersistenceGameTests {

    private static final String TEMPLATE = "empty8x3x8";

    private static MusicDiscMakerBlockEntity place(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, ModBlocks.MUSIC_DISC_MAKER.get());
        final MusicDiscMakerBlockEntity be = helper.getBlockEntity(rel);
        if (be == null) {
            helper.fail("MusicDiscMakerBlockEntity が生成されていない", rel);
        }
        return be;
    }

    /**
     * 失敗の事実と理由が NBT に書かれ、別インスタンスへの読み込みで復元されること。
     * ここが緩むと、chunk 再ロード後に GUI を開き直しても「何も起きなかった」ように見える
     * (修正前の実際の症状)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void resolveFailedSurvivesSaveAndLoad(GameTestHelper helper) {
        final BlockPos rel = new BlockPos(1, 1, 1);
        final MusicDiscMakerBlockEntity be = place(helper, rel);
        if (be == null) {
            return;
        }
        final HolderLookup.Provider registries = helper.getLevel().registryAccess();

        be.setCurrentUrl("https://example.invalid/track");
        be.setResolveFailed(FailureReason.PRIVATE_OR_REMOVED);
        helper.assertTrue(be.isResolveFailed(), "前提: setResolveFailed 直後に isResolveFailed が true でない");

        final CompoundTag saved = be.saveWithoutMetadata(registries);
        helper.assertTrue(saved.getBoolean("resolveFailed"),
                "resolveFailed が saveAdditional (= saveWithoutMetadata) に書かれていない。"
                        + "getUpdateTag 経由の同期にしか乗らないと、chunk 再ロードで失敗の事実が消える");
        helper.assertTrue(FailureReason.PRIVATE_OR_REMOVED.name().equals(saved.getString("failureReason")),
                "failureReason が saveAdditional に書かれていない (実際: " + saved.getString("failureReason") + ")");

        // 別インスタンス (= chunk 再ロード相当) へ読み込み、事実と理由の両方が復元されること。
        final MusicDiscMakerBlockEntity reloaded = new MusicDiscMakerBlockEntity(be.getBlockPos(), be.getBlockState());
        reloaded.loadWithComponents(saved, registries);
        helper.assertTrue(reloaded.isResolveFailed(),
                "NBT から読み直した BlockEntity で isResolveFailed が復元されていない");
        helper.assertTrue(reloaded.getFailureReason() == FailureReason.PRIVATE_OR_REMOVED,
                "NBT から読み直した BlockEntity で failureReason が復元されていない (実際: "
                        + reloaded.getFailureReason() + ")");

        helper.setBlock(rel, Blocks.AIR);
        helper.succeed();
    }

    /** タグ欠落 (この修正より前のセーブ) は失敗なしとして読める (既定値へ安全に倒れる)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void legacySaveWithoutFailureTagsDefaultsToNoFailure(GameTestHelper helper) {
        final BlockPos rel = new BlockPos(1, 1, 1);
        final MusicDiscMakerBlockEntity be = place(helper, rel);
        if (be == null) {
            return;
        }
        final HolderLookup.Provider registries = helper.getLevel().registryAccess();

        be.setResolveFailed(FailureReason.BOT_CHECK);
        final CompoundTag legacy = be.saveWithoutMetadata(registries);
        legacy.remove("resolveFailed");
        legacy.remove("failureReason");

        final MusicDiscMakerBlockEntity reloaded = new MusicDiscMakerBlockEntity(be.getBlockPos(), be.getBlockState());
        reloaded.loadWithComponents(legacy, registries);
        helper.assertFalse(reloaded.isResolveFailed(),
                "失敗タグの無い旧セーブが失敗ありとして読まれている");
        helper.assertTrue(reloaded.getFailureReason() == FailureReason.UNKNOWN,
                "失敗タグの無い旧セーブで failureReason が UNKNOWN 以外になっている (実際: "
                        + reloaded.getFailureReason() + ")");

        helper.setBlock(rel, Blocks.AIR);
        helper.succeed();
    }

    /** 新しい URL がコミットされたら、古い失敗表示は消える。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void resolveFailedClearsWhenNewUrlCommitted(GameTestHelper helper) {
        final BlockPos rel = new BlockPos(1, 1, 1);
        final MusicDiscMakerBlockEntity be = place(helper, rel);
        if (be == null) {
            return;
        }
        be.setCurrentUrl("https://example.invalid/first");
        be.setResolveFailed(FailureReason.CONNECTION_FAILED);
        helper.assertTrue(be.isResolveFailed(), "前提: 失敗が記録されていない");

        be.setCurrentUrl("https://example.invalid/second");
        helper.assertFalse(be.isResolveFailed(),
                "新しい URL をコミットしても古い失敗表示が消えていない");

        helper.setBlock(rel, Blocks.AIR);
        helper.succeed();
    }

    /** 次の解決が始まった (resolving に入った) ら、古い失敗表示は消える。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void resolveFailedClearsWhenNextResolveStarts(GameTestHelper helper) {
        final BlockPos rel = new BlockPos(1, 1, 1);
        final MusicDiscMakerBlockEntity be = place(helper, rel);
        if (be == null) {
            return;
        }
        be.setResolveFailed(FailureReason.REGION_LOCKED);
        helper.assertTrue(be.isResolveFailed(), "前提: 失敗が記録されていない");

        be.setResolving(true);
        helper.assertFalse(be.isResolveFailed(),
                "次の解決 (resolving=true) が始まっても古い失敗表示が消えていない");

        helper.setBlock(rel, Blocks.AIR);
        helper.succeed();
    }

    /**
     * 原因だった入力の空ディスクを取り出したら、古い失敗表示は消える
     * (ペンディングな attempt が無くなった状態で失敗だけ残り続けるのを防ぐ)。
     * 対照として、入力が空にならない限り (無関係なスロット変化では) 消えないことも確認する。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void resolveFailedClearsWhenInputDiscIsTakenOut(GameTestHelper helper) {
        final BlockPos rel = new BlockPos(1, 1, 1);
        final MusicDiscMakerBlockEntity be = place(helper, rel);
        if (be == null) {
            return;
        }
        be.setItem(MusicDiscMakerBlockEntity.SLOT_INPUT, new ItemStack(ModItems.BLANK_DISC.get()));
        be.setResolveFailed(FailureReason.UNSUPPORTED_URL);
        helper.assertTrue(be.isResolveFailed(), "前提: 失敗が記録されていない");

        // 対照: 入力に空ディスクが残ったままの無関係なスロット変化 (出力側) では消えない。
        be.setItem(MusicDiscMakerBlockEntity.SLOT_OUTPUT, ItemStack.EMPTY);
        helper.assertTrue(be.isResolveFailed(),
                "入力の空ディスクが残っているのに、無関係な出力スロットの変化で失敗表示が消えている");

        // 本番: 原因だった入力の空ディスクを取り出す。
        be.removeItem(MusicDiscMakerBlockEntity.SLOT_INPUT, 1);
        helper.assertFalse(be.isResolveFailed(),
                "原因だった入力の空ディスクを取り出しても失敗表示が消えていない");

        helper.setBlock(rel, Blocks.AIR);
        helper.succeed();
    }

    /** 解決が成功してディスクが生成されニュートラルへ戻ったら、古い失敗表示は消える。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void resolveFailedClearsOnResetToNeutral(GameTestHelper helper) {
        final BlockPos rel = new BlockPos(1, 1, 1);
        final MusicDiscMakerBlockEntity be = place(helper, rel);
        if (be == null) {
            return;
        }
        be.setResolveFailed(FailureReason.AGE_RESTRICTED);
        helper.assertTrue(be.isResolveFailed(), "前提: 失敗が記録されていない");

        be.resetToNeutral();
        helper.assertFalse(be.isResolveFailed(),
                "resetToNeutral (解決成功→生成後のニュートラル化) で失敗表示が消えていない");

        helper.setBlock(rel, Blocks.AIR);
        helper.succeed();
    }
}
