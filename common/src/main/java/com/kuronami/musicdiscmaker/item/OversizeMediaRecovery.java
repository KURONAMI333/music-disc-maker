package com.kuronami.musicdiscmaker.item;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.kuronami.musicdiscmaker.block.BoomboxBlockEntity;
import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.AlbumStorageBudget;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.menu.AlbumMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.register.ModDataComponents;
//?}
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** 初回menu同期を安全に送れない既存媒体を、個別同期可能なdropへ回収するserver側の経路。 */
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

    public static Result recoverAlbum(ServerPlayer player, ItemStack album) {
        return recoverAlbum(player, album, ServerLevel::addFreshEntity);
    }

    public static void notify(ServerPlayer player, Result result) {
        final String suffix = switch (result) {
            case RECOVERED -> "complete";
            case RECOVERED_BUT_INVENTORY_TOO_LARGE -> "inventory_too_large";
            case NOT_SOURCE_TOO_LARGE -> "inventory_only";
            case NOTHING_TO_RECOVER -> "nothing";
            case ITEM_TOO_LARGE -> "item_too_large";
            case NO_SAFE_REDUCTION -> "no_safe_path";
            case SPAWN_FAILED -> "retry";
            case STALE_SOURCE -> "stale";
        };
        player.sendSystemMessage(Component.translatable("message.music_disc_maker.recovery." + suffix), true);
    }

    public static Result recoverAlbum(ServerPlayer player, ItemStack album, EntitySpawner spawner) {
        final InteractionHand hand = heldHand(player, album);
        if (hand == null) return Result.STALE_SOURCE;
        closeMediaMenu(player);
        final RegistryAccess registries = registryAccess((ServerLevel) player.level());
        // 空Album自体は初回menu同期の原因になれない。ここで先に NOTHING を返すと、
        // 他の所持品が大きくて開けない場合にも取るべき行動を案内できなくなる。
        if (AlbumItem.contents(album).isEmpty()) {
            return AlbumMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(hand))
                    ? Result.NOTHING_TO_RECOVER : Result.NOT_SOURCE_TOO_LARGE;
        }
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
        // 空Boomboxも同様に媒体側ではなく、初回menuへ含まれる他の所持品だけが
        // 拒否理由になり得る。sneak-useではその理由を優先して返す。
        if (before.disc().isEmpty()) {
            return BoomboxMenu.fitsInitialMenuSync(player.getInventory(), BoomboxSource.held(hand))
                    ? Result.NOTHING_TO_RECOVER : Result.NOT_SOURCE_TOO_LARGE;
        }
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

    /** Album単体を安全なopen最小構成まで縮める先頭prefixを探す。 */
    private static AlbumPlan albumPlan(ItemStack album, RegistryAccess registries) {
        final List<ItemStack> all = AlbumItem.contents(album).discs();
        final List<ItemStack> drops = new ArrayList<>();
        for (int count = 1; count <= all.size(); count++) {
            final ItemStack extracted = all.get(count - 1).copy();
            if (!fits(extracted, registries)) return null;
            drops.add(extracted);
            final AlbumContents remaining = remaining(all, count);
            final ItemStack candidate = album.copy();
            AlbumItem.setContents(candidate, remaining);
            if (albumCleanSyncFits(candidate, registries)) return new AlbumPlan(List.copyOf(drops), remaining);
        }
        return null;
    }

    /** Boombox内Albumも、外側Boomboxを含む最小同期構成まで先頭prefixを回収する。 */
    private static BoomboxPlan boomboxPlan(ItemStack boombox, BoomboxContents before, RegistryAccess registries) {
        final ItemStack medium = before.disc();
        if (medium.isEmpty()) return null;
        if (!medium.is(ModItems.ALBUM.get())) {
            if (!fits(medium, registries)) return null;
            final ItemStack candidate = boombox.copy();
            storeContents(candidate, before.withDisc(ItemStack.EMPTY));
            return boomboxCleanSyncFits(candidate, registries)
                    ? new BoomboxPlan(List.of(medium.copy()), ItemStack.EMPTY) : null;
        }
        final List<ItemStack> all = AlbumItem.contents(medium).discs();
        final List<ItemStack> drops = new ArrayList<>();
        for (int count = 1; count <= all.size(); count++) {
            final ItemStack extracted = all.get(count - 1).copy();
            if (!fits(extracted, registries)) return null;
            drops.add(extracted);
            final ItemStack reducedAlbum = medium.copy();
            AlbumItem.setContents(reducedAlbum, remaining(all, count));
            final ItemStack candidate = boombox.copy();
            storeContents(candidate, before.withDisc(reducedAlbum));
            if (boomboxCleanSyncFits(candidate, registries)) return new BoomboxPlan(List.copyOf(drops), reducedAlbum);
        }
        return null;
    }

    private static AlbumContents remaining(List<ItemStack> all, int count) {
        return count == all.size() ? AlbumContents.EMPTY : new AlbumContents(all.subList(count, all.size()));
    }

    private static boolean albumCleanSyncFits(ItemStack album, RegistryAccess registries) {
        final List<ItemStack> minimal = new ArrayList<>(AlbumItem.GUI_CAPACITY + 1);
        final List<ItemStack> discs = AlbumItem.contents(album).discs();
        for (int i = 0; i < AlbumItem.GUI_CAPACITY; i++) minimal.add(i < discs.size() ? discs.get(i) : ItemStack.EMPTY);
        minimal.add(album.copy());
        return fitsInitial(minimal, registries);
    }

    private static boolean boomboxCleanSyncFits(ItemStack boombox, RegistryAccess registries) {
        return fitsInitial(List.of(contentsOf(boombox).disc().copy(), boombox.copy()), registries);
    }

    private static Result noAlbumPlanResult(AlbumContents contents, RegistryAccess registries) {
        return !contents.isEmpty() && !fits(contents.discAt(0), registries) ? Result.ITEM_TOO_LARGE : Result.NO_SAFE_REDUCTION;
    }

    private static Result noMediumPlanResult(ItemStack medium, RegistryAccess registries) {
        return !medium.isEmpty() && !fits(medium, registries) ? Result.ITEM_TOO_LARGE : Result.NO_SAFE_REDUCTION;
    }

    /** spawn済みentityもcommit失敗時にdiscardし、原本変更とdropを同じtransactionにする。 */
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
            if (result != Result.RECOVERED) discardAll(spawned);
            return result;
        } catch (RuntimeException failure) {
            discardAll(spawned);
            return Result.SPAWN_FAILED;
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

    private static boolean fitsInitial(List<ItemStack> stacks, RegistryAccess registries) {
        //? if >=1.21 {
        return AlbumStorageBudget.fitsInitialMenuSync(stacks, ItemStack.EMPTY, registries);
        //?} else {
        /*return AlbumStorageBudget.fitsInitialMenuSync(stacks, ItemStack.EMPTY);
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
