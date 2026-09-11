package com.kuronami.musicdiscmaker.menu;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.block.BoomboxBlockEntity;
import com.kuronami.musicdiscmaker.component.AlbumStorageBudget;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.BoomboxPlaybackState;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.MediaSequence;
import com.kuronami.musicdiscmaker.component.MediaSequenceResolver;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.network.ModNetwork;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.register.ModDataComponents;
//?}
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModMenus;

//? if >=1.21 {
import net.minecraft.network.RegistryFriendlyByteBuf;
//?} else {
/*import net.minecraft.network.FriendlyByteBuf;
*///?}
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
//? if >=26.1 {
import net.minecraft.world.inventory.ContainerInput;
//?} else {
/*import net.minecraft.world.inventory.ClickType;
*///?}
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** ブームボックス内の媒体1枠を、元の機体を動かさずに編集する menu。 */
public class BoomboxMenu extends AbstractContainerMenu {
    public static final int MEDIA_SLOT_X = 10;
    public static final int MEDIA_SLOT_Y = 18;

    private static final int MEDIA_SLOT = 0;
    private static final int PLAYER_SLOTS_START = 1;

    private final BoomboxSource source;
    private final Player owner;
    private final boolean authoritative;
    private final ItemStack machine;
    @Nullable private final BoomboxBlockEntity placedEntity;
    private final SimpleContainer contents;
    private final int sourceInventorySlot;
    private int sourceMenuSlot = -1;
    private boolean loading;
    private boolean storageRejected;
    private ItemStack resolvedMedium = ItemStack.EMPTY;
    @Nullable private MediaSequenceResolver.ResolvedSequence resolvedSequence;
    private BoomboxPlaybackState playbackState = new BoomboxPlaybackState(PlaybackCursor.initial(), 0L,
            false, false, 100);
    @Nullable private BoomboxPlaybackState lastSentPlaybackState;
    private long playbackStateReceivedMillis;
    private boolean playbackStateReady;
    private long lastStateSentMillis;

