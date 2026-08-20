package com.kuronami.musicdiscmaker.gametest;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.compat.album.AlbumSupport;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.event.ActiveDiscRegistry;
import com.kuronami.musicdiscmaker.event.AlbumPlaybackMirror;
import com.kuronami.musicdiscmaker.event.JukeboxDiscController;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.EitherHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.item.component.BundleContents;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 強化版ジュークボックスのアルバム再生 (Additional Additions 互換) の headless テスト。
 *
 * <p>AA は {@code compileOnly} = <b>このテスト runtime に AA は存在しない</b>。そこで
 * {@link AlbumSupport} の差し込み口へ「バンドルをアルバムと見なす」fake を入れて、common 側の
 * 経路 (受け入れゲート・{@code effectiveDisc} の間接化・トラック送り・index の永続化) だけを固定する。
 * AA 固有の型に依存しないので、AA の API が変わってもこのテストは AA 側の問題を隠さない。
 *
 * <p>fake の差し込みは他バッチへ漏れても無害だが ({@code BUNDLE} をジュークに挿すテストは他に無い)、
 * {@code Services.swapNetwork} と同じ規律でバッチ終了時に戻す。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class AlbumJukeboxGameTests {

    private static final String TEMPLATE = "empty8x3x8";
    private static final String BATCH = "album";
    /**
     * 実 payload と {@link ActiveDiscRegistry} を見る回帰テストは別 batch に置く。batch は順に走り、
     * 同一 batch 内のテストは並行に進むので、{@code Services.swapNetwork} を張っている間に
     * 他テストの packet が混ざらないようにする ({@code ChunkResendRestartGameTests} と同じ理由)。
     */
    private static final String MIRROR_BATCH = "albumMirror";

    private static final String URL_A = "https://example.invalid/album-track-a";
    private static final String URL_B = "https://example.invalid/album-track-b";

    /** 「複数ディスクを束ねた 1 アイテム」の最小実装。中身は stack ごとに持つ (AA のアルバムと同じ形)。 */
    private static final class FakeAlbums implements AlbumSupport.Provider {

        @Override
        public boolean isAlbum(ItemStack stack) {
            return stack.is(Items.BUNDLE);
        }

        @Override
        @Nullable
        public List<ItemStack> contents(ItemStack stack) {
            if (!isAlbum(stack)) {
                return null;
            }
            return stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY)
                    .itemCopyStream().toList();
        }
    }

    private static void installFakeAlbums() {
        AlbumSupport.install(new FakeAlbums());
    }

    @AfterBatch(batch = BATCH)
    public static void restoreAlbums(ServerLevel level) {
        AlbumSupport.install(null);
    }

    @AfterBatch(batch = MIRROR_BATCH)
    public static void restoreAlbumsAfterMirror(ServerLevel level) {
        AlbumSupport.install(null);
    }

    private static ItemStack customDisc(String url, String title, long durationMs) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(),
                new CustomTrackData(url, title, "tester", durationMs, "", false));
        disc.set(DataComponents.JUKEBOX_PLAYABLE,
                new JukeboxPlayable(new EitherHolder<>(SilentSongs.pick(durationMs)), false));
        return disc;
    }

    private static ItemStack album(ItemStack... discs) {
        final ItemStack stack = new ItemStack(Items.BUNDLE);
        stack.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(discs)));
        return stack;
    }

    @Nullable
    private static GoldenJukeboxBlockEntity placeJukebox(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity jukebox = helper.getBlockEntity(rel);
        if (jukebox == null) {
            helper.fail("GoldenJukeboxBlockEntity が生成されていない", rel);
        }
        return jukebox;
    }

    private static String urlOf(GoldenJukeboxBlockEntity jukebox) {
        final CustomTrackData track = jukebox.currentTrack();
        return track == null ? "" : track.url();
    }

    /** 先読みが掴む「次に鳴る曲」の URL。次が無ければ空文字。 */
    private static String nextUrlOf(GoldenJukeboxBlockEntity jukebox) {
        final CustomTrackData track = jukebox.nextPlaybackTrack();
        return track == null ? "" : track.url();
    }

    /**
     * アルバムを挿せる。アルバム item は {@code JUKEBOX_PLAYABLE} を持たないので、v2.2.0 までの
     * ゲートでは 3 箇所 (canPlaceItem / 手挿し / menu) すべてで弾かれていた。ここでは容器としての
     * 受け入れと、ディスクでもアルバムでもない物を弾き続けることの両方を固定する。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void albumIsAccepted(GameTestHelper helper) {
        installFakeAlbums();
        final BlockPos rel = new BlockPos(1, 1, 1);
        final GoldenJukeboxBlockEntity jukebox = placeJukebox(helper, rel);
        if (jukebox == null) {
            return;
        }
        final ItemStack albumStack = album(customDisc(URL_A, "A", 120_000L), customDisc(URL_B, "B", 120_000L));

        helper.assertTrue(jukebox.canPlaceItem(GoldenJukeboxBlockEntity.SLOT_DISC, albumStack),
                "アルバムがディスクスロットに受け入れられない");
        helper.assertTrue(GoldenJukeboxBlockEntity.isPlayableInSlot(albumStack),
                "共通ゲート isPlayableInSlot がアルバムを弾いている");
        // ディスクでもアルバムでもない物は従来どおり弾く (ゲートを開けすぎていないこと)。
        helper.assertFalse(jukebox.canPlaceItem(GoldenJukeboxBlockEntity.SLOT_DISC, new ItemStack(Items.STONE)),
                "ディスクでもアルバムでもない物が受け入れられている");

        jukebox.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, albumStack);
        helper.assertTrue(jukebox.hasDisc(), "アルバム挿入後にスロットが埋まっていない");
        helper.assertTrue(jukebox.getDisc().is(Items.BUNDLE),
                "スロットの生アイテムがアルバム本体でない (容器としての同一性が壊れている)");
        helper.assertValueEqual(jukebox.getAlbumTrack(), 0, "アルバム挿入直後のトラック index");
        helper.succeed();
    }

    /**
     * 再生対象はアルバムの現在トラックのディスクになる ({@code effectiveDisc} の間接化)。
     * あわせて、アルバムでない時は従来どおりスロットの stack そのものが返り index が -1 のままであること
     * (後方互換の契約) を同じテストで固定する。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void effectiveDiscIsCurrentAlbumTrack(GameTestHelper helper) {
        installFakeAlbums();
        final BlockPos albumRel = new BlockPos(1, 1, 1);
        final BlockPos plainRel = new BlockPos(5, 1, 1);
        final GoldenJukeboxBlockEntity withAlbum = placeJukebox(helper, albumRel);
        final GoldenJukeboxBlockEntity withDisc = placeJukebox(helper, plainRel);
        if (withAlbum == null || withDisc == null) {
            return;
        }

        withAlbum.setItem(GoldenJukeboxBlockEntity.SLOT_DISC,
                album(customDisc(URL_A, "A", 120_000L), customDisc(URL_B, "B", 120_000L)));
        helper.assertTrue(withAlbum.effectiveDisc().is(ModItems.CUSTOM_MUSIC_DISC.get()),
                "アルバムの effectiveDisc が中身のディスクになっていない");
        helper.assertValueEqual(urlOf(withAlbum), URL_A, "アルバム再生対象の URL");
        helper.assertValueEqual(withAlbum.albumTrackCount(), 2, "アルバムのトラック数");

        // 従来動作: 素のディスクは index -1 のまま、effectiveDisc = スロットの stack。
        withDisc.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, customDisc(URL_B, "B", 120_000L));
        helper.assertValueEqual(withDisc.getAlbumTrack(), -1, "素のディスクのトラック index");
        helper.assertValueEqual(withDisc.albumTrackCount(), 0, "素のディスクのトラック数");
        helper.assertTrue(withDisc.effectiveDisc() == withDisc.getDisc(),
                "素のディスクで effectiveDisc がスロットの stack と一致しない");
        helper.succeed();
    }

    /**
     * 現在トラックが終わると次のトラックへ送られる。AA の mixin はバニラ jukebox しか狙っていないので、
     * 送りは MDM が自前で持つ (ここが動かないとアルバムは 1 曲目で止まる)。
     * 1 曲目を極短尺にして、tick が進めば必ず終わる形にしてある。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void albumAdvancesToNextTrack(GameTestHelper helper) {
        installFakeAlbums();
        final BlockPos rel = new BlockPos(1, 1, 1);
        final GoldenJukeboxBlockEntity jukebox = placeJukebox(helper, rel);
        if (jukebox == null) {
            return;
        }
        jukebox.setItem(GoldenJukeboxBlockEntity.SLOT_DISC,
                album(customDisc(URL_A, "A", 1L), customDisc(URL_B, "B", 120_000L)));
        helper.assertValueEqual(jukebox.getAlbumTrack(), 0, "前提: 挿入直後のトラック index");

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(jukebox.getAlbumTrack(), 1,
                        "1 曲目が終わってもトラックが送られない"))
                .thenExecute(() -> {
                    helper.assertValueEqual(urlOf(jukebox), URL_B, "送った先の再生対象 URL");
                    helper.assertTrue(jukebox.isVanillaPlaying(),
                            "トラックを送った後に vanilla 再生状態が立っていない");
                })
                .thenSucceed();
    }

    /**
     * 先読みが掴む「次に鳴る曲」({@code nextPlaybackTrack}) の分岐。アルバムの曲送りと
     * 単曲 repeat の折り返しの両方がここへ集まる。
     *
     * <p><b>アルバムの判定が repeat より先にあること</b>を最終トラックで確かめる。repeat を先に
     * 見る実装だと、アルバム + repeat の最終トラックで「次 = 今と同じ曲」が返り、アルバム全体の
     * ループが 1 曲ループに化ける (repeat の意味はアルバムでは全体ループ・単曲では曲内ループ)。
     * 最終トラック以外では両者の答えが一致してしまうので、送った後で判定する。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void nextPlaybackTrackCoversAlbumAdvanceAndSingleRepeat(GameTestHelper helper) {
        installFakeAlbums();
        final BlockPos albumRel = new BlockPos(1, 1, 1);
        final BlockPos plainRel = new BlockPos(5, 1, 1);
        final GoldenJukeboxBlockEntity withAlbum = placeJukebox(helper, albumRel);
        final GoldenJukeboxBlockEntity withDisc = placeJukebox(helper, plainRel);
        if (withAlbum == null || withDisc == null) {
            return;
        }

        // 素のディスク: repeat していなければ曲が終わって止まるだけなので「次」は無い。
        withDisc.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, customDisc(URL_B, "B", 120_000L));
        helper.assertTrue(withDisc.nextPlaybackTrack() == null,
                "repeat していない素のディスクに「次に鳴る曲」がある (無駄なストリームを開く)");
        // 単曲 repeat: 折り返しは同じ曲を頭から鳴らし直すので「次」は今の曲そのもの。
        withDisc.setRepeat(true);
        helper.assertValueEqual(nextUrlOf(withDisc), URL_B, "単曲 repeat の次に鳴る曲");

        // アルバム: 1 曲目を極短尺にして 2 曲目へ送らせ、最終トラックで先頭へ戻ることを見る。
        withAlbum.setRepeat(true);
        withAlbum.setItem(GoldenJukeboxBlockEntity.SLOT_DISC,
                album(customDisc(URL_A, "A", 1L), customDisc(URL_B, "B", 120_000L)));
        helper.assertValueEqual(nextUrlOf(withAlbum), URL_B, "アルバム 1 曲目の次に鳴る曲");

        helper.startSequence()
                .thenWaitUntil(() -> helper.assertValueEqual(withAlbum.getAlbumTrack(), 1,
                        "前提: 1 曲目が終わってもトラックが送られない"))
                .thenExecute(() -> helper.assertValueEqual(nextUrlOf(withAlbum), URL_A,
                        "アルバム最終トラック + repeat の次に鳴る曲 (先頭へ戻らず 1 曲ループに化けている)"))
                .thenSucceed();
    }

    /**
     * 保存 → 復元でトラック index と現在トラック内の再生位置が保たれる。
     * {@code playbackStartGameTime} は「現在トラック内の位置」の意味になるので、index と一緒に
     * 永続化されていないと復元時に別トラックの途中から鳴る。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 200)
    public static void albumTrackAndOffsetSurviveReload(GameTestHelper helper) {
        installFakeAlbums();
        final BlockPos rel = new BlockPos(1, 1, 1);
        final GoldenJukeboxBlockEntity jukebox = placeJukebox(helper, rel);
        if (jukebox == null) {
            return;
        }
        jukebox.setItem(GoldenJukeboxBlockEntity.SLOT_DISC,
                album(customDisc(URL_A, "A", 1L), customDisc(URL_B, "B", 120_000L)));

        helper.startSequence()
                // 2 曲目へ送られるのを待ち、さらに再生位置が 0 でなくなるまで進める。
                .thenWaitUntil(() -> helper.assertValueEqual(jukebox.getAlbumTrack(), 1,
                        "前提: トラックが送られない"))
                .thenIdle(10)
                .thenExecute(() -> {
                    final ServerLevel level = helper.getLevel();
                    final HolderLookup.Provider registries = level.registryAccess();
                    final CompoundTag tag = jukebox.saveWithFullMetadata(registries);
                    helper.assertTrue(tag.contains("albumTrack"),
                            "保存 NBT にトラック index が入っていない");

                    final long elapsedBefore = jukebox.currentElapsedMs();
                    helper.assertTrue(elapsedBefore > 0L, "前提: 再生位置が進んでいない");

                    final GoldenJukeboxBlockEntity restored = new GoldenJukeboxBlockEntity(
                            helper.absolutePos(rel), level.getBlockState(helper.absolutePos(rel)));
                    restored.setLevel(level);
                    restored.loadWithComponents(tag, registries);

                    helper.assertValueEqual(restored.getAlbumTrack(), 1, "復元後のトラック index");
                    helper.assertValueEqual(urlOf(restored), URL_B, "復元後の再生対象 URL");
                    helper.assertValueEqual(restored.currentElapsedMs(), elapsedBefore,
                            "復元後の現在トラック内の再生位置");
                })
                .thenSucceed();
    }

    /**
     * ミラーが管轄するのは<b>アルバムが挿さっている jukebox だけ</b>であること。
     *
     * <p>Additional Additions はバニラ {@code JukeboxBlockEntity} に mixin するので、AA を入れると
     * MDM の tick フックは<b>全てのバニラジュークボックス</b>で走る。素のディスクが入っているだけの
     * 座標でミラーが「MDM ストリーム対象なし」の停止側に落ちると、{@link JukeboxDiscController} が
     * 挿入時に張ったばかりの再生を次の tick で消し、登録まで消える (登録が消えるので late-join 再送も
     * 効かず、入れ直しても 1 tick 後にまた止まる)。
     *
     * <p>AA は {@code compileOnly} = この runtime に存在しないので、AA 側の状態読み取りではなく
     * <b>ミラーの入口</b>を直接叩いて分岐を固定する。{@link AlbumSupport} に fake を差してあるので
     * 「保持 stack がアルバムか」は生きた provider が答える (差していないと {@code ABSENT} が全部
     * false を返し、テストが正しくない理由で通る)。あわせて、アルバム保持時にはミラーが従来どおり
     * 停止もトラック送りも出せること (壊してはいけない遷移) を同じテストで固定する。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = MIRROR_BATCH)
    public static void mirrorLeavesPlainDiscJukeboxesAlone(GameTestHelper helper) {
        installFakeAlbums();
        final ServerLevel level = helper.getLevel();
        final BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        final ItemStack plainDisc = customDisc(URL_A, "A", 120_000L);
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            // 素のディスクの挿入。setTheItem フックと同じ入口で再生が張られる。
            JukeboxDiscController.onContentChanged(level, pos, plainDisc);
            helper.assertTrue(ActiveDiscRegistry.isActive(level.dimension(), pos),
                    "前提: 素のディスクの挿入で再生登録が張られていない");
            net.clear();

            // 次の tick。AA 導入時はこの座標にもミラーが来る。
            AlbumPlaybackMirror.mirrorAlbumJukebox(level, pos, plainDisc, null);

            helper.assertTrue(ActiveDiscRegistry.isActive(level.dimension(), pos),
                    "素のディスクの再生登録がミラーに消された");
            helper.assertValueEqual(net.of(StopDiscPayload.class).size(), 0,
                    "素のディスクの座標へミラーが停止 packet を送った");

            // 判別: 同じ座標・同じ引数でも、保持 stack がアルバムならミラーは停止を出す
            // (遷移「アルバムはあるが再生停止」)。
            final ItemStack albumStack = album(customDisc(URL_B, "B", 120_000L));
            net.clear();
            AlbumPlaybackMirror.mirrorAlbumJukebox(level, pos, albumStack, null);
            helper.assertFalse(ActiveDiscRegistry.isActive(level.dimension(), pos),
                    "アルバム保持時にミラーが停止していない");
            helper.assertValueEqual(net.of(StopDiscPayload.class).size(), 1,
                    "アルバム保持時の停止 packet の数");

            // 遷移「アルバムのトラック送り」: 手持ちはアルバムのままなのでミラーは走り続ける。
            net.clear();
            AlbumPlaybackMirror.mirrorAlbumJukebox(level, pos, albumStack, customDisc(URL_B, "B", 120_000L));
            final ActiveDiscRegistry.Playing playing = ActiveDiscRegistry.current(level.dimension(), pos);
            helper.assertTrue(playing != null && URL_B.equals(playing.track().url()),
                    "アルバムのトラック送りがミラーから張られていない");
            helper.assertValueEqual(net.of(PlayDiscPayload.class).size(), 1,
                    "トラック送りの再生 packet の数");
        } finally {
            Services.swapNetwork(previous);
            ActiveDiscRegistry.stop(level.dimension(), pos);
        }
        helper.succeed();
    }
}
