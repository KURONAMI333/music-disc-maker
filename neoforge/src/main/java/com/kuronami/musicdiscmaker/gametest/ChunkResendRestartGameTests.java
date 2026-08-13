package com.kuronami.musicdiscmaker.gametest;

import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.event.ActiveDiscRegistry;
import com.kuronami.musicdiscmaker.event.AlbumPlaybackMirror;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * chunk が player へ送り直される瞬間 (死亡リスポーン・TP・再ログイン・chunk 再ロード) に、
 * 再生中のトラックが頭出しへ巻き戻らないことを固定する headless テスト。
 *
 * <p>{@link ActiveDiscRegistry#activeInChunk} は曲尺を過ぎたエントリを<b>この呼び出しで消す</b>。
 * 消えた後の registry は「一度も再生していない」と区別が付かないので、chunk 再送の走査
 * ({@code JukeboxHandler#onChunkWatch} / Fabric の {@code ChunkWatchMixin}) はスロットに刺さった
 * ままの custom disc を「まだ始まっていない」と読み、{@code start(..., now)} で起点を打ち直して
 * offset {@code 0L} で送る。同じ経路を {@link AlbumPlaybackMirror} も踏む。
 *
 * <p>ここで固定するのは<b>正しい期待値</b>側 — 曲尺を過ぎても registry はその位置の再生を
 * 覚えていること、および起点が第三者の chunk 再送で打ち直されないこと。
 *
 * <p>{@link ActiveDiscRegistry} は static な global state なので、各テストは入口で
 * {@link ActiveDiscRegistry#clear()} して前のテストの残りを断つ。assert が投げた時点で
 * エントリは残るが、registry を読むテストはこの 2 本しかないので実害は無い。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class ChunkResendRestartGameTests {

    private static final String TEMPLATE = "empty8x3x8";
    /** 曲尺。prune の判定は呼び出し側が渡す nowMillis で行うので、実時間は待たない。 */
    private static final long DURATION_MS = 120_000L;

    private static ItemStack customDisc(long durationMs) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(),
                new CustomTrackData("https://example.invalid/resend-disc", "Resend Disc", "tester", durationMs, "",
                        false));
        disc.set(DataComponents.JUKEBOX_PLAYABLE,
                new JukeboxPlayable(new EitherHolder<>(SilentSongs.pick(durationMs)), false));
        return disc;
    }

    /**
     * 曲尺を過ぎた後に chunk が送り直されても、registry がその位置の再生を忘れないこと。
     *
     * <p>忘れた瞬間に chunk 再送の第 2 ループ (「ブロックには disc があるのに registry に居ない」)
     * が成立し、刺さったままのディスクが offset {@code 0L} で再生され直す = 報告されている
     * 「死亡 → リスポーンでトラックが頭に戻る」。起点も {@code now} に打ち直されるので、
     * その後に同じ chunk へ入った別 player は別の位置から鳴り始める = 「多重再生」の形。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void finishedTrackStaysKnownSoChunkResendDoesNotRestartIt(GameTestHelper helper) {
        ActiveDiscRegistry.clear();

        final BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, Blocks.JUKEBOX);
        final BlockPos pos = helper.absolutePos(rel);
        final ServerLevel level = helper.getLevel();
        final ResourceKey<Level> dim = level.dimension();

        if (!(helper.getBlockEntity(rel) instanceof JukeboxBlockEntity jukebox)) {
            helper.fail("JukeboxBlockEntity が生成されていない", rel);
            return;
        }
        // 挿入は本番と同じ経路 (setTheItem → JukeboxBlockEntityMixin → JukeboxDiscController)。
        jukebox.setTheItem(customDisc(DURATION_MS));

        final ActiveDiscRegistry.Playing started = ActiveDiscRegistry.current(dim, pos);
        helper.assertTrue(started != null, "前提: ディスク挿入で再生が registry に登録されていない");
        final long origin = started.startMillis();
        final ChunkPos chunk = new ChunkPos(pos);

        // 対照: 再生中の chunk 再送。現在位置つきで返り、起点は動かない。
        final List<ActiveDiscRegistry.Playing> midway =
                ActiveDiscRegistry.activeInChunk(dim, chunk, origin + DURATION_MS / 2L);
        helper.assertTrue(midway.size() == 1, "再生中の jukebox が chunk 再送の対象から漏れている");
        helper.assertTrue(midway.get(0).startMillis() == origin, "再生中の chunk 再送で起点が動いている");

        // 本番: 曲尺を過ぎた時点で chunk が送り直される (死亡リスポーン・TP・再ログイン)。
        ActiveDiscRegistry.activeInChunk(dim, chunk, origin + DURATION_MS + 1L);

        helper.assertTrue(jukebox.getTheItem().is(ModItems.CUSTOM_MUSIC_DISC.get()),
                "前提: ディスクはスロットに刺さったまま (vanilla jukebox は曲が終わっても排出しない)");
        helper.assertTrue(ActiveDiscRegistry.isActive(dim, pos),
                "曲尺を過ぎた再生が registry から消えている。chunk 再送はこれを「まだ始まっていない」"
                        + "と読んで、刺さったままのディスクを offset 0 で再生し直す (= 頭出し)");
        final ActiveDiscRegistry.Playing after = ActiveDiscRegistry.current(dim, pos);
        helper.assertTrue(after != null, "chunk 再送の後に再生エントリが失われている");
        helper.assertTrue(after.startMillis() == origin,
                "chunk 再送で再生の起点が打ち直されている。後から同じ chunk へ入った player は"
                        + "別の位置から鳴り始める (= 多重再生)");
        helper.succeed();
    }

    /**
     * chunk 再送の prune が、進行中のアルバム再生の起点を打ち直さないこと。
     *
     * <p>{@link AlbumPlaybackMirror} は「同じ url を既にストリーム中なら何もしない (毎 tick の
     * 再送を防ぐ)」という契約で毎 tick 呼ばれる。prune がその判定材料を消すと、次の tick の
     * mirror が同じトラックを新規扱いして起点を打ち直し、<b>chunk 内の全員へ</b> offset 0 で
     * 送り直す。別 player の死亡リスポーンが、聴いている全員のトラックを頭に戻す形。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void albumMirrorKeepsItsOriginAcrossChunkResend(GameTestHelper helper) {
        ActiveDiscRegistry.clear();

        final ServerLevel level = helper.getLevel();
        final BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        final ResourceKey<Level> dim = level.dimension();
        final ItemStack disc = customDisc(DURATION_MS);
        // 起点は wall-clock なので、打ち直しが起きたことを見るには実時間の差が要る (下の thenIdle)。
        final long[] origin = new long[1];

        helper.startSequence()
                .thenExecute(() -> {
                    AlbumPlaybackMirror.mirror(level, pos, disc);
                    final ActiveDiscRegistry.Playing started = ActiveDiscRegistry.current(dim, pos);
                    helper.assertTrue(started != null, "前提: アルバム再生が registry にミラーされていない");
                    origin[0] = started.startMillis();
                    // 対照: 同じ url を鳴らし続けている間、毎 tick の mirror は起点を動かさない。
                    AlbumPlaybackMirror.mirror(level, pos, disc);
                    final ActiveDiscRegistry.Playing same = ActiveDiscRegistry.current(dim, pos);
                    helper.assertTrue(same != null && same.startMillis() == origin[0],
                            "同じ url の再生中に mirror が起点を打ち直している");
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    // 別 player がこの chunk に入り、chunk 再送の走査が曲尺超えを prune する。
                    ActiveDiscRegistry.activeInChunk(dim, new ChunkPos(pos), origin[0] + DURATION_MS + 1L);
                    // アルバムは同じトラックを鳴らし続けている (次の tick の mirror)。
                    AlbumPlaybackMirror.mirror(level, pos, disc);

                    final ActiveDiscRegistry.Playing after = ActiveDiscRegistry.current(dim, pos);
                    helper.assertTrue(after != null, "chunk 再送の後にアルバム再生が失われている");
                    helper.assertTrue(after.startMillis() == origin[0],
                            "chunk 再送の prune でアルバム再生の起点が打ち直されている。"
                                    + "mirror は同じトラックを新規扱いして chunk 内の全員へ offset 0 で送り直す");
                })
                .thenSucceed();
    }
}
