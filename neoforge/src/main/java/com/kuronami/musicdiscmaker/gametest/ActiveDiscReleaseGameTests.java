package com.kuronami.musicdiscmaker.gametest;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.event.ActiveDiscRegistry;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.EitherHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * jukebox が<b>プレイヤーの手作業以外で</b>消えたときに {@link ActiveDiscRegistry} が解放される
 * ことを固定する headless テスト。
 *
 * <p>解放されないと、その次元のエントリは server が止まるまで残り続ける。残ったエントリは
 * chunk 再送の走査に引っかかり続け、late-joiner へ「そこには無いジュークボックス」の再生を
 * 送ろうとする。
 *
 * <p>あわせて<b>解放してはいけない側</b>も固定する — chunk のアンロードで block entity が
 * 捨てられるのは日常茶飯事で、そこで忘れると挿さったままのディスクが chunk 再入のたびに
 * 頭から鳴り直す。
 *
 * <p>{@link ActiveDiscRegistry} は static な global state なので、{@link ActiveDiscRegistry#clear()}
 * を撃つテストは 1 本ずつ別 batch に置く (batch は順に走る)。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class ActiveDiscReleaseGameTests {

    private static final String TEMPLATE = "empty8x3x8";
    private static final long DURATION_MS = 120_000L;

    private static ItemStack customDisc() {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(),
                new CustomTrackData("https://example.invalid/release-disc", "Release Disc", "tester", DURATION_MS,
                        "", false));
        disc.set(DataComponents.JUKEBOX_PLAYABLE,
                new JukeboxPlayable(new EitherHolder<>(SilentSongs.pick(DURATION_MS)), false));
        return disc;
    }

    /** ディスクを挿した jukebox を置いて、registry に載ったことを確かめる。 */
    private static BlockPos armedJukebox(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, Blocks.JUKEBOX);
        if (!(helper.getBlockEntity(rel) instanceof JukeboxBlockEntity jukebox)) {
            helper.fail("JukeboxBlockEntity が生成されていない", rel);
            return null;
        }
        // 挿入は本番と同じ経路 (setTheItem → JukeboxBlockEntityMixin → JukeboxDiscController)。
        jukebox.setTheItem(customDisc());
        final BlockPos pos = helper.absolutePos(rel);
        helper.assertTrue(ActiveDiscRegistry.isActive(helper.getLevel().dimension(), pos),
                "前提: ディスク挿入で再生が registry に登録されていない");
        return pos;
    }

    /**
     * 爆発と同じ経路 ({@code Level#destroyBlock}) でブロックが消えたら registry も解放されること。
     * プレイヤーの手作業の破壊イベントは通らないので、ここが唯一の解放点になる。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = "discReleaseDestroy")
    public static void destroyingTheBlockReleasesTheEntry(GameTestHelper helper) {
        ActiveDiscRegistry.clear();
        final ServerLevel level = helper.getLevel();
        final BlockPos pos = armedJukebox(helper, new BlockPos(1, 1, 1));
        if (pos == null) {
            return;
        }
        level.destroyBlock(pos, false);
        helper.assertFalse(ActiveDiscRegistry.isActive(level.dimension(), pos),
                "爆発で消えた jukebox のエントリが registry に残っている (server 停止まで消えない)");
        helper.succeed();
    }

    /** コマンドと同じ経路 ({@code /setblock air}) でも解放されること。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = "discReleaseSetBlock")
    public static void replacingTheBlockWithAirReleasesTheEntry(GameTestHelper helper) {
        ActiveDiscRegistry.clear();
        final ServerLevel level = helper.getLevel();
        final BlockPos pos = armedJukebox(helper, new BlockPos(2, 1, 2));
        if (pos == null) {
            return;
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertFalse(ActiveDiscRegistry.isActive(level.dimension(), pos),
                "/setblock air で消えた jukebox のエントリが registry に残っている");
        helper.succeed();
    }

    /**
     * block entity が捨てられただけ (chunk アンロード) では<b>忘れないこと</b>。
     * ブロック自体はまだそこにあり、ディスクも挿さったままなので、忘れると chunk 再入のたびに
     * 頭から鳴り直す。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = "discReleaseUnload")
    public static void droppingTheBlockEntityAloneKeepsTheEntry(GameTestHelper helper) {
        ActiveDiscRegistry.clear();
        final ServerLevel level = helper.getLevel();
        final BlockPos rel = new BlockPos(3, 1, 3);
        final BlockPos pos = armedJukebox(helper, rel);
        if (pos == null) {
            return;
        }
        // chunk アンロードで起きること = ブロックはそのまま、block entity だけが捨てられる。
        if (helper.getBlockEntity(rel) instanceof JukeboxBlockEntity jukebox) {
            jukebox.setRemoved();
        }
        helper.assertTrue(level.getBlockState(pos).is(Blocks.JUKEBOX),
                "前提: ブロックはまだそこにある");
        helper.assertTrue(ActiveDiscRegistry.isActive(level.dimension(), pos),
                "chunk アンロードで再生を忘れている。挿さったままのディスクが chunk 再入のたびに"
                        + "頭から鳴り直す");
        helper.succeed();
    }

    /**
     * 次元単位の解放。次元がアンロードされてもエントリが残ると、その次元へ戻るまで
     * (= server が止まるまで) 掃除の機会が無い。他の次元を巻き添えにしないことも見る。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = "discReleaseDimension")
    public static void clearingOneDimensionLeavesTheOthersAlone(GameTestHelper helper) {
        ActiveDiscRegistry.clear();
        final BlockPos here = new BlockPos(10, 64, 10);
        final BlockPos there = new BlockPos(20, 64, 20);
        final CustomTrackData track = customDisc().get(ModDataComponents.CUSTOM_TRACK.get());
        final ResourceKey<Level> overworld = Level.OVERWORLD;
        final ResourceKey<Level> nether = Level.NETHER;
        final long now = System.currentTimeMillis();
        ActiveDiscRegistry.start(overworld, here, track, now);
        ActiveDiscRegistry.start(nether, there, track, now);

        ActiveDiscRegistry.clear(nether);

        helper.assertFalse(ActiveDiscRegistry.isActive(nether, there),
                "アンロードされた次元のエントリが残っている");
        helper.assertTrue(ActiveDiscRegistry.isActive(overworld, here),
                "別の次元のエントリまで巻き添えで消えている");
        helper.succeed();
    }
}
