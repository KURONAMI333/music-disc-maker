package com.kuronami.musicdiscmaker.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.compat.album.AlbumSupport;
import com.kuronami.musicdiscmaker.compat.pocketjukebox.PocketJukeboxAdvance;
import com.kuronami.musicdiscmaker.compat.pocketjukebox.PocketJukeboxTracks;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Additional Additions の携帯ジュークボックス (Pocket Jukebox) 対応のうち、client を持たずに判定できる
 * 部分を headless に固定する。
 *
 * <p>AA は compileOnly のソフト依存 (gametest classpath には無い) なので、実際の
 * {@code PocketJukeboxPlayer} は動かさない。固定するのは compat が持っている 2 つの純ロジック:
 *
 * <ul>
 *   <li>{@link PocketJukeboxTracks} — 携帯ジュークボックスの stack から「今のトラックのディスク」を
 *       復元する読み取り (vanilla の {@code CONTAINER} component と {@link AlbumSupport} だけを使う)</li>
 *   <li>{@link PocketJukeboxAdvance} — AA の曲送りをいつまで保留するかの状態機械</li>
 * </ul>
 *
 * <p>client 側の配線 ({@code PocketJukeboxClient} の {@code SoundManager} 操作・mixin の適用) は
 * ここでは踏めない。踏めない範囲は報告に明示すること。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class PocketJukeboxGameTests {

    private static final String TEMPLATE = "empty8x3x8";
    private static final long DURATION_MS = 240_000L;

    /* ---------------------------------------------------------------- トラックの読み取り */

    /**
     * 素のディスク 1 枚を飲み込んだ携帯ジュークボックスは「1 トラックのアルバム」として読める。
     * トラック 1 以降は空 (AA 側はここで {@code stop()} する)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = "pocketJukebox")
    public static void singleDiscIsReadAsTrackZeroOnly(GameTestHelper helper) {
        final ItemStack pocket = pocketHolding(customDisc("https://example.invalid/single", "Single"));

        final CustomTrackData first = PocketJukeboxTracks.mdmTrackAt(pocket, 0);
        helper.assertTrue(first != null && "https://example.invalid/single".equals(first.url()),
                "素のディスクがトラック 0 として読めていない");
        helper.assertTrue(PocketJukeboxTracks.mdmTrackAt(pocket, 1) == null,
                "1 枚しか入っていないのにトラック 1 が読めている");
        helper.assertTrue(PocketJukeboxTracks.mdmTrackAt(pocket, -1) == null,
                "トラック番号 -1 (再生前) で読めてしまっている");
        helper.succeed();
    }

    /**
     * <b>回帰の要:</b> バニラ / 他 MOD のディスクは MDM のトラックとして読めない。ここが真になると
     * 保留がかかって AA の曲送りが止まる = 携帯ジュークボックスがバニラディスクごと壊れる。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = "pocketJukebox")
    public static void vanillaDiscIsNotAnMdmTrack(GameTestHelper helper) {
        final ItemStack pocket = pocketHolding(new ItemStack(Items.MUSIC_DISC_CAT));
        helper.assertTrue(PocketJukeboxTracks.mdmTrackAt(pocket, 0) == null,
                "バニラのディスクが MDM のトラックとして読まれている");

        // MDM のディスクでも曲データが無ければ対象外 (作りかけ・データ破損)。
        final ItemStack blank = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        helper.assertTrue(PocketJukeboxTracks.mdmTrackAt(pocketHolding(blank), 0) == null,
                "曲データの無いカスタムディスクがトラックとして読まれている");

        helper.assertTrue(PocketJukeboxTracks.mdmTrackAt(new ItemStack(Items.STICK), 0) == null,
                "何も飲み込んでいない stack からトラックが読めている");
        helper.succeed();
    }

    /** アルバムを飲み込んだ場合は {@link AlbumSupport} 経由で中身がトラック列になる。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = "pocketJukebox")
    public static void albumContentsBecomeTheTrackList(GameTestHelper helper) {
        final ItemStack album = new ItemStack(Items.BOOK);
        final List<ItemStack> tracks = List.of(
                customDisc("https://example.invalid/a", "A"),
                new ItemStack(Items.MUSIC_DISC_CAT),
                customDisc("https://example.invalid/c", "C"));

        final AlbumSupport.Provider previous = AlbumSupport.install(new FakeAlbums(album, tracks));
        try {
            final ItemStack pocket = pocketHolding(album);
            final CustomTrackData zero = PocketJukeboxTracks.mdmTrackAt(pocket, 0);
            helper.assertTrue(zero != null && "https://example.invalid/a".equals(zero.url()),
                    "アルバムのトラック 0 が読めていない");
            helper.assertTrue(PocketJukeboxTracks.mdmTrackAt(pocket, 1) == null,
                    "アルバム中のバニラディスクが MDM のトラックとして読まれている");
            final CustomTrackData two = PocketJukeboxTracks.mdmTrackAt(pocket, 2);
            helper.assertTrue(two != null && "https://example.invalid/c".equals(two.url()),
                    "アルバムのトラック 2 が読めていない");
            helper.assertTrue(PocketJukeboxTracks.mdmTrackAt(pocket, 3) == null,
                    "アルバムの末尾を超えたトラックが読めている");
        } finally {
            AlbumSupport.install(previous);
        }
        helper.succeed();
    }

    /** ラジオ (無限長) はストリーム対象にしない (蓄音機の判断 S2 に揃える)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = "pocketJukebox")
    public static void radioIsNotStreamable(GameTestHelper helper) {
        helper.assertTrue(PocketJukeboxTracks.streamable(
                new CustomTrackData("https://example.invalid/song", "Song", "a", DURATION_MS, "", false)),
                "通常の曲がストリーム対象から外れている");
        helper.assertTrue(!PocketJukeboxTracks.streamable(
                new CustomTrackData("https://example.invalid/radio", "Radio", "a", 0L, "", true)),
                "ラジオがストリーム対象になっている");
        helper.assertTrue(!PocketJukeboxTracks.streamable(null),
                "MDM ディスクでないものがストリーム対象になっている");
        helper.succeed();
    }

    /* ---------------------------------------------------------------- 曲送りの保留 */

    /**
     * <b>これが今回の判定式そのもの。</b> 実音声が鳴っている間は曲送りを保留し、ストリームが終わったら
     * 保留を解除する。従来 (この compat が無い状態) は無音ディスクの 1 秒で送られていた。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = "pocketJukebox")
    public static void advanceIsHeldWhileTheRealTrackPlays(GameTestHelper helper) {
        final AtomicLong now = new AtomicLong(1_000L);
        final PocketJukeboxAdvance advance = new PocketJukeboxAdvance(now::get);
        final String url = "https://example.invalid/held";

        helper.assertTrue(advance.isIdle(), "初期状態で担当を持っている");
        helper.assertTrue(!advance.holdsAdvance(0), "担当が無いのに曲送りを保留している");

        advance.assign(0, url, true);
        helper.assertTrue(advance.handles(0, url), "担当が記録されていない");
        // ロード中 (音源はまだ無い) も保留する。ここを通すと、実音声が始まる前に AA が送ってしまう。
        helper.assertTrue(advance.holdsAdvance(0), "ロード中に曲送りが保留されていない");

        final AtomicBoolean ended = new AtomicBoolean(false);
        helper.assertTrue(advance.attach(0, url, new HeldVoice(ended), DURATION_MS),
                "担当が変わっていないのに音源を受け付けていない");
        helper.assertTrue(advance.holdsAdvance(0), "再生中に曲送りが保留されていない");
        helper.assertTrue(!advance.holdsAdvance(1),
                "AA が別トラックへ進んだ後も古い担当で保留している");

        now.addAndGet(DURATION_MS - 1_000L);
        helper.assertTrue(advance.holdsAdvance(0), "曲の途中で保留が切れている");

        // ストリーム終端 → 保留解除。ここで AA の startNextTrack が通る。
        ended.set(true);
        helper.assertTrue(!advance.holdsAdvance(0), "ストリーム終端後も曲送りが保留されている");
        helper.succeed();
    }

    /**
     * バニラディスク / ラジオの担当は保留しない (AA の従来の尺で普段どおり送られる)。
     * URL 解決に失敗した時も同じ扱いに落として、1 曲目で固まらないようにする。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = "pocketJukebox")
    public static void nonStreamingAssignmentNeverHoldsTheAdvance(GameTestHelper helper) {
        final AtomicLong now = new AtomicLong(1_000L);
        final PocketJukeboxAdvance advance = new PocketJukeboxAdvance(now::get);

        advance.assign(0, "", false);
        helper.assertTrue(advance.handles(0, ""), "非ストリーム担当が記録されていない");
        helper.assertTrue(!advance.holdsAdvance(0), "非ストリーム担当が曲送りを保留している");
        helper.assertTrue(!advance.attach(0, "", new HeldVoice(new AtomicBoolean(false)), DURATION_MS),
                "非ストリーム担当が音源を受け付けている");

        final String url = "https://example.invalid/broken";
        advance.assign(1, url, true);
        helper.assertTrue(advance.holdsAdvance(1), "前提: ロード中は保留する");
        advance.giveUp(1, url);
        helper.assertTrue(!advance.holdsAdvance(1), "ロード失敗後も曲送りが保留されている");
        // 別の担当への giveUp は無視される (遅れて届いた失敗が今の再生を殺さない)。
        advance.assign(2, url, true);
        advance.giveUp(1, url);
        helper.assertTrue(advance.holdsAdvance(2), "古い担当の失敗報告が今の担当の保留を解除している");
        helper.succeed();
    }

    /**
     * 音源が終端も自己停止も報せずに失われた場合でも、壁時計の締切で保留を諦める
     * (諦めないと、ディスクを抜くまで曲送りが二度と起きない)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = "pocketJukebox")
    public static void orphanedVoiceReleasesTheHoldOnDeadline(GameTestHelper helper) {
        final AtomicLong now = new AtomicLong(1_000L);
        final PocketJukeboxAdvance advance = new PocketJukeboxAdvance(now::get);
        final String url = "https://example.invalid/orphan";

        advance.assign(0, url, true);
        // 「鳴り続けている」と嘘をつく音源 = SoundEngine に捨てられたが誰も気づいていない状態。
        advance.attach(0, url, new HeldVoice(new AtomicBoolean(false)), DURATION_MS);
        now.addAndGet(DURATION_MS + PocketJukeboxAdvance.OVERRUN_GRACE_MS - 1L);
        helper.assertTrue(advance.holdsAdvance(0), "余裕の内側で保留が切れている");
        now.addAndGet(2L);
        helper.assertTrue(!advance.holdsAdvance(0), "尺 + 余裕を過ぎても保留が続いている");

        // ロードが固まった場合も同じ (音源が一度も来ない)。
        final String slow = "https://example.invalid/slow";
        advance.assign(1, slow, true);
        helper.assertTrue(advance.holdsAdvance(1), "前提: ロード中は保留する");
        now.addAndGet(PocketJukeboxAdvance.LOAD_TIMEOUT_MS + 1L);
        helper.assertTrue(!advance.holdsAdvance(1), "ロードが固まっても保留が続いている");
        helper.succeed();
    }

    /**
     * 停止 → 同じトラック番号での再生し直しを「担当済み」と誤読しない (url も鍵にしてある)。
     * {@code release} は止めるべき音源を返す。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = "pocketJukebox")
    public static void releaseHandsBackTheVoiceAndForgetsTheAssignment(GameTestHelper helper) {
        final PocketJukeboxAdvance advance = new PocketJukeboxAdvance(() -> 0L);
        final String url = "https://example.invalid/again";
        final AtomicBoolean ended = new AtomicBoolean(false);
        final HeldVoice voice = new HeldVoice(ended);

        advance.assign(0, url, true);
        advance.attach(0, url, voice, DURATION_MS);
        helper.assertTrue(advance.release() == voice, "release が鳴っている音源を返していない");
        helper.assertTrue(advance.isIdle(), "release 後も担当が残っている");
        helper.assertTrue(!advance.handles(0, url), "release 後も同じ担当と判定されている");
        helper.assertTrue(!advance.holdsAdvance(0), "release 後も曲送りが保留されている");

        // 別の曲を同じトラック番号 0 に張り替えたら、前の url では担当済みにならない。
        advance.assign(0, "https://example.invalid/other", true);
        helper.assertTrue(!advance.handles(0, url), "url が違うのに担当済みと判定されている");
        helper.succeed();
    }

    /* ---------------------------------------------------------------- 部品 */

    private static ItemStack customDisc(String url, String title) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(),
                new CustomTrackData(url, title, "tester", DURATION_MS, "", false));
        return disc;
    }

    /** 携帯ジュークボックスの飲み込み方 (vanilla の CONTAINER component 先頭 1 件) を再現する。 */
    private static ItemStack pocketHolding(ItemStack stored) {
        final ItemStack pocket = new ItemStack(Items.STICK);
        pocket.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(stored)));
        return pocket;
    }

    /** 終端フラグが立つまで「鳴っている」と答える音源。 */
    private record HeldVoice(AtomicBoolean ended) implements PocketJukeboxAdvance.Voice {

        @Override
        public boolean isPlaying() {
            return !ended.get();
        }

        @Override
        public void stop() {
            ended.set(true);
        }
    }

    /** 1 つの stack だけをアルバムとして答える {@link AlbumSupport.Provider}。 */
    private record FakeAlbums(ItemStack album, List<ItemStack> tracks) implements AlbumSupport.Provider {

        @Override
        public boolean isAlbum(ItemStack stack) {
            return ItemStack.isSameItemSameComponents(stack, album);
        }

        @Override
        @Nullable
        public List<ItemStack> contents(ItemStack stack) {
            return isAlbum(stack) ? new ArrayList<>(tracks) : null;
        }
    }
}
