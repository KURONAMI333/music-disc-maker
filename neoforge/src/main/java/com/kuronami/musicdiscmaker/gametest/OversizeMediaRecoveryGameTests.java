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
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.item.OversizeMediaRecovery;
import com.kuronami.musicdiscmaker.menu.AlbumMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
//? if <1.21.2 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}

/** 初回同期不能な既存媒体のserver回収。entity spawn失敗時には保存値を一切変えない。 */
public final class OversizeMediaRecoveryGameTests {
    private OversizeMediaRecoveryGameTests() { }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void albumRecoveryPreservesOrderAndSpawnFailurePreservesSource(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack album = album(0);
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        helper.assertTrue(!AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND)),
                "過大Album fixtureが初回同期拒否域に入っていない");
        final int[] attempts = { 0 };
        final ItemEntity[] firstSpawned = { null };
        final OversizeMediaRecovery.Result failed = OversizeMediaRecovery.recoverAlbum(player, album,
                (level, entity) -> {
                    if (++attempts[0] == 1) {
                        firstSpawned[0] = entity;
                        return level.addFreshEntity(entity);
                    }
                    return false;
                });
        helper.assertTrue(failed == OversizeMediaRecovery.Result.SPAWN_FAILED && firstSpawned[0] != null
                        && firstSpawned[0].isRemoved() && ordered(AlbumItem.contents(album), 0, 1, 2, 3),
                "実spawn後の拒否でdrop rollbackまたはAlbum保存値の保全に失敗した");
        final List<ItemStack> dropped = new ArrayList<>();
        final OversizeMediaRecovery.Result recovered = OversizeMediaRecovery.recoverAlbum(player, album,
                (level, entity) -> {
                    dropped.add(entity.getItem().copy());
                    return level.addFreshEntity(entity);
                });
        helper.assertTrue(recovered == OversizeMediaRecovery.Result.RECOVERED && orderedStacks(dropped, 0, 1)
                        && ordered(AlbumItem.contents(album), 2, 3),
                "Album回収後のdrop/残り順序またはmetadataが不正");
        helper.assertTrue(OversizeMediaRecovery.recoverAlbum(player, album) == OversizeMediaRecovery.Result.NOT_SOURCE_TOO_LARGE
                        && dropped.size() == 2,
                "回収済みAlbumの再試行で重複dropが発生した");
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void recoveryStaleCommitDiscardsSpawnedEntities(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack boombox = new ItemStack(ModItems.BOOMBOX.get());
        boombox.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(album(60), 2_103L));
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

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void albumContentChangeDiscardsSpawnedEntities(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack album = album(70);
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

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void oversizedDiscAndPlacedBoomboxAreUntouched(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack oversizedAlbum = new ItemStack(ModItems.ALBUM.get());
        final ItemStack oversizedDisc = disc(80, 256);
        AlbumItem.setContents(oversizedAlbum, new AlbumContents(List.of(oversizedDisc)));
        final AlbumContents albumBefore = AlbumItem.contents(oversizedAlbum);
        player.setItemInHand(InteractionHand.MAIN_HAND, oversizedAlbum);
        helper.assertTrue(OversizeMediaRecovery.recoverAlbum(player, oversizedAlbum)
                        == OversizeMediaRecovery.Result.ITEM_TOO_LARGE && AlbumItem.contents(oversizedAlbum).equals(albumBefore),
                "1枚で上限超過した盤を回収して元媒体を変えた");

        final BlockPos relative = new BlockPos(4, 1, 4);
        final BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, ModBlocks.BOOMBOX.get());
        final BoomboxBlockEntity be = (BoomboxBlockEntity) helper.getLevel().getBlockEntity(pos);
        final ItemStack stored = new ItemStack(ModItems.BOOMBOX.get());
        stored.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(album(90), 3_007L));
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

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void boomboxAlbumRecoveryAndInventoryOnlyRejection(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack boombox = new ItemStack(ModItems.BOOMBOX.get());
        boombox.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(album(10), 901L));
        player.setItemInHand(InteractionHand.MAIN_HAND, boombox);
        final OversizeMediaRecovery.Result result = OversizeMediaRecovery.recoverHeldBoombox(player, boombox);
        final ItemStack remaining = contents(boombox).disc();
        helper.assertTrue(result == OversizeMediaRecovery.Result.RECOVERED && remaining.is(ModItems.ALBUM.get())
                        && ordered(AlbumItem.contents(remaining), 12, 13),
                "Boombox内Albumを安全に縮小して順序を保てない");

        final ItemStack safeAlbum = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(safeAlbum, new AlbumContents(List.of(disc(30))));
        // 6枚分（約1.8 MiB）は単体2 MiB上限には収まりつつ、menu内に置けば初回枠を越える。
        final ItemStack blocker = album(40, 6);
        player.setItemInHand(InteractionHand.MAIN_HAND, safeAlbum);
        player.getInventory().setItem(9, blocker);
        final OversizeMediaRecovery.Result untouched = OversizeMediaRecovery.recoverAlbum(player, safeAlbum);
        helper.assertTrue(untouched == OversizeMediaRecovery.Result.NOT_SOURCE_TOO_LARGE
                        && ordered(AlbumItem.contents(safeAlbum), 30),
                "他inventoryが原因の拒否で安全なAlbumをばらまいた");

        final ItemStack emptyAlbum = new ItemStack(ModItems.ALBUM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, emptyAlbum);
        final boolean emptyAlbumInitialFits = AlbumMenu.fitsInitialMenuSync(player.getInventory(),
                BoomboxSource.held(InteractionHand.MAIN_HAND));
        final OversizeMediaRecovery.Result emptyAlbumResult = OversizeMediaRecovery.recoverAlbum(player, emptyAlbum);
        helper.assertTrue(emptyAlbumResult == OversizeMediaRecovery.Result.NOT_SOURCE_TOO_LARGE,
                "空Albumで他inventoryが原因でも回収不要の案内を返さなかった: initialFits="
                        + emptyAlbumInitialFits + ", result=" + emptyAlbumResult);
        final ItemStack emptyBoombox = new ItemStack(ModItems.BOOMBOX.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, emptyBoombox);
        helper.assertTrue(OversizeMediaRecovery.recoverHeldBoombox(player, emptyBoombox)
                        == OversizeMediaRecovery.Result.NOT_SOURCE_TOO_LARGE,
                "空Boomboxで他inventoryが原因でも回収不要の案内を返さなかった");

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

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    *///?}
    public static void sourceCountChangeRollsBackRecovery(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        for (boolean machine : new boolean[] { false, true }) {
            final ItemStack source = machine ? new ItemStack(ModItems.BOOMBOX.get()) : album(100);
            if (machine) source.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(album(110), 2_209L));
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

    //? if <1.21.2 {
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
        source.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(album(110), 2_209L));
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
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), new CustomTrackData("https://example.invalid/" + index, "track-" + index, "test", 1_000L, "", false));
        // Extra item metadata exercises the synchronization limit without a multi-track disc.
        final net.minecraft.nbt.CompoundTag padding = new net.minecraft.nbt.CompoundTag();
        padding.putByteArray("fixture_padding", new byte[sizeClass == 70 ? 300 * 1024 : 1100 * 1024]);
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

    private static boolean ordered(AlbumContents contents, int... ids) {
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
}
