package com.kuronami.musicdiscmaker.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.kuronami.musicdiscmaker.block.BoomboxBlockEntity;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.AlbumStorageBudget;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.StorageOverflow;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.menu.AlbumMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.register.ModDataComponents;
//?}
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 初回 menu 同期の上限を超えた保存値を、<b>収まる分だけ残して残りをプレイヤーへ返す</b>server側の経路。
 *
 * <p>返す分はまず持ち物へ入り、入らなかった分だけ足元へ落ちる。プレイヤーへ文は出さない
 * (2026-11-02 本人指示「文を出すべきではない というか、文を出さないとわからないのがおかしい」)。
 * 収まる分の選び方は {@link StorageOverflow#split} が持つ。
 */
public final class OversizeMediaRecovery {
    public enum Result {
        RECOVERED,
        RECOVERED_BUT_INVENTORY_TOO_LARGE,
        NOT_SOURCE_TOO_LARGE,
        NOTHING_TO_RECOVER,
        ITEM_TOO_LARGE,
        NO_SAFE_REDUCTION,
        SPAWN_FAILED,
        STALE_SOURCE
    }

    /** GameTestでevent拒否と例外を注入するためのentity追加境界。 */
    @FunctionalInterface public interface EntitySpawner {
        boolean add(ServerLevel level, ItemEntity entity);
    }

    private record AlbumPlan(List<ItemStack> drops, AlbumContents remaining) { }
    private record BoomboxPlan(List<ItemStack> drops, ItemStack remainingMedium) { }

    private OversizeMediaRecovery() { }

    /**
     * 開く前の上限確認に失敗した時の共通の入口。容量超過は文を出さずに保存値を縮め、
     * 縮められた時だけ {@code true} を返す。encode/decode の失敗は縮められないので、
     * 従来どおり文言を出して {@code false}。
     */
    public static boolean openAfterReduction(ServerPlayer player, AlbumStorageBudget.Result sync, ItemStack album) {
        return reduce(player, sync) && isReduced(recoverAlbum(player, album));
    }

    public static boolean openAfterReductionHeldBoombox(ServerPlayer player, AlbumStorageBudget.Result sync,
            ItemStack boombox) {
        return reduce(player, sync) && isReduced(recoverHeldBoombox(player, boombox));
    }

    public static boolean openAfterReductionPlacedBoombox(ServerPlayer player, AlbumStorageBudget.Result sync,
            ServerLevel level, BlockPos pos, BoomboxBlockEntity be) {
        return reduce(player, sync) && isReduced(recoverPlacedBoombox(player, level, pos, be));
    }

    /**
     * 縮められない機器 (Curios 装備の機体) 用。同期形式の失敗だけ文言を出して開かない。
     * 装備中の機体は手に持っていないので、保存値の書き換え経路を持たない。
     */
    public static void refuseOpen(ServerPlayer player, AlbumStorageBudget.Result sync) {
        final String key = sync.failureKey();
        if (key != null) player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(key), true);
    }

    /** false の時点で文言は出し終えている (容量超過は何も出さない)。 */
    private static boolean reduce(ServerPlayer player, AlbumStorageBudget.Result sync) {
        final String key = sync.failureKey();
        if (key != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(key), true);
            return false;
        }
        return true;
    }

    private static boolean isReduced(Result result) {
        // 所持品の大きさは初回packetに入らないため、ここでは開けるかだけを見る。
        return result == Result.RECOVERED || result == Result.RECOVERED_BUT_INVENTORY_TOO_LARGE;
    }

    public static Result recoverAlbum(ServerPlayer player, ItemStack album) {
        return recoverAlbum(player, album, ServerLevel::addFreshEntity);
    }

    public static Result recoverAlbum(ServerPlayer player, ItemStack album, EntitySpawner spawner) {
        final InteractionHand hand = heldHand(player, album);
        if (hand == null) return Result.STALE_SOURCE;
        closeMediaMenu(player);
        final RegistryAccess registries = registryAccess((ServerLevel) player.level());
        // 初回同期はAlbumの内部枠だけを測る。空Albumには返すべき媒体がない。
        if (AlbumItem.contents(album).isEmpty()) return Result.NOTHING_TO_RECOVER;
        if (albumCleanSyncFits(album, registries)) return Result.NOT_SOURCE_TOO_LARGE;
        final AlbumPlan plan = albumPlan(album, registries);
        if (plan == null) return noAlbumPlanResult(AlbumItem.contents(album), registries);
        final AlbumContents before = AlbumItem.contents(album);
        final ItemStack original = album.copy();
        final Result committed = spawnAndCommit(player, plan.drops(), spawner, () -> {
            if (heldHand(player, album) != hand || !ItemStack.matches(album, original)) return Result.STALE_SOURCE;
            try {
                AlbumItem.setContents(album, plan.remaining());
                return Result.RECOVERED;
            } catch (RuntimeException failure) {
                AlbumItem.setContents(album, before);
                return Result.SPAWN_FAILED;
            }
        });
        if (committed != Result.RECOVERED) return committed;
        return AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(hand))
                ? Result.RECOVERED : Result.RECOVERED_BUT_INVENTORY_TOO_LARGE;
    }

    public static Result recoverHeldBoombox(ServerPlayer player, ItemStack boombox) {
        return recoverHeldBoombox(player, boombox, ServerLevel::addFreshEntity);
    }

    public static Result recoverHeldBoombox(ServerPlayer player, ItemStack boombox, EntitySpawner spawner) {
        final InteractionHand hand = heldHand(player, boombox);
        if (hand == null) return Result.STALE_SOURCE;
        closeMediaMenu(player);
        final RegistryAccess registries = registryAccess((ServerLevel) player.level());
        final BoomboxContents before = contentsOf(boombox);
        final ItemStack original = boombox.copy();
        // 初回同期はBoomboxの媒体枠だけを測る。空の媒体枠には返す対象がない。
        if (before.disc().isEmpty()) return Result.NOTHING_TO_RECOVER;
        if (boomboxCleanSyncFits(boombox, registries)) return Result.NOT_SOURCE_TOO_LARGE;
        final BoomboxPlan plan = boomboxPlan(boombox, before, registries);
        if (plan == null) return noMediumPlanResult(before.disc(), registries);
        final Result committed = spawnAndCommit(player, plan.drops(), spawner, () -> {
            if (heldHand(player, boombox) != hand || !ItemStack.matches(boombox, original)) return Result.STALE_SOURCE;
            try {
                return BoomboxPlayback.replaceMediaCarried(player, boombox, plan.remainingMedium(),
                        before.cursor().generation()) ? Result.RECOVERED : Result.STALE_SOURCE;
            } catch (RuntimeException failure) {
                storeContents(boombox, before);
                return Result.SPAWN_FAILED;
            }
        });
        if (committed != Result.RECOVERED) return committed;
        return BoomboxMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(hand))
                ? Result.RECOVERED : Result.RECOVERED_BUT_INVENTORY_TOO_LARGE;
    }

    public static Result recoverPlacedBoombox(ServerPlayer player, ServerLevel level, BlockPos pos,
            BoomboxBlockEntity be) {
        return recoverPlacedBoombox(player, level, pos, be, ServerLevel::addFreshEntity);
    }

    public static Result recoverPlacedBoombox(ServerPlayer player, ServerLevel level, BlockPos pos,
            BoomboxBlockEntity be, EntitySpawner spawner) {
        if (player.level() != level || level.getBlockEntity(pos) != be || player.distanceToSqr(Vec3.atCenterOf(pos)) > 64.0) return Result.STALE_SOURCE;
        closeMediaMenu(player);
        final RegistryAccess registries = registryAccess(level);
        final ItemStack boombox = be.getStored();
        final BoomboxContents before = contentsOf(boombox);
        final ItemStack original = boombox.copy();
        if (before.disc().isEmpty()) return Result.NOTHING_TO_RECOVER;
        if (boomboxCleanSyncFits(boombox, registries)) return Result.NOT_SOURCE_TOO_LARGE;
        final BoomboxPlan plan = boomboxPlan(boombox, before, registries);
        if (plan == null) return noMediumPlanResult(before.disc(), registries);
        final Result committed = spawnAndCommit(player, plan.drops(), spawner, () -> {
            if (player.level() != level || level.getBlockEntity(pos) != be || be.getStored() != boombox
                    || player.distanceToSqr(Vec3.atCenterOf(pos)) > 64.0
                    || !ItemStack.matches(boombox, original)) return Result.STALE_SOURCE;
            try {
                return BoomboxPlayback.replaceMediaPlaced(level, pos, be, plan.remainingMedium(),
                        before.cursor().generation()) ? Result.RECOVERED : Result.STALE_SOURCE;
            } catch (RuntimeException failure) {
                // setChanged can fail after the contents write. Restore the original
                // component before spawnAndCommit removes the extracted item entities.
                storeContents(boombox, before);
                return Result.SPAWN_FAILED;
            }
        });
        if (committed != Result.RECOVERED) return committed;
        return BoomboxMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.placed(pos))
                ? Result.RECOVERED : Result.RECOVERED_BUT_INVENTORY_TOO_LARGE;
    }

    /** Album単体を、保存順の先頭から最小件数だけ外した最小構成へ縮める。 */
    private static AlbumPlan albumPlan(ItemStack album, RegistryAccess registries) {
        final List<ItemStack> all = AlbumItem.contents(album).discs();
        final StorageOverflow.Split split = StorageOverflow.split(all.size(),
                kept -> albumCleanSyncFits(withRemaining(album, all, kept), registries),
                index -> fits(all.get(index).copy(), registries));
        if (split == null) return null;
        return new AlbumPlan(dropsFrom(all, split), remaining(all, split.kept()));
    }

    /** Boombox内Albumも、外側Boomboxを含む最小同期構成まで縮める。 */
    private static BoomboxPlan boomboxPlan(ItemStack boombox, BoomboxContents before, RegistryAccess registries) {
        final ItemStack medium = before.disc();
        if (medium.isEmpty()) return null;
        if (!AlbumItem.isAlbum(medium)) {
            if (!fits(medium, registries)) return null;
            final ItemStack candidate = boombox.copy();
            storeContents(candidate, before.withDisc(ItemStack.EMPTY));
            return boomboxCleanSyncFits(candidate, registries)
                    ? new BoomboxPlan(List.of(medium.copy()), ItemStack.EMPTY) : null;
        }
        final List<ItemStack> all = AlbumItem.contents(medium).discs();
        final StorageOverflow.Split split = StorageOverflow.split(all.size(),
                kept -> boomboxCleanSyncFits(withMedium(boombox, before.withDisc(withRemaining(medium, all, kept))),
                        registries),
                index -> fits(all.get(index).copy(), registries));
        if (split == null) return null;
        return new BoomboxPlan(dropsFrom(all, split), withRemaining(medium, all, split.kept()));
    }

    /** 保存順の先頭から外へ出す分を、元の順序のまま取り出す。 */
    private static List<ItemStack> dropsFrom(List<ItemStack> all, StorageOverflow.Split split) {
        final List<ItemStack> drops = new ArrayList<>(split.returned());
        for (int i = 0; i < split.returned(); i++) drops.add(all.get(i).copy());
        return List.copyOf(drops);
    }

    private static ItemStack withRemaining(ItemStack machine, List<ItemStack> all, int kept) {
        final ItemStack candidate = machine.copy();
        AlbumItem.setContents(candidate, remaining(all, kept));
        return candidate;
    }

    private static ItemStack withMedium(ItemStack boombox, BoomboxContents contents) {
        final ItemStack candidate = boombox.copy();
        storeContents(candidate, contents);
        return candidate;
    }

    private static AlbumContents remaining(List<ItemStack> all, int kept) {
        return kept == 0 ? AlbumContents.EMPTY : new AlbumContents(all.subList(all.size() - kept, all.size()));
    }

    private static boolean albumCleanSyncFits(ItemStack album, RegistryAccess registries) {
        final int capacity = AlbumItem.capacity(album);
        final List<ItemStack> minimal = new ArrayList<>(capacity);
        final List<ItemStack> discs = AlbumItem.contents(album).discs();
        for (int i = 0; i < capacity; i++) minimal.add(i < discs.size() ? discs.get(i) : ItemStack.EMPTY);
        return fitsInitial(minimal, registries);
    }

    private static boolean boomboxCleanSyncFits(ItemStack boombox, RegistryAccess registries) {
        return fitsInitial(List.of(contentsOf(boombox).disc().copy()), registries);
    }

    private static Result noAlbumPlanResult(AlbumContents contents, RegistryAccess registries) {
        return !contents.isEmpty() && !fits(contents.discAt(0), registries) ? Result.ITEM_TOO_LARGE : Result.NO_SAFE_REDUCTION;
    }

    private static Result noMediumPlanResult(ItemStack medium, RegistryAccess registries) {
        return !medium.isEmpty() && !fits(medium, registries) ? Result.ITEM_TOO_LARGE : Result.NO_SAFE_REDUCTION;
    }

    /**
     * 返す分を持ち物へ入れてから、入らなかった分だけを足元のentityとして残す。
     * entityのspawnに失敗した時は、まだどの保存値も変えていないので何も残さない。
     */
    private static Result spawnAndCommit(ServerPlayer player, List<ItemStack> drops, EntitySpawner spawner,
            Supplier<Result> commit) {
        final ServerLevel level = (ServerLevel) player.level();
        final List<ItemEntity> spawned = new ArrayList<>();
        try {
            for (final ItemStack stack : drops) {
                final ItemEntity entity = new ItemEntity(level, player.getX(), player.getY() + 0.25, player.getZ(), stack.copy());
                // addFreshEntityがfalseを返す実装でも、eventが既にentityをworldへ加えた場合をrollback対象にする。
                spawned.add(entity);
                if (!spawner.add(level, entity)) {
                    discardAll(spawned);
                    return Result.SPAWN_FAILED;
                }
            }
            final Result result = commit.get();
            if (result != Result.RECOVERED) {
                discardAll(spawned);
                return result;
            }
            // 保存値の書き換えが確定してから、返す分を持ち物へ移す。入らない分だけが地面に残る。
            collect(player, spawned);
            return result;
        } catch (RuntimeException failure) {
            discardAll(spawned);
            return Result.SPAWN_FAILED;
        }
    }

    /** 足元へ出した分のうち、持ち物に入る分だけを移してentityを片付ける。 */
    private static void collect(ServerPlayer player, List<ItemEntity> spawned) {
        final Inventory inventory = player.getInventory();
        for (final ItemEntity entity : spawned) {
            try {
                final ItemStack remaining = entity.getItem().copy();
                if (remaining.isEmpty()) continue;
                if (inventory.add(remaining)) {
                    entity.discard();
                } else if (remaining.getCount() != entity.getItem().getCount()) {
                    entity.setItem(remaining);
                }
            } catch (RuntimeException ignored) {
                // 移せない分は足元の entity のまま残す。ここで例外が出ても媒体は消さない。
            }
        }
    }

    private static void discardAll(List<ItemEntity> entities) { for (final ItemEntity entity : entities) entity.discard(); }

    private static InteractionHand heldHand(ServerPlayer player, ItemStack source) {
        if (player.getItemInHand(InteractionHand.MAIN_HAND) == source) return InteractionHand.MAIN_HAND;
        return player.getItemInHand(InteractionHand.OFF_HAND) == source ? InteractionHand.OFF_HAND : null;
    }

    private static void closeMediaMenu(ServerPlayer player) {
        if (player.containerMenu instanceof AlbumMenu || player.containerMenu instanceof BoomboxMenu) player.closeContainer();
    }

    /** 初回packetと同じ形 (内部枠 + 空の所持品) で測る。menu 側の判定と1 byte も違えない。 */
    private static boolean fitsInitial(List<ItemStack> internal, RegistryAccess registries) {
        final List<ItemStack> slots = new ArrayList<>(internal.size() + Inventory.INVENTORY_SIZE);
        slots.addAll(internal);
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) slots.add(ItemStack.EMPTY);
        //? if >=1.21 {
        return AlbumStorageBudget.fitsInitialMenuSync(slots, ItemStack.EMPTY, registries);
        //?} else {
        /*return AlbumStorageBudget.fitsInitialMenuSync(slots, ItemStack.EMPTY);
        *///?}
    }

    private static RegistryAccess registryAccess(ServerLevel level) {
        //? if >=1.21 {
        return level.registryAccess();
        //?} else {
        /*return null;
        *///?}
    }

    private static boolean fits(ItemStack stack, RegistryAccess registries) {
        //? if >=1.21 {
        return AlbumStorageBudget.fits(stack, registries);
        //?} else {
        /*return AlbumStorageBudget.fits(stack);
        *///?}
    }

    private static BoomboxContents contentsOf(ItemStack stack) {
        //? if >=1.21 {
        return stack.getOrDefault(ModDataComponents.BOOMBOX_CONTENTS.get(), BoomboxContents.EMPTY);
        //?} else {
        /*return BoomboxContents.of(stack);
        *///?}
    }

    private static void storeContents(ItemStack stack, BoomboxContents contents) {
        //? if >=1.21 {
        stack.set(ModDataComponents.BOOMBOX_CONTENTS.get(), contents);
        //?} else {
        /*BoomboxContents.store(stack, contents);
        *///?}
    }
}
