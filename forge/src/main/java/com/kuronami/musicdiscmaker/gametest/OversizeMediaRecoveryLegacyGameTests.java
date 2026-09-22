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
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.item.OversizeMediaRecovery;
import com.kuronami.musicdiscmaker.menu.AlbumMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

/** Forge 1.20.1 NBTの過大媒体回収。 */
@net.minecraftforge.gametest.GameTestHolder("music_disc_maker")
@net.minecraftforge.gametest.PrefixGameTestTemplate(false)
public final class OversizeMediaRecoveryLegacyGameTests {
    private OversizeMediaRecoveryLegacyGameTests() { }

    @GameTest(template = "empty8x3x8", timeoutTicks = 200)
    public static void albumRecoveryPreservesOrderAndSpawnFailurePreservesSource(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack album = oversizedAlbum(0);
        player.setItemInHand(InteractionHand.MAIN_HAND, album);
        helper.assertTrue(!AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND)),
                "legacy過大Album fixtureが初回同期拒否域に入っていない");
        final int[] attempts = { 0 };
        final ItemEntity[] firstSpawned = { null };
        final OversizeMediaRecovery.Result failed = OversizeMediaRecovery.recoverAlbum(player, album, (level, entity) -> {
            if (++attempts[0] == 1) {
                firstSpawned[0] = entity;
                return level.addFreshEntity(entity);
            }
            return false;
        });
        helper.assertTrue(failed == OversizeMediaRecovery.Result.SPAWN_FAILED && firstSpawned[0] != null
                        && firstSpawned[0].isRemoved() && ordered(AlbumItem.contents(album), 0, 1, 2, 3),
                "legacy実spawn後の拒否でdrop rollbackまたはAlbum保存値の保全に失敗した");
        final List<ItemStack> dropped = new ArrayList<>();
        final OversizeMediaRecovery.Result recovered = OversizeMediaRecovery.recoverAlbum(player, album, (level, entity) -> {
            dropped.add(entity.getItem().copy());
            return level.addFreshEntity(entity);
        });
        helper.assertTrue(recovered == OversizeMediaRecovery.Result.RECOVERED && orderedStacks(dropped, 0, 1)
                        && ordered(AlbumItem.contents(album), 2, 3),
                "legacy Album回収でdrop/残り順序またはmetadataを保てない");
        helper.assertTrue(OversizeMediaRecovery.recoverAlbum(player, album) == OversizeMediaRecovery.Result.NOT_SOURCE_TOO_LARGE
                        && dropped.size() == 2, "legacy回収済みAlbumの再試行で重複dropが発生した");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 200)
    public static void recoveryStaleCommitDiscardsSpawnedEntities(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack boombox = new ItemStack(ModItems.BOOMBOX.get());
        BoomboxContents.store(boombox, new BoomboxContents(oversizedAlbum(60), 2_103L));
        final BoomboxContents before = BoomboxContents.of(boombox);
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
                        && spawned.stream().allMatch(ItemEntity::isRemoved) && BoomboxContents.of(boombox).equals(before),
                "legacy spawn後のBoombox差替えでdrop rollbackまたは元媒体保全に失敗した");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 200)
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
                "legacy同じAlbum参照の保存内容差替えでdrop rollbackまたは差替え内容保全に失敗した");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 200)
    public static void oversizedDiscAndPlacedBoomboxAreUntouched(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack oversizedAlbum = new ItemStack(ModItems.ALBUM.get());
        // 初回menu上限も、単体drop上限も超える1枚。前者未満では回収自体が不要になる。
        final ItemStack oversizedDisc = discWithPadding(80, 1600 * 1024);
        AlbumItem.setContents(oversizedAlbum, new AlbumContents(List.of(oversizedDisc)));
        final AlbumContents albumBefore = AlbumItem.contents(oversizedAlbum);
        player.setItemInHand(InteractionHand.MAIN_HAND, oversizedAlbum);
        helper.assertTrue(OversizeMediaRecovery.recoverAlbum(player, oversizedAlbum)
                        == OversizeMediaRecovery.Result.ITEM_TOO_LARGE && AlbumItem.contents(oversizedAlbum).equals(albumBefore),
                "legacy 1枚で上限超過した盤を回収して元媒体を変えた");

        final BlockPos relative = new BlockPos(4, 1, 4);
        final BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, ModBlocks.BOOMBOX.get());
        final BoomboxBlockEntity be = (BoomboxBlockEntity) helper.getLevel().getBlockEntity(pos);
        final ItemStack stored = new ItemStack(ModItems.BOOMBOX.get());
        BoomboxContents.store(stored, new BoomboxContents(oversizedAlbum(90), 3_007L));
        final BoomboxContents storedBefore = BoomboxContents.of(stored);
        be.setStored(stored);
        player.setPos(pos.getX() + 20.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        helper.assertTrue(OversizeMediaRecovery.recoverPlacedBoombox(player, helper.getLevel(), pos, be)
                        == OversizeMediaRecovery.Result.STALE_SOURCE && BoomboxContents.of(stored).equals(storedBefore),
                "legacy遠距離の設置Boombox回収を受け入れた");
        helper.setBlock(relative, Blocks.AIR);
        final BoomboxContents afterRemoval = BoomboxContents.of(stored);
        final OversizeMediaRecovery.Result replaced = OversizeMediaRecovery.recoverPlacedBoombox(player, helper.getLevel(), pos, be);
        helper.assertTrue(replaced == OversizeMediaRecovery.Result.STALE_SOURCE,
                "legacy置換済みBlockEntityのBoombox回収を受け入れた");
        helper.assertTrue(BoomboxContents.of(stored).equals(afterRemoval),
                "legacy置換済みBlockEntityの拒否処理が撤去後の媒体を変えた");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 200)
    public static void boomboxAlbumRecoveryAndInventoryOnlyRejection(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final ItemStack boombox = new ItemStack(ModItems.BOOMBOX.get());
        BoomboxContents.store(boombox, new BoomboxContents(oversizedAlbum(10), 901L));
        player.setItemInHand(InteractionHand.MAIN_HAND, boombox);
        final OversizeMediaRecovery.Result result = OversizeMediaRecovery.recoverHeldBoombox(player, boombox);
        final ItemStack remaining = BoomboxContents.of(boombox).disc();
        helper.assertTrue(result == OversizeMediaRecovery.Result.RECOVERED && remaining.is(ModItems.ALBUM.get())
                        && ordered(AlbumItem.contents(remaining), 12, 13), "legacy Boombox内Albumを安全に縮小できない");
        final ItemStack safeAlbum = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(safeAlbum, new AlbumContents(List.of(disc(30))));
        player.setItemInHand(InteractionHand.MAIN_HAND, safeAlbum);
        final ItemStack blocker = oversizedAlbum(40);
        player.getInventory().setItem(9, blocker);
        helper.assertTrue(OversizeMediaRecovery.recoverAlbum(player, safeAlbum)
                        == OversizeMediaRecovery.Result.NOT_SOURCE_TOO_LARGE && ordered(AlbumItem.contents(safeAlbum), 30),
                "legacy他inventory起因で安全なAlbumをばらまいた");

        final ItemStack emptyAlbum = new ItemStack(ModItems.ALBUM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, emptyAlbum);
        helper.assertTrue(OversizeMediaRecovery.recoverAlbum(player, emptyAlbum)
                        == OversizeMediaRecovery.Result.NOTHING_TO_RECOVER,
                "legacy空Albumで回収不要の結果を返さなかった");
        final ItemStack emptyBoombox = new ItemStack(ModItems.BOOMBOX.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, emptyBoombox);
        helper.assertTrue(OversizeMediaRecovery.recoverHeldBoombox(player, emptyBoombox)
                        == OversizeMediaRecovery.Result.NOTHING_TO_RECOVER,
                "legacy空Boomboxで回収不要の結果を返さなかった");

        // このmenuに登録されないoffhandを大きくしても、媒体の初回同期を拒否してはならない。
        player.getInventory().setItem(9, ItemStack.EMPTY);
        player.getInventory().setItem(40, blocker);
        player.setItemInHand(InteractionHand.MAIN_HAND, safeAlbum);
        helper.assertFalse(legacyInitialSlotsFit(player.getInventory(), safeAlbum, AlbumItem.GUI_CAPACITY),
                "legacy旧inventory全体集合がoffhandを同期対象としていないため回帰対照になっていない");
        helper.assertTrue(AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND)),
                "legacy menu外offhandの大きいitemで安全なAlbumの初回同期を拒否した");
        player.setItemInHand(InteractionHand.MAIN_HAND, emptyBoombox);
        helper.assertFalse(legacyInitialSlotsFit(player.getInventory(), emptyBoombox, 1),
                "legacy旧Boombox集合がoffhandを同期対象としていないため回帰対照になっていない");
        helper.assertTrue(BoomboxMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(InteractionHand.MAIN_HAND)),
                "legacy menu外offhandの大きいitemで空Boomboxの初回同期を拒否した");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 400)
    public static void sourceCountChangeRollsBackRecovery(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        for (boolean machine : new boolean[] { false, true }) {
            final ItemStack source = machine ? new ItemStack(ModItems.BOOMBOX.get()) : oversizedAlbum(100);
            if (machine) BoomboxContents.store(source, new BoomboxContents(oversizedAlbum(110), 2_209L));
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

    @GameTest(template = "empty8x3x8", timeoutTicks = 400)
    public static void placedCommitExceptionRestoresMedia(GameTestHelper helper) {
        final FakePlayer player = player(helper);
        final BlockPos relative = new BlockPos(4, 1, 4);
        final BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, ModBlocks.BOOMBOX.get());
        final ThrowingBoombox be = new ThrowingBoombox(pos, helper.getLevel().getBlockState(pos));
        helper.getLevel().removeBlockEntity(pos);
        helper.getLevel().setBlockEntity(be);
        final ItemStack source = new ItemStack(ModItems.BOOMBOX.get());
        BoomboxContents.store(source, new BoomboxContents(oversizedAlbum(110), 2_209L));
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
        final BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        player.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        return player;
    }

    private static ItemStack album(int first) { return album(first, 4); }
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

    /** 2枚を取り出すまで初回menu枠に収まらない既存媒体のfixture。各drop自体は1 MiB以内。 */
    private static ItemStack oversizedAlbum(int first) {
        final List<ItemStack> discs = new ArrayList<>();
        for (int i = 0; i < 4; i++) discs.add(discWithPadding(first + i, 520 * 1024));
        final ItemStack album = new ItemStack(ModItems.ALBUM.get());
        AlbumItem.setContents(album, new AlbumContents(discs));
        return album;
    }

    private static ItemStack discWithPadding(int index, int paddingBytes) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        CustomMusicDiscItem.setTrack(disc, new CustomTrackData("https://example.invalid/" + index, "track-" + index, "test", 1_000L, "", false));
        // Extra item metadata exercises the synchronization limit without a multi-track disc.
        disc.getOrCreateTag().putByteArray("fixture_padding", new byte[paddingBytes]);
        return disc;
    }

    /** 3.0.1までのgetContainerSize走査が構成していた初回slot集合。回帰用のみ。 */
    private static boolean legacyInitialSlotsFit(net.minecraft.world.entity.player.Inventory inventory,
            ItemStack heldSource, int internalSlots) {
        final List<ItemStack> slots = new ArrayList<>(internalSlots + inventory.getContainerSize());
        if (heldSource.is(ModItems.ALBUM.get())) {
            final List<ItemStack> saved = AlbumItem.contents(heldSource).discs();
            for (int i = 0; i < internalSlots; i++) slots.add(i < saved.size() ? saved.get(i).copy() : ItemStack.EMPTY);
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
        return AlbumStorageBudget.fitsInitialMenuSync(slots, ItemStack.EMPTY);
    }

    private static boolean ordered(AlbumContents contents, int... ids) {
        if (contents.size() != ids.length) return false;
        for (int i = 0; i < ids.length; i++) {
            final CustomTrackData track = CustomMusicDiscItem.getTrack(contents.discAt(i));
            if (track == null || !track.title().equals("track-" + ids[i])) return false;
        }
        return true;
    }

    private static boolean orderedStacks(List<ItemStack> stacks, int... ids) {
        return ordered(new AlbumContents(stacks), ids);
    }
}