    //? if >=1.21 {
    public BoomboxMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory, BoomboxSource.STREAM_CODEC.decode(buf));
    }
    //?} else {
    /*public BoomboxMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        this(containerId, playerInventory, BoomboxSource.read(buf));
    }
    *///?}

    public BoomboxMenu(int containerId, Inventory playerInventory, BoomboxSource source) {
        super(ModMenus.BOOMBOX.get(), containerId);
        this.source = source;
        this.owner = playerInventory.player;
        this.authoritative = !owner.level().isClientSide();
        this.placedEntity = source.isPlaced() && owner.level().getBlockEntity(source.pos()) instanceof BoomboxBlockEntity be
                ? be : null;
        this.machine = resolveMachine(owner, source, placedEntity);
        this.sourceInventorySlot = !source.isPlaced() && source.heldHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                ? findInventorySlot(playerInventory, machine) : -1;
        this.contents = new SimpleContainer(1) {
            @Override public void setChanged() { super.setChanged(); save(); }
        };
        loading = true;
        if (isBoombox(machine)) contents.setItem(MEDIA_SLOT, contentsOf(machine).disc().copy());
        loading = false;
        addSlot(new MediaSlot(contents, MEDIA_SLOT, MEDIA_SLOT_X, MEDIA_SLOT_Y));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 142 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(col == sourceInventorySlot
                ? new LockedSourceSlot(playerInventory, col, 8 + col * 18, 200)
                : new Slot(playerInventory, col, 8 + col * 18, 200));
        for (int i = PLAYER_SLOTS_START; i < slots.size(); i++) {
            final Slot slot = slots.get(i);
            if (slot.container == playerInventory && slot.getContainerSlot() == sourceInventorySlot) {
                sourceMenuSlot = i;
                break;
            }
        }
        if (authoritative && isOriginalMachine()) applyPlaybackState(BoomboxPlayback.stateOf(machine));
    }

    /** 初回open packetが運ぶ内部枠・プレイヤー枠・元機体を実codecで検査する。 */
    public static boolean fitsInitialMenuSync(Inventory inventory, BoomboxSource source) {
        final Player player = inventory.player;
        final BoomboxBlockEntity placed = source.isPlaced()
                && player.level().getBlockEntity(source.pos()) instanceof BoomboxBlockEntity be ? be : null;
        final ItemStack machine = resolveMachine(player, source, placed);
        if (!isBoombox(machine)) return false;
        final List<ItemStack> initial = new ArrayList<>(38);
        initial.add(contentsOf(machine).disc().copy());
        boolean machineInInventory = false;
        for (int i = 9; i < inventory.getContainerSize(); i++) {
            final ItemStack stack = inventory.getItem(i);
            initial.add(stack.copy());
            machineInInventory |= stack == machine;
        }
        for (int i = 0; i < 9; i++) {
            final ItemStack stack = inventory.getItem(i);
            initial.add(stack.copy());
            machineInInventory |= stack == machine;
        }
        if (!machineInInventory) initial.add(machine.copy());
        //? if >=1.21 {
        return AlbumStorageBudget.fitsInitialMenuSync(initial, ItemStack.EMPTY, player.level().registryAccess());
        //?} else {
        /*return AlbumStorageBudget.fitsInitialMenuSync(initial, ItemStack.EMPTY);
        *///?}
    }

    public BoomboxSource source() { return source; }

    /** serverでは元機体の同一参照まで確認して返す。 */
    public ItemStack currentStack() { return isOriginalMachine() ? machine : ItemStack.EMPTY; }

    public void applyPlaybackState(BoomboxPlaybackState state) {
        if (state == null) return;
        playbackState = state;
        playbackStateReady = true;
        playbackStateReceivedMillis = System.currentTimeMillis();
    }

    public boolean hasPlaybackState() {
        return playbackStateReady;
    }

    /** clientではPLAYINGだけ受信時刻から補間し、停止・pauseは固定値を返す。 */
    public BoomboxPlaybackState playbackState() {
        if (!authoritative && playbackState.cursor().state() == PlaybackCursor.State.PLAYING) {
            return new BoomboxPlaybackState(playbackState.cursor(), playbackState.elapsedMs()
                    + Math.max(0L, System.currentTimeMillis() - playbackStateReceivedMillis),
                    playbackState.repeat(), playbackState.shuffle(), playbackState.volumePercent());
        }
        return playbackState;
    }

    @Nullable public CustomTrackData currentTrack() {
        final MediaSequenceResolver.ResolvedSequence resolved = resolveMedium();
        if (resolved == null) return null;
        final PlaybackCursor cursor = playbackState().cursor();
        return (cursor.hasPosition()
                ? resolved.at(new MediaSequence.Position(cursor.discIndex(), cursor.trackIndex()))
                : resolved.first()).map(MediaSequenceResolver.ResolvedTrack::track).orElse(null);
    }

    public int navigableTrackCount() {
        final MediaSequenceResolver.ResolvedSequence resolved = resolveMedium();
        return resolved == null ? 0 : resolved.tracks().size();
    }

    private @Nullable MediaSequenceResolver.ResolvedSequence resolveMedium() {
        final ItemStack medium = contents.getItem(MEDIA_SLOT);
        if (!ItemStack.matches(resolvedMedium, medium)) {
            resolvedMedium = medium.copy();
            resolvedSequence = MediaSequenceResolver.resolve(medium);
        }
        return resolvedSequence;
    }

    private void save() {
        if (loading || !authoritative || !isOriginalMachine()) return;
        final BoomboxContents oldContents = contentsOf(machine);
        final ItemStack medium = contents.getItem(MEDIA_SLOT).copy();
        if (ItemStack.matches(oldContents.disc(), medium)) return;
        // 容量拒否は挿入前のmayStoreだけで行う。ここで戻すとsuper.clickedが既に移したcursorや
        // inventoryとcontentsだけが食い違い、媒体を複製し得る。既存の大きい機体からの取出しも許可する。
        final boolean replaced;
        if (source.isPlaced()) {
            replaced = placedEntity != null && BoomboxPlayback.replaceMediaPlaced((ServerLevel) owner.level(),
                    source.pos(), placedEntity, medium, oldContents.cursor().generation());
        } else {
            replaced = owner instanceof ServerPlayer player && BoomboxPlayback.replaceMediaCarried(player,
                    machine, medium, oldContents.cursor().generation());
        }
        if (!replaced) return;
        applyPlaybackState(BoomboxPlayback.stateOf(machine));
    }

    private boolean mayStore(ItemStack stack) {
        if (!isAcceptedMedium(stack) || !authoritative) return isAcceptedMedium(stack);
        if (!isOriginalMachine()) return false;
        final ItemStack candidate = machine.copy();
        storeContents(candidate, contentsOf(machine).withDisc(stack));
        //? if >=1.21 {
        final boolean accepted = AlbumStorageBudget.fits(candidate, owner.level().registryAccess())
                && AlbumStorageBudget.fitsMenuSync(prospectiveMenuSlots(candidate, stack), getCarried().copy(),
                        owner.level().registryAccess());
        //?} else {
        /*final boolean accepted = AlbumStorageBudget.fits(candidate)
                && AlbumStorageBudget.fitsMenuSync(prospectiveMenuSlots(candidate, stack), getCarried().copy());
        *///?}
        if (!accepted) storageRejected = true;
        return accepted;
    }

    private List<ItemStack> prospectiveMenuSlots(ItemStack candidateMachine, ItemStack inserted) {
        final List<ItemStack> prospective = new ArrayList<>(slots.size() + 2);
        for (int i = 0; i < slots.size(); i++) {
            if (i == MEDIA_SLOT) prospective.add(inserted.copy());
            else if (i == sourceMenuSlot) prospective.add(candidateMachine);
            else prospective.add(slots.get(i).getItem().copy());
        }
        prospective.add(inserted.copy());
        if (sourceMenuSlot < 0) prospective.add(candidateMachine);
        return prospective;
    }

    //? if >=26.1 {
    @Override public void clicked(int slotId, int button, ContainerInput input, Player player) {
        storageRejected = false;
        if (!stillValid(player) || (input == ContainerInput.SWAP && isSourceSwapButton(button))) return;
        super.clicked(slotId, button, input, player);
        notifyStorageRejected();
    }
    //?} else {
    /*@Override public void clicked(int slotId, int button, ClickType input, Player player) {
        storageRejected = false;
        if (!stillValid(player) || (input == ClickType.SWAP && isSourceSwapButton(button))) return;
        super.clicked(slotId, button, input, player);
        notifyStorageRejected();
    }
    *///?}

    @Override public @NotNull ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || index == sourceMenuSlot || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        final Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        final ItemStack stack = slot.getItem();
        final ItemStack original = stack.copy();
        if (index == MEDIA_SLOT) {
            if (!moveItemStackTo(stack, PLAYER_SLOTS_START, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            storageRejected = false;
            if (!mayStore(stack) || !moveItemStackTo(stack, MEDIA_SLOT, PLAYER_SLOTS_START, false)) {
                notifyStorageRejected();
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }

    @Override public boolean stillValid(Player player) { return player == owner && isOriginalMachine(); }

    @Override public void broadcastChanges() {
        super.broadcastChanges();
        // Menu initialization can broadcast before the player has installed this menu.
        // Do not remember an unsent snapshot, or the first unchanged state will never reach the client.
        if (!authoritative || !(owner instanceof ServerPlayer player)
                || player.containerMenu != this || !stillValid(player)) return;
        final BoomboxPlaybackState state = BoomboxPlayback.stateOf(currentStack());
        applyPlaybackState(state);
        final long now = System.currentTimeMillis();
        if (lastSentPlaybackState == null || stateChanged(lastSentPlaybackState, state)
                || (state.cursor().state() == PlaybackCursor.State.PLAYING && now - lastStateSentMillis >= 1_000L)) {
            ModNetwork.sendBoomboxState(player, this);
            lastSentPlaybackState = state;
            lastStateSentMillis = now;
        }
    }

    private static boolean stateChanged(BoomboxPlaybackState before, BoomboxPlaybackState after) {
        return !before.cursor().equals(after.cursor()) || before.repeat() != after.repeat()
                || before.shuffle() != after.shuffle() || before.volumePercent() != after.volumePercent();
    }

    private boolean isOriginalMachine() {
        if (source == null) return false;
        if (source.isPlaced()) {
            if (!(owner.level().getBlockEntity(source.pos()) instanceof BoomboxBlockEntity current)) return false;
            if (owner.distanceToSqr(Vec3.atCenterOf(source.pos())) > 64.0) return false;
            // BlockEntityのstored ItemStackはclientへ同期していない。clientは存在・種類・距離だけを見て
            // slot同期を受け、serverだけが開いた時のBEと機体参照が同じことを保存の条件にする。
            return !authoritative || (isBoombox(machine) && current == placedEntity && current.getStored() == machine);
        }
        if (!isBoombox(machine)) return false;
        final ItemStack held = owner.getItemInHand(source.heldHand());
        return isBoombox(held) && (!authoritative || held == machine);
    }

    private static ItemStack resolveMachine(Player owner, BoomboxSource source, @Nullable BoomboxBlockEntity placed) {
        return source.isPlaced() ? placed == null ? ItemStack.EMPTY : placed.getStored()
                : owner.getItemInHand(source.heldHand());
    }

    private static boolean isBoombox(ItemStack stack) { return !stack.isEmpty() && stack.is(ModItems.BOOMBOX.get()); }
    private static boolean isAcceptedMedium(ItemStack stack) {
        return stack.is(ModItems.CUSTOM_MUSIC_DISC.get()) || stack.is(ModItems.ALBUM.get());
    }

    private static BoomboxContents contentsOf(ItemStack stack) {
        //? if >=1.21 {
        return stack.getOrDefault(ModDataComponents.BOOMBOX_CONTENTS.get(), BoomboxContents.EMPTY);
        //?} else {
        /*return BoomboxContents.of(stack);
        *///?}
    }

    private static void storeContents(ItemStack stack, BoomboxContents value) {
        //? if >=1.21 {
        stack.set(ModDataComponents.BOOMBOX_CONTENTS.get(), value);
        //?} else {
        /*BoomboxContents.store(stack, value);
        *///?}
    }

    private void notifyStorageRejected() {
        if (storageRejected && authoritative && owner instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.translatable("message.music_disc_maker.boombox.storage_too_large"), true);
        }
        storageRejected = false;
    }

    private static int findInventorySlot(Inventory inventory, ItemStack stack) {
        for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i) == stack) return i;
        return -1;
    }

    private boolean isSourceSwapButton(int button) {
        if (sourceInventorySlot >= 0) return button == sourceInventorySlot;
        return !source.isPlaced() && source.heldHand() == net.minecraft.world.InteractionHand.OFF_HAND && button == 40;
    }

    private final class MediaSlot extends Slot {
        MediaSlot(Container container, int slot, int x, int y) { super(container, slot, x, y); }
        @Override public boolean mayPlace(@NotNull ItemStack stack) { return mayStore(stack); }
        @Override public int getMaxStackSize() { return 1; }
    }

    private static final class LockedSourceSlot extends Slot {
        LockedSourceSlot(Container container, int slot, int x, int y) { super(container, slot, x, y); }
        @Override public boolean mayPlace(@NotNull ItemStack stack) { return false; }
        @Override public boolean mayPickup(Player player) { return false; }
    }
}
