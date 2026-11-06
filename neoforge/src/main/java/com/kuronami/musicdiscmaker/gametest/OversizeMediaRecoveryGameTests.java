package com.kuronami.musicdiscmaker.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.kuronami.musicdiscmaker.block.BoomboxBlockEntity;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.AlbumStorageBudget;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.item.OversizeMediaRecovery;
import com.kuronami.musicdiscmaker.menu.AlbumMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
//? if <1.21.11 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}

/** 初回同期不能な既存媒体のserver回収。entity spawn失敗時には保存値を一切変えない。 */
public final class OversizeMediaRecoveryGameTests {
    private OversizeMediaRecoveryGameTests() { }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void albumRecoveryPreservesOrderAndSpawnFailurePreservesSource(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack album = oversizedAlbum(0);
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        helper.assertTrue(!AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND)),
                "過大Album fixtureが初回同期拒否域に入っていない");
        final ItemEntity[] firstSpawned = { null };
        final OversizeMediaRecovery.Result failed = OversizeMediaRecovery.recoverAlbum(player, album,
                (level, entity) -> {
                    // event が既に entity を world へ加えた後で spawner が false を返す場合を再現する。
                    if (firstSpawned[0] == null) {
                        firstSpawned[0] = entity;
                        level.addFreshEntity(entity);
                    }
                    return false;
                });
        helper.assertTrue(failed == OversizeMediaRecovery.Result.SPAWN_FAILED && firstSpawned[0] != null
                        && firstSpawned[0].isRemoved() && ordered(AlbumItem.contents(album), 0, 1, 2),
                "実spawn後の拒否でdrop rollbackまたはAlbum保存値の保全に失敗した");
        final List<ItemStack> dropped = new ArrayList<>();
        final OversizeMediaRecovery.Result recovered = OversizeMediaRecovery.recoverAlbum(player, album,
                (level, entity) -> {
                    dropped.add(entity.getItem().copy());
                    return level.addFreshEntity(entity);
                });
        helper.assertTrue(recovered == OversizeMediaRecovery.Result.RECOVERED && orderedStacks(dropped, 0)
                        && ordered(AlbumItem.contents(album), 1, 2),
                "Album回収後のdrop/残り順序またはmetadataが不正");
        helper.assertTrue(OversizeMediaRecovery.recoverAlbum(player, album) == OversizeMediaRecovery.Result.NOT_SOURCE_TOO_LARGE
                        && dropped.size() == 1,
                "回収済みAlbumの再試行で重複dropが発生した");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void recoveryStaleCommitDiscardsSpawnedEntities(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack boombox = new ItemStack(ModItems.BOOMBOX.get());
        boombox.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(oversizedAlbum(60), 2_103L));
        final BoomboxContents before = contents(boombox);
        player.setItemInHand(InteractionHand.MAIN_HAND, boombox);
        final List<ItemEntity> spawned = new ArrayList<>();
        final OversizeMediaRecovery.Result result = OversizeMediaRecovery.recoverHeldBoombox(player, boombox,
                (level, entity) -> {
                    spawned.add(entity);
                    final boolean added = level.addFreshEntity(entity);
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.BOOMBOX.get()));
                    return added;
                });
        helper.assertTrue(result == OversizeMediaRecovery.Result.STALE_SOURCE && !spawned.isEmpty()
                        && spawned.stream().allMatch(ItemEntity::isRemoved) && contents(boombox).equals(before),
                "spawn後のBoombox差替えでdrop rollbackまたは元媒体保全に失敗した");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void albumContentChangeDiscardsSpawnedEntities(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack album = oversizedAlbum(70);
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final List<ItemEntity> spawned = new ArrayList<>();
        final AlbumContents replacement = new AlbumContents(List.of(disc(75)));
        final OversizeMediaRecovery.Result result = OversizeMediaRecovery.recoverAlbum(player, album,
                (level, entity) -> {
                    spawned.add(entity);
                    final boolean added = level.addFreshEntity(entity);
                    AlbumItem.setContents(album, replacement);
                    return added;
                });
        helper.assertTrue(result == OversizeMediaRecovery.Result.STALE_SOURCE && !spawned.isEmpty()
                        && spawned.stream().allMatch(ItemEntity::isRemoved) && AlbumItem.contents(album).equals(replacement),
                "同じAlbum参照の保存内容差替えでdrop rollbackまたは差替え内容保全に失敗した");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void oversizedDiscIsReturnedAndPlacedBoomboxIsGuarded(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack oversizedAlbum = new ItemStack(ModItems.ALBUM.get());
        // 初回menu上限も、単体drop上限も超える1枚。前者未満では回収自体が不要になる。
        final ItemStack oversizedDisc = discWithPadding(80, 1600 * 1024);
        AlbumItem.setContents(oversizedAlbum, new AlbumContents(List.of(oversizedDisc)));
        player.setItemInHand(InteractionHand.MAIN_HAND, oversizedAlbum);
        // 単独で挿入上限を超える1枚は外へ出せない分割になるので、その1枚を返して空にする。
        final List<ItemStack> returned = new ArrayList<>();
        helper.assertTrue(OversizeMediaRecovery.recoverAlbum(player, oversizedAlbum,
                        (level, entity) -> { returned.add(entity.getItem().copy()); return level.addFreshEntity(entity); })
                        == OversizeMediaRecovery.Result.RECOVERED
                        && AlbumItem.contents(oversizedAlbum).isEmpty() && orderedStacks(returned, 80),
                "1枚で上限超過した盤を1枚ずつ返して空にできなかった");

        final BlockPos relative = new BlockPos(4, 1, 4);
        final BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, ModBlocks.BOOMBOX.get());
        final BoomboxBlockEntity be = (BoomboxBlockEntity) helper.getLevel().getBlockEntity(pos);
        final ItemStack stored = new ItemStack(ModItems.BOOMBOX.get());
        stored.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(oversizedAlbum(90), 3_007L));
        final BoomboxContents storedBefore = contents(stored);
        be.setStored(stored);
        player.setPos(pos.getX() + 20.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        helper.assertTrue(OversizeMediaRecovery.recoverPlacedBoombox(player, helper.getLevel(), pos, be)
                        == OversizeMediaRecovery.Result.STALE_SOURCE && contents(stored).equals(storedBefore),
                "遠距離の設置Boombox回収を受け入れた");
        helper.setBlock(relative, Blocks.AIR);
        final BoomboxContents afterRemoval = contents(stored);
        final OversizeMediaRecovery.Result replaced = OversizeMediaRecovery.recoverPlacedBoombox(player, helper.getLevel(), pos, be);
        helper.assertTrue(replaced == OversizeMediaRecovery.Result.STALE_SOURCE,
                "置換済みBlockEntityのBoombox回収を受け入れた");
        helper.assertTrue(contents(stored).equals(afterRemoval),
                "置換済みBlockEntityの拒否処理が撤去後の媒体を変えた");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void boomboxAlbumRecoveryAndEmptyMediaNoop(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack boombox = new ItemStack(ModItems.BOOMBOX.get());
        boombox.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(oversizedAlbum(10), 901L));
        player.setItemInHand(InteractionHand.MAIN_HAND, boombox);
        final OversizeMediaRecovery.Result result = OversizeMediaRecovery.recoverHeldBoombox(player, boombox);
        final ItemStack remaining = contents(boombox).disc();
        helper.assertTrue(result == OversizeMediaRecovery.Result.RECOVERED && remaining.is(ModItems.ALBUM.get())
                        && ordered(AlbumItem.contents(remaining), 11, 12),
                "Boombox内Albumを安全に縮小して順序を保てない");

        final ItemStack safeAlbum = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(safeAlbum, new AlbumContents(List.of(disc(30))));
        // player inventoryの大きい媒体は分割同期され、開いた媒体の回収対象にはならない。
        final ItemStack blocker = album(40, 6);
        player.setItemInHand(InteractionHand.MAIN_HAND, safeAlbum);
        player.getInventory().setItem(9, blocker);
        final OversizeMediaRecovery.Result untouched = OversizeMediaRecovery.recoverAlbum(player, safeAlbum);
        helper.assertTrue(untouched == OversizeMediaRecovery.Result.NOT_SOURCE_TOO_LARGE
                        && ordered(AlbumItem.contents(safeAlbum), 30),
                "回収不要な安全なAlbumをばらまいた");

        final ItemStack emptyAlbum = new ItemStack(ModItems.ALBUM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, emptyAlbum);
        final boolean emptyAlbumInitialFits = AlbumMenu.fitsInitialMenuSync(player.getInventory(),
                BoomboxSource.held(InteractionHand.MAIN_HAND));
        final OversizeMediaRecovery.Result emptyAlbumResult = OversizeMediaRecovery.recoverAlbum(player, emptyAlbum);
        helper.assertTrue(emptyAlbumResult == OversizeMediaRecovery.Result.NOTHING_TO_RECOVER,
                "空Albumで回収不要の結果を返さなかった: initialFits="
                        + emptyAlbumInitialFits + ", result=" + emptyAlbumResult);
        final ItemStack emptyBoombox = new ItemStack(ModItems.BOOMBOX.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, emptyBoombox);
        helper.assertTrue(OversizeMediaRecovery.recoverHeldBoombox(player, emptyBoombox)
                        == OversizeMediaRecovery.Result.NOTHING_TO_RECOVER,
                "空Boomboxで回収不要の結果を返さなかった");

        // 装備/offhandはこのmenuにslotとして登録されず、初回container packetにも含まれない。
        // 大きい装備品のためにAlbum/Boomboxのopenまで拒否してはならない。
        player.getInventory().setItem(9, ItemStack.EMPTY);
        player.getInventory().setItem(40, blocker);
        player.setItemInHand(InteractionHand.MAIN_HAND, safeAlbum);
        // 旧実装はgetContainerSize()まで走査してoffhandを初回packetに含めていた。
        // 同じ実codec・同じ1.5 MiB予算では旧集合だけが拒否されることを対照に残す。
        helper.assertFalse(legacyInitialSlotsFit(player.getInventory(), safeAlbum, AlbumItem.GUI_CAPACITY),
                "旧getContainerSize集合がoffhandを同期対象としていないため回帰対照になっていない");
        helper.assertTrue(AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND)),
                "menu外offhandの大きいitemで安全なAlbumの初回同期を拒否した");
        player.setItemInHand(InteractionHand.MAIN_HAND, emptyBoombox);
        helper.assertFalse(legacyInitialSlotsFit(player.getInventory(), emptyBoombox, 1),
                "旧Boombox集合がoffhandを同期対象としていないため回帰対照になっていない");
        helper.assertTrue(com.kuronami.musicdiscmaker.menu.BoomboxMenu.fitsInitialMenuSync(
                        player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND)),
                "menu外offhandの大きいitemで空Boomboxの初回同期を拒否した");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void sourceCountChangeRollsBackRecovery(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        for (boolean machine : new boolean[] { false, true }) {
            final ItemStack source = machine ? new ItemStack(ModItems.BOOMBOX.get()) : oversizedAlbum(100);
            if (machine) source.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(oversizedAlbum(110), 2_209L));
            final ItemStack before = source.copy();
            player.setItemInHand(InteractionHand.MAIN_HAND, source);
            final List<ItemEntity> spawned = new ArrayList<>();
            final OversizeMediaRecovery.EntitySpawner spawner = (level, entity) -> {
                spawned.add(entity);
                final boolean added = level.addFreshEntity(entity);
                source.setCount(0);
                return added;
            };
            final OversizeMediaRecovery.Result result = machine
                    ? OversizeMediaRecovery.recoverHeldBoombox(player, source, spawner)
                    : OversizeMediaRecovery.recoverAlbum(player, source, spawner);
            helper.assertTrue(result == OversizeMediaRecovery.Result.STALE_SOURCE && !spawned.isEmpty()
                            && spawned.stream().allMatch(ItemEntity::isRemoved) && source.getCount() == 0,
                    "same-reference count mutation did not reject/rollback recovery");
            // Restore only the fixture count so matches also checks the retained contents.
            source.setCount(1);
            helper.assertTrue(ItemStack.matches(before, source), "stale recovery rewrote stored media");
        }
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void placedCommitExceptionRestoresMedia(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final BlockPos relative = new BlockPos(4, 1, 4);
        final BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, ModBlocks.BOOMBOX.get());
        final ThrowingBoombox be = new ThrowingBoombox(pos, helper.getLevel().getBlockState(pos));
        helper.getLevel().removeBlockEntity(pos);
        helper.getLevel().setBlockEntity(be);
        final ItemStack source = new ItemStack(ModItems.BOOMBOX.get());
        source.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(oversizedAlbum(110), 2_209L));
        be.setStored(source);
        final ItemStack before = source.copy();
        final List<ItemEntity> spawned = new ArrayList<>();
        be.armed = true;
        final OversizeMediaRecovery.Result result;
        try {
            result = OversizeMediaRecovery.recoverPlacedBoombox(player, helper.getLevel(), pos, be,
                    (level, entity) -> { spawned.add(entity); return level.addFreshEntity(entity); });
        } finally {
            be.armed = false;
        }
        helper.assertTrue(be.reached && result == OversizeMediaRecovery.Result.SPAWN_FAILED,
                "fixture did not fail after the media write");
        helper.assertTrue(!spawned.isEmpty() && spawned.stream().allMatch(ItemEntity::isRemoved)
                        && ItemStack.matches(source, before), "commit exception lost original media");
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void openAfterReductionKeepsEveryDisc(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack album = oversizedAlbum(200);
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        final AlbumStorageBudget.Result sync = AlbumMenu.inspectInitialMenuSync(player.getInventory(),
                BoomboxSource.held(InteractionHand.MAIN_HAND));
        helper.assertTrue(!sync.fits(), "過大Album fixtureが初回同期拒否域に入っていない");
        helper.assertTrue(OversizeMediaRecovery.openAfterReduction(player, sync, album),
                "容量超過のAlbumを縮めて開けなかった");
        helper.assertTrue(ordered(AlbumItem.contents(album), 201, 202),
                "収まる分として保存順の末尾が残っていない");
        final List<String> returned = returnedDiscTitles(player, helper);
        returned.sort(String::compareTo);
        helper.assertTrue(returned.equals(List.of("track-200")),
                "外した盤が持ち物にも足元にも無い/重複している: " + returned);
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void singleOversizedMediumIsReturnedAndTheMachineOpens(GameTestHelper helper) {
        final ServerPlayer player = networkedPlayer(helper);
        final ItemStack box = new ItemStack(ModItems.BOOMBOX.get());
        // 1枚が単独で挿入上限を超える媒体。外へ出せる分割が無いので丸ごと返して空にする。
        box.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(discWithPadding(80, 1600 * 1024), 4_211L));
        withCapturedNetwork(player, () -> {
            player.setItemInHand(InteractionHand.MAIN_HAND, box);
            final List<ItemStack> returned = new ArrayList<>();
            helper.assertTrue(OversizeMediaRecovery.recoverHeldBoombox(player, box,
                            (level, entity) -> { returned.add(entity.getItem().copy()); return level.addFreshEntity(entity); })
                            == OversizeMediaRecovery.Result.RECOVERED,
                    "単独で挿入上限を超える媒体を返して空にできなかった");
            helper.assertTrue(contents(box).disc().isEmpty() && orderedStacks(returned, 80),
                    "返した媒体が元の1枚と一致しない、または機体が空になっていない");
            final List<String> present = returnedDiscTitles(player, helper);
            helper.assertTrue(present.equals(List.of("track-80")),
                    "返した媒体が持ち物か足元に1枚だけ存在しない: " + present);
            Services.MENU.openBoomboxMenu(player, BoomboxSource.held(InteractionHand.MAIN_HAND));
            helper.assertTrue(player.containerMenu instanceof BoomboxMenu menu && menu.currentStack() == box,
                    "媒体を返して空にした機体を開けなかった");
        });
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void boomboxRecoveryKeepsPlaybackState(GameTestHelper helper) {
        final ServerPlayer player = networkedPlayer(helper);
        // oversizedAlbum は先頭1枚 (first) を外すと収まる。残るのは first+1, first+2 で、添字は1つずれる。
        final ItemStack stopped = boomboxWith(oversizedAlbum(300), 5_001L);
        final ItemStack playing = boomboxAt(oversizedAlbum(310), 5_002L,
                new PlaybackCursor(2, 0, PlaybackCursor.State.STOPPED, 3L), 0L, false);
        final ItemStack paused = boomboxAt(oversizedAlbum(320), 5_003L,
                new PlaybackCursor(1, 0, PlaybackCursor.State.PAUSED, 4L), 7_500L, true);
        final ItemStack playingRemoved = boomboxWith(oversizedAlbum(330), 5_004L);
        withCapturedNetwork(player, () -> {
            // 止まっていた機体は、開いただけでは鳴り出さない。
            player.setItemInHand(InteractionHand.MAIN_HAND, stopped);
            helper.assertTrue(OversizeMediaRecovery.recoverHeldBoombox(player, stopped)
                            == OversizeMediaRecovery.Result.RECOVERED
                            && ordered(AlbumItem.contents(contents(stopped).disc()), 301, 302),
                    "停止中の機体の媒体を縮小できなかった");
            helper.assertTrue(BoomboxPlayback.stateOf(stopped).cursor().state() == PlaybackCursor.State.STOPPED
                            && !BoomboxPlayback.isPlaying(5_001L),
                    "開いただけの機体が再生を始めた");

            // 残った盤を鳴らしていた機体は、同じ盤 (添字は1つ前へ) のまま鳴り続ける。
            player.setItemInHand(InteractionHand.MAIN_HAND, playing);
            helper.assertTrue(BoomboxPlayback.startCarried(player, playing), "再生fixtureを開始できなかった");
            helper.assertTrue(contents(playing).cursor().discIndex() == 2, "再生fixtureが3枚目から始まっていない");
            helper.assertTrue(OversizeMediaRecovery.recoverHeldBoombox(player, playing)
                            == OversizeMediaRecovery.Result.RECOVERED
                            && ordered(AlbumItem.contents(contents(playing).disc()), 311, 312),
                    "再生中の機体の媒体を縮小できなかった");
            final PlaybackCursor afterPlaying = contents(playing).cursor();
            helper.assertTrue(BoomboxPlayback.isPlaying(5_002L)
                            && afterPlaying.state() == PlaybackCursor.State.PLAYING
                            && afterPlaying.discIndex() == 1 && afterPlaying.trackIndex() == 0
                            && orderedStacks(List.of(AlbumItem.contents(contents(playing).disc())
                                    .discAt(afterPlaying.discIndex())), 312),
                    "縮小で鳴っていた曲が変わった/止まった: " + afterPlaying);
            BoomboxPlayback.stop(helper.getLevel(), player.chunkPosition(), 5_002L);

            // 一時停止中の機体は、位置・一時停止位置・シャッフルを保って止まったまま。
            player.setItemInHand(InteractionHand.MAIN_HAND, paused);
            helper.assertTrue(OversizeMediaRecovery.recoverHeldBoombox(player, paused)
                            == OversizeMediaRecovery.Result.RECOVERED,
                    "一時停止中の機体の媒体を縮小できなかった");
            final BoomboxContents afterPaused = contents(paused);
            helper.assertTrue(!BoomboxPlayback.isPlaying(5_003L)
                            && afterPaused.cursor().state() == PlaybackCursor.State.PAUSED
                            && afterPaused.cursor().discIndex() == 0
                            && afterPaused.pausedOffsetMs() == 7_500L
                            && afterPaused.shuffle() && afterPaused.shuffleSeed() == 42L
                            && afterPaused.shuffleAnchorDisc() == 0,
                    "縮小で一時停止の位置・offset・シャッフルが消えた: " + afterPaused.cursor()
                            + " offset=" + afterPaused.pausedOffsetMs() + " shuffle=" + afterPaused.shuffle());

            // 鳴っていた盤そのものが外へ出た時は、残った先頭から鳴り続ける。
            player.setItemInHand(InteractionHand.MAIN_HAND, playingRemoved);
            helper.assertTrue(BoomboxPlayback.startCarried(player, playingRemoved), "再生fixture2を開始できなかった");
            helper.assertTrue(OversizeMediaRecovery.recoverHeldBoombox(player, playingRemoved)
                            == OversizeMediaRecovery.Result.RECOVERED,
                    "先頭を鳴らしていた機体の媒体を縮小できなかった");
            final PlaybackCursor afterRemoved = contents(playingRemoved).cursor();
            helper.assertTrue(BoomboxPlayback.isPlaying(5_004L)
                            && afterRemoved.state() == PlaybackCursor.State.PLAYING && afterRemoved.discIndex() == 0,
                    "鳴っていた盤を返した後に残りから鳴り続けない: " + afterRemoved);
            BoomboxPlayback.stop(helper.getLevel(), player.chunkPosition(), 5_004L);
        });
        helper.succeed();
    }

    private static ItemStack boomboxAt(ItemStack medium, long id, PlaybackCursor cursor, long pausedOffsetMs,
            boolean shuffle) {
        final ItemStack box = new ItemStack(ModItems.BOOMBOX.get());
        box.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(medium, id, cursor, pausedOffsetMs,
                false, shuffle, shuffle ? 42L : 0L, shuffle ? 1 : PlaybackCursor.NO_INDEX,
                shuffle ? 0 : PlaybackCursor.NO_INDEX, 100));
        return box;
    }

    private static final class ThrowingBoombox extends BoomboxBlockEntity {
        boolean armed;
        boolean reached;
        ThrowingBoombox(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { super(pos, state); }
        @Override public void setChanged() {
            if (armed) { reached = true; throw new IllegalStateException("injected dirty failure"); }
            super.setChanged();
        }
    }

    private static FakePlayer player(GameTestHelper helper) {
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "recovery-test"));
        final var pos = helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1));
        player.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return player;
    }

    private static ItemStack boomboxWith(ItemStack medium, long id) {
        final ItemStack box = new ItemStack(ModItems.BOOMBOX.get());
        box.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(medium, id));
        return box;
    }

    private static ServerPlayer networkedPlayer(GameTestHelper helper) {
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        return player;
    }

    /** menu open と再生開始の送信を捕捉し、mock player へ実送信しない。 */
    private static void withCapturedNetwork(ServerPlayer player, Runnable body) {
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            body.run();
        } finally {
            try {
                player.containerMenu = player.inventoryMenu;
            } finally {
                try {
                    player.discard();
                } finally {
                    Services.swapNetwork(previous);
                }
            }
        }
    }

    private static ItemStack album(int first) {
        return album(first, 4);
    }

    private static ItemStack album(int first, int count) {
        final List<ItemStack> discs = new ArrayList<>();
        for (int i = 0; i < count; i++) discs.add(disc(first + i));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(discs));
        return album;
    }

    private static ItemStack disc(int index) {
        return disc(index, 70);
    }

    private static ItemStack disc(int index, int sizeClass) {
        return discWithPadding(index, sizeClass == 70 ? 300 * 1024 : 1100 * 1024);
    }

    /**
     * 1枚外すまで初回menu枠 (1.5 MiB) に収まらない既存媒体のfixture。各drop自体は1 MiB以内で、
     * 3枚のpayloadはAlbum自体の2 MiB framingには収まる (容量超過として縮められる側)。
     */
    static ItemStack oversizedAlbum(int first) {
        final List<ItemStack> discs = new ArrayList<>();
        for (int i = 0; i < 3; i++) discs.add(discWithPadding(first + i, 600 * 1024));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(discs));
        return album;
    }

    static ItemStack discWithPadding(int index, int paddingBytes) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), new CustomTrackData("https://example.invalid/" + index, "track-" + index, "test", 1_000L, "", false));
        // Extra item metadata exercises the synchronization limit without a multi-track disc.
        final net.minecraft.nbt.CompoundTag padding = new net.minecraft.nbt.CompoundTag();
        padding.putByteArray("fixture_padding", new byte[paddingBytes]);
        disc.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                net.minecraft.world.item.component.CustomData.of(padding));
        return disc;
    }

    private static BoomboxContents contents(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.BOOMBOX_CONTENTS.get(), BoomboxContents.EMPTY);
    }

    /** 3.0.1までのgetContainerSize走査が構成していた初回slot集合。回帰用のみ。 */
    private static boolean legacyInitialSlotsFit(net.minecraft.world.entity.player.Inventory inventory,
            ItemStack heldSource, int internalSlots) {
        final List<ItemStack> slots = new ArrayList<>(internalSlots + inventory.getContainerSize());
        if (heldSource.is(ModItems.ALBUM.get())) {
            final List<ItemStack> saved = AlbumItem.contents(heldSource).discs();
            for (int i = 0; i < internalSlots; i++) {
                slots.add(i < saved.size() ? saved.get(i).copy() : ItemStack.EMPTY);
            }
        } else {
            for (int i = 0; i < internalSlots; i++) slots.add(ItemStack.EMPTY);
        }
        boolean sourceInInventory = false;
        for (int i = 9; i < inventory.getContainerSize(); i++) {
            final ItemStack stack = inventory.getItem(i);
            slots.add(stack.copy());
            sourceInInventory |= stack == heldSource;
        }
        for (int i = 0; i < 9; i++) {
            final ItemStack stack = inventory.getItem(i);
            slots.add(stack.copy());
            sourceInInventory |= stack == heldSource;
        }
        if (!sourceInInventory) slots.add(heldSource.copy());
        return AlbumStorageBudget.fitsInitialMenuSync(slots, ItemStack.EMPTY,
                inventory.player.level().registryAccess());
    }

    static boolean ordered(AlbumContents contents, int... ids) {
        if (contents.size() != ids.length) return false;
        for (int i = 0; i < ids.length; i++) {
            final CustomTrackData track = contents.discAt(i).get(ModDataComponents.CUSTOM_TRACK.get());
            if (track == null || !track.title().equals("track-" + ids[i])) return false;
        }
        return true;
    }

    private static boolean orderedStacks(List<ItemStack> stacks, int... ids) {
        return ordered(new AlbumContents(stacks), ids);
    }

    /** 外した盤が持ち物か足元のどちらかに存在する時、その表示名を集める。 */
    static List<String> returnedDiscTitles(net.minecraft.world.entity.player.Player player, GameTestHelper helper) {
        final List<String> titles = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            addDiscTitle(player.getInventory().getItem(i), titles);
        }
        final net.minecraft.world.phys.Vec3 center = net.minecraft.world.phys.Vec3.atCenterOf(
                helper.absolutePos(net.minecraft.core.BlockPos.ZERO));
        final net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(
                center.x - 8.0, center.y - 8.0, center.z - 8.0,
                center.x + 8.0, center.y + 8.0, center.z + 8.0);
        for (final ItemEntity entity : helper.getLevel().getEntitiesOfClass(ItemEntity.class, area)) {
            if (!entity.isRemoved()) addDiscTitle(entity.getItem(), titles);
        }
        return titles;
    }

    private static void addDiscTitle(ItemStack stack, List<String> titles) {
        final CustomTrackData track = stack.get(ModDataComponents.CUSTOM_TRACK.get());
        if (track != null) titles.add(track.title());
    }
}
