package com.kuronami.musicdiscmaker.gametest;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.EitherHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 強化版ジュークボックスのホッパー I/O の headless テスト。
 *
 * <p>{@code GoldenJukeboxBlockEntity} は既に {@code Container} (1 スロット) を実装しているため、
 * vanilla のホッパー機構 (plain {@code Container} 相手は {@code WorldlyContainer} 無しでも
 * {@code canPlaceItem}/自由な取り出しが素通しで通る) を経由した実際の投入・取り出しが機能することを
 * ここで固定する。ホッパー自体は vanilla の tick 機構をそのまま使う (専用の配線コードは無い)。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class GoldenJukeboxHopperGameTests {

    private static final String TEMPLATE = "empty8x3x8";

    private static ItemStack customDisc(long durationMs) {
        return customDisc(durationMs, SilentSongs.pick(durationMs));
    }

    /**
     * 実尺と無音バケット song を別々に指定する。実運用でも両者は一致しない (バケットは 10 秒刻みの
     * 切り上げ・尺不明は 3600 秒) ので、その乖離を意図的に大きく取ったディスクを作れるようにする。
     */
    private static ItemStack customDisc(long durationMs, ResourceKey<JukeboxSong> silentSong) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(),
                new CustomTrackData("https://example.invalid/hopper-disc", "Hopper Disc", "tester", durationMs, "", false));
        disc.set(DataComponents.JUKEBOX_PLAYABLE,
                new JukeboxPlayable(new EitherHolder<>(silentSong), false));
        return disc;
    }

    /**
     * ホッパー投入 → 再生開始。金ジュークの真上にホッパーを置き、ディスクを積んで自然 tick させると、
     * vanilla のホッパー機構が下 (金ジューク) へ押し込み、挿入を検知して自動再生が始まること。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void hopperInsertStartsPlayback(GameTestHelper helper) {
        final BlockPos jukeboxRel = new BlockPos(1, 1, 1);
        final BlockPos hopperRel = jukeboxRel.above();
        helper.setBlock(jukeboxRel, ModBlocks.GOLDEN_JUKEBOX.get());
        // 既定 facing は DOWN (真下の container へ押し込む)。
        helper.setBlock(hopperRel, Blocks.HOPPER.defaultBlockState());

        final GoldenJukeboxBlockEntity jukebox = helper.getBlockEntity(jukeboxRel);
        if (jukebox == null) {
            helper.fail("GoldenJukeboxBlockEntity が生成されていない", jukeboxRel);
            return;
        }
        if (!(helper.getBlockEntity(hopperRel) instanceof HopperBlockEntity hopper)) {
            helper.fail("HopperBlockEntity が生成されていない", hopperRel);
            return;
        }
        hopper.setItem(0, customDisc(120_000L));

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(jukebox.hasDisc(), "ホッパーからディスクが投入されていない");
                    helper.assertTrue(jukebox.getDisc().is(ModItems.CUSTOM_MUSIC_DISC.get()),
                            "投入されたアイテムが custom disc ではない");
                })
                .thenExecute(() -> helper.assertTrue(jukebox.isVanillaPlaying(),
                        "ホッパー投入で自動再生が始まっていない"))
                .thenSucceed();
    }

    /**
     * 再生終了 → ホッパー取り出し。金ジュークの真下にホッパーを置き、既に再生中のディスクを
     * 手動停止 (自然終了と同じ「スロットは埋まったまま・再生は止まっている」状態を作る) した後、
     * vanilla のホッパー機構が自然 tick で吸い出すこと。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void hopperExtractsAfterPlaybackStops(GameTestHelper helper) {
        final BlockPos jukeboxRel = new BlockPos(1, 1, 1);
        final BlockPos hopperRel = jukeboxRel.below();
        helper.setBlock(jukeboxRel, ModBlocks.GOLDEN_JUKEBOX.get());
        // 下向きホッパーの吸出し面 (上面) を金ジュークに向けるには、金ジュークの真下に置けば
        // vanilla の「ホッパーは自分の真上の container から吸い出す」既定動作がそのまま使える。
        helper.setBlock(hopperRel, Blocks.HOPPER.defaultBlockState());

        final GoldenJukeboxBlockEntity jukebox = helper.getBlockEntity(jukeboxRel);
        if (jukebox == null) {
            helper.fail("GoldenJukeboxBlockEntity が生成されていない", jukeboxRel);
            return;
        }
        if (!(helper.getBlockEntity(hopperRel) instanceof HopperBlockEntity hopper)) {
            helper.fail("HopperBlockEntity が生成されていない", hopperRel);
            return;
        }
        jukebox.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, customDisc(120_000L));
        helper.assertTrue(jukebox.isVanillaPlaying(), "前提: 挿入直後に再生が始まっていない");
        // 再生終了と同じ状態 (スロットは埋まったまま・再生は止まっている) を作る。
        jukebox.setPaused(true);
        helper.assertFalse(jukebox.isVanillaPlaying(), "前提: 一時停止で vanilla 再生状態が止まっていない");

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(hopper.getItem(0).is(ModItems.CUSTOM_MUSIC_DISC.get()),
                        "停止中のディスクがホッパーに吸い出されていない"))
                .thenExecute(() -> helper.assertFalse(jukebox.hasDisc(),
                        "ホッパー取り出し後も金ジューク側にディスクが残っている"))
                .thenSucceed();
    }

    /**
     * 実曲の自然終了 → ホッパー取り出し (<b>一時停止を通さない</b>)。
     *
     * <p>{@link #hopperExtractsAfterPlaybackStops} は {@code setPaused(true)} で「信号が 0 になる唯一の
     * 状態」を先に作ってから測るので、無音バケット song の尺で信号が張り付くバグを構造的に踏まない。
     * ここは再生したまま実曲を鳴り終わらせる。
     *
     * <p>ディスクは実尺 50ms・無音バケット {@code silent_3600s} (= 72000 tick)。これは「尺不明の
     * ディスク」で実際に選ばれる組み合わせで、信号をバケット基準で判定していると 1 時間ホッパーが
     * 止まる。判定が実尺基準なら、バケットがまだ鳴っている最中に信号が落ちる — 最後の
     * {@code isVanillaPlaying} の表明がその同時性を固定する (バケット基準だと必ず false になる)。
     *
     * <p>GameTest サーバは 20 TPS ではなく上限なしで回る (67 テストが実時間 3 秒弱) ので、
     * <b>tick 数を壁時計 ms の代わりに使わない</b>。待機は信号そのものの条件で書く。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 600)
    public static void hopperExtractsAfterRealTrackEnds(GameTestHelper helper) {
        final BlockPos jukeboxRel = new BlockPos(1, 1, 1);
        final BlockPos hopperRel = jukeboxRel.below();
        helper.setBlock(jukeboxRel, ModBlocks.GOLDEN_JUKEBOX.get());
        helper.setBlock(hopperRel, Blocks.HOPPER.defaultBlockState());

        final GoldenJukeboxBlockEntity jukebox = helper.getBlockEntity(jukeboxRel);
        if (jukebox == null) {
            helper.fail("GoldenJukeboxBlockEntity が生成されていない", jukeboxRel);
            return;
        }
        if (!(helper.getBlockEntity(hopperRel) instanceof HopperBlockEntity hopper)) {
            helper.fail("HopperBlockEntity が生成されていない", hopperRel);
            return;
        }
        // 実尺 50ms・バケット 3600 秒 (SilentSongs.pick(0) = 尺不明のディスクが選ぶバケット)。
        jukebox.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, customDisc(50L, SilentSongs.pick(0L)));
        helper.assertTrue(jukebox.isRedstonePlaying(), "前提: 挿入直後に redstone 信号が立っていない");
        helper.assertFalse(helper.getBlockState(hopperRel).getValue(HopperBlock.ENABLED),
                "前提: 再生開始でホッパーが無効化されていない (この時点で有効だと以降の待機が無意味)");

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertFalse(jukebox.isRedstonePlaying(),
                        "実曲 (50ms) が終わっても redstone 信号が落ちない = 無音バケット (3600 秒) で判定している"))
                .thenExecute(() -> {
                    helper.assertFalse(jukebox.isPaused(), "前提: 一時停止を経由してしまっている");
                    helper.assertTrue(jukebox.isVanillaPlaying(),
                            "信号が落ちた時点で無音バケットも止まっている = 実尺でなくバケットで判定している");
                })
                .thenWaitUntil(() -> helper.assertTrue(hopper.getItem(0).is(ModItems.CUSTOM_MUSIC_DISC.get()),
                        "実曲終了後もディスクがホッパーに吸い出されていない"))
                .thenExecute(() -> helper.assertFalse(jukebox.hasDisc(),
                        "ホッパー取り出し後も金ジューク側にディスクが残っている"))
                .thenSucceed();
    }
}
