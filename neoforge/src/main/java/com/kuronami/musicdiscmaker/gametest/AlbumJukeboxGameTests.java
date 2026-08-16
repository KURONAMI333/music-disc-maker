package com.kuronami.musicdiscmaker.gametest;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.compat.album.AlbumSupport;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
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
}
