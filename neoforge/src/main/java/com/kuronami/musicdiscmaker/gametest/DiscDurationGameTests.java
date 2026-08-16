package com.kuronami.musicdiscmaker.gametest;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.EitherHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.JukeboxPlayable;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 強化版ジュークボックスの「総尺」の headless テスト。
 *
 * <p>v2.2.1 まで {@code trackDurationMs()} は MDM の custom disc しか見ておらず、vanilla / 他 MOD の
 * ディスク (例: [Let's Do] Furniture の 2 枚) では常に 0 を返していた。GUI の秒数表示・進捗 fill・
 * つまみは全てこの値をゲートにしているので、**MDM 以外のディスクでは何も出なかった**。
 *
 * <p>ここで固定するのは 3 点。
 * <ol>
 *   <li>vanilla のレコードで尺が取れる ({@code jukebox_song} の {@code length_in_seconds} 由来)</li>
 *   <li>custom disc は<b>実尺</b>を返す。custom disc も {@code SilentSongs} のバケット長を持つ
 *       {@code jukebox_playable} を抱えているので、フォールバックを先に見ると 95 秒が 100 秒に化ける。
 *       このテストは<b>参照順序を固定する</b>のが主目的</li>
 *   <li>{@code isSeekable()} は custom disc だけ。尺が取れるようになっても vanilla のレコードで
 *       つまみを出してはいけない (実音は {@code JukeboxSongPlayer} が鳴らしていて頭出しできず、
 *       {@code seekTo} が即 return するのでバーが戻るだけになる)</li>
 * </ol>
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class DiscDurationGameTests {

    private static final String TEMPLATE = "empty8x3x8";
    private static final String BATCH = "discDuration";

    /** 実尺 95 秒。{@link SilentSongs} のバケットは 100 秒になるので、両者を取り違えたら落ちる。 */
    private static final long CUSTOM_DURATION_MS = 95_000L;

    @Nullable
    private static GoldenJukeboxBlockEntity placeJukebox(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity jukebox = helper.getBlockEntity(rel);
        if (jukebox == null) {
            helper.fail("GoldenJukeboxBlockEntity が生成されていない", rel);
        }
        return jukebox;
    }

    private static ItemStack customDisc(long durationMs) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(),
                new CustomTrackData("https://example.invalid/duration", "T", "tester", durationMs, "", false));
        disc.set(DataComponents.JUKEBOX_PLAYABLE,
                new JukeboxPlayable(new EitherHolder<>(SilentSongs.pick(durationMs)), false));
        return disc;
    }

    /**
     * vanilla のレコードでも総尺が取れる。値そのものはバニラのデータ次第なので、
     * 「0 でない」「常識的な範囲に収まる」だけを見る (バージョン更新で秒数が変わっても落とさない)。
     * あわせて、尺が取れてもシーク可能にはしないことを同じテストで固定する。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void vanillaDiscReportsDuration(GameTestHelper helper) {
        final BlockPos rel = new BlockPos(1, 1, 1);
        final GoldenJukeboxBlockEntity jukebox = placeJukebox(helper, rel);
        if (jukebox == null) {
            return;
        }
        jukebox.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, new ItemStack(Items.MUSIC_DISC_13));

        final long dur = jukebox.trackDurationMs();
        helper.assertTrue(dur > 0L,
                "vanilla のレコードで総尺が 0 (GUI の秒数表示と進捗バーが出ない): " + dur);
        helper.assertTrue(dur < 3_600_000L, "vanilla のレコードの総尺が非現実的に長い: " + dur);
        helper.assertTrue(jukebox.currentTrack() == null,
                "vanilla のレコードが custom track として解決されている");
        helper.assertFalse(jukebox.isSeekable(),
                "vanilla のレコードでシーク可能になっている (つまみが出るが seekTo は即 return する)");
        helper.succeed();
    }

    /**
     * custom disc は実尺を返す。<b>このテストが参照順序の番人</b> — {@code jukebox_song} の
     * フォールバックを先に見ると {@link SilentSongs} のバケット長 (100 秒) が返って落ちる。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void customDiscReportsRealDurationNotSilentBucket(GameTestHelper helper) {
        final BlockPos rel = new BlockPos(1, 1, 1);
        final GoldenJukeboxBlockEntity jukebox = placeJukebox(helper, rel);
        if (jukebox == null) {
            return;
        }
        jukebox.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, customDisc(CUSTOM_DURATION_MS));

        helper.assertValueEqual(jukebox.trackDurationMs(), CUSTOM_DURATION_MS,
                "custom disc の総尺 (バケット長に化けていないか)");
        helper.assertTrue(jukebox.isSeekable(), "custom disc がシーク可能になっていない");
        helper.succeed();
    }

    /** ディスクが無い時は 0 のまま (空のジュークで秒数やバーを出さない)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void emptyJukeboxHasNoDuration(GameTestHelper helper) {
        final BlockPos rel = new BlockPos(1, 1, 1);
        final GoldenJukeboxBlockEntity jukebox = placeJukebox(helper, rel);
        if (jukebox == null) {
            return;
        }
        helper.assertValueEqual(jukebox.trackDurationMs(), 0L, "空のジュークボックスの総尺");
        helper.assertFalse(jukebox.isSeekable(), "空のジュークボックスがシーク可能になっている");
        helper.succeed();
    }
}
