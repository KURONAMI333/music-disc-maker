package com.kuronami.musicdiscmaker.menu;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;

import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.component.AlbumStorageBudget;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModMenus;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.register.ModDataComponents;
//?} else {
//?}

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
//? if >=26.1 {
import net.minecraft.world.inventory.ContainerInput;
//?} else {
/*import net.minecraft.world.inventory.ClickType;
*///?}
//? if >=1.21 {
import net.minecraft.network.RegistryFriendlyByteBuf;
//?} else {
/*import net.minecraft.network.FriendlyByteBuf;
*///?}

/** Album の9枠収納 menu。永続値への反映は server 側 container の変更時だけに行う。 */
public class AlbumMenu extends AbstractContainerMenu {
    private static final int ALBUM_SLOTS = AlbumItem.GUI_CAPACITY;
    private final BoomboxSource source;
    private final Player owner;
    private final ItemStack album;
    private final SimpleContainer contents;
    private final boolean authoritative;
    private final List<ItemStack> overflow;
    private final int sourceInventorySlot;
    private int sourceMenuSlot = -1;
    private boolean loading;
    private boolean handlingClick;
    private boolean storageRejected;

    //? if >=1.21 {
    public AlbumMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(id, inventory, BoomboxSource.STREAM_CODEC.decode(buf));
    }
    //?} else {
    /*public AlbumMenu(int id, Inventory inventory, FriendlyByteBuf buf) {
        this(id, inventory, BoomboxSource.read(buf));
    }
    *///?}

    public AlbumMenu(int id, Inventory inventory, BoomboxSource source) {
        super(ModMenus.ALBUM.get(), id);
        this.source = source;
        this.owner = inventory.player;
        this.album = inventory.player.getItemInHand(source.heldHand());
        this.authoritative = !inventory.player.level().isClientSide();
        // 選択hotbar番号の公開APIは版によって異なる。保持中stackの同一参照で探せば、
        // menuを開いた元Albumだけを全対応帯でロックできる。
        this.sourceInventorySlot = source.heldHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                ? findInventorySlot(inventory, album) : -1;
        this.overflow = new ArrayList<>();
        this.contents = new SimpleContainer(ALBUM_SLOTS) {
            @Override public void setChanged() { super.setChanged(); save(); }
        };
        if (authoritative && album.is(ModItems.ALBUM.get())) {
            final List<ItemStack> saved = AlbumItem.contents(album).discs();
            loading = true;
            for (int i = 0; i < Math.min(saved.size(), ALBUM_SLOTS); i++) contents.setItem(i, saved.get(i));
            for (int i = ALBUM_SLOTS; i < saved.size(); i++) overflow.add(saved.get(i).copy());
            loading = false;
        }
        for (int i = 0; i < ALBUM_SLOTS; i++) addSlot(new AlbumSlot(contents, i, 8 + (i % 9) * 18, 18));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 49 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(col == sourceInventorySlot
                ? new LockedSourceSlot(inventory, col, 8 + col * 18, 107)
                : new Slot(inventory, col, 8 + col * 18, 107));
        for (int i = ALBUM_SLOTS; i < slots.size(); i++) {
            if (slots.get(i).container == inventory && slots.get(i).getContainerSlot() == sourceInventorySlot) {
                sourceMenuSlot = i;
                break;
            }
        }
    }

    /**
     * 初回open packetの全slotとcursorを、実ItemStack codecで先に検証する。
     * 実際にmenuへ登録する内部9枠とプレイヤー所持品36枠だけを検査する。
     * 装備・offhandはこのmenuのslotではなく、初回container packetにも載らない。
     */
    public static boolean fitsInitialMenuSync(Inventory inventory, BoomboxSource source) {
        final ItemStack heldAlbum = inventory.player.getItemInHand(source.heldHand());
        if (!heldAlbum.is(ModItems.ALBUM.get())) return false;
        final List<ItemStack> initialSlots = new ArrayList<>(ALBUM_SLOTS + 37);
        final List<ItemStack> saved = AlbumItem.contents(heldAlbum).discs();
        for (int i = 0; i < ALBUM_SLOTS; i++) {
            initialSlots.add(i < saved.size() ? saved.get(i).copy() : ItemStack.EMPTY);
        }
        for (int i = 9; i < Inventory.INVENTORY_SIZE; i++) {
            final ItemStack stack = inventory.getItem(i);
            initialSlots.add(stack.copy());
        }
        for (int i = 0; i < 9; i++) {
            final ItemStack stack = inventory.getItem(i);
            initialSlots.add(stack.copy());
        }
        //? if >=1.21 {
        return AlbumStorageBudget.fitsInitialMenuSync(initialSlots, ItemStack.EMPTY,
                inventory.player.level().registryAccess());
        //?} else {
        /*return AlbumStorageBudget.fitsInitialMenuSync(initialSlots, ItemStack.EMPTY);
        *///?}
    }

    private void save() {
        if (loading || !authoritative || !isOriginalAlbumHeld()) return;
        final List<ItemStack> saved = new ArrayList<>();
        for (int i = 0; i < ALBUM_SLOTS; i++) if (!contents.getItem(i).isEmpty()) saved.add(contents.getItem(i));
        for (ItemStack stack : overflow) saved.add(stack.copy());
        AlbumItem.setContents(album, saved.isEmpty() ? AlbumContents.EMPTY : new AlbumContents(saved));
    }

    /**
     * 画面枠の空きを保存順で詰め、overflowから補充する。super.clickedのQUICK_MOVE中に呼ぶと
     * 同一slotが再評価されて一操作で全盤を移すため、呼び出し側が処理完了後に一度だけ実行する。
     */
    private void refillVisibleFromOverflow() {
        if (overflow.isEmpty()) return;
        boolean hasGap = false;
        for (int i = 0; i < ALBUM_SLOTS; i++) {
            if (contents.getItem(i).isEmpty()) {
                hasGap = true;
                break;
            }
        }
        if (!hasGap) return;

        final List<ItemStack> ordered = new ArrayList<>();
        for (int i = 0; i < ALBUM_SLOTS; i++) {
            final ItemStack stack = contents.getItem(i);
            if (!stack.isEmpty()) ordered.add(stack.copy());
        }
        for (final ItemStack stack : overflow) ordered.add(stack.copy());
        loading = true;
        for (int i = 0; i < ALBUM_SLOTS; i++) {
            contents.setItem(i, i < ordered.size() ? ordered.get(i) : ItemStack.EMPTY);
        }
        overflow.clear();
        for (int i = ALBUM_SLOTS; i < ordered.size(); i++) overflow.add(ordered.get(i));
        loading = false;
        save();
    }

    private boolean mayStore(ItemStack stack) {
        if (!AlbumContents.canInsert(stack, candidate -> candidate.is(ModItems.CUSTOM_MUSIC_DISC.get()),
                candidate -> candidate.is(ModItems.ALBUM.get()) || candidate.is(ModItems.BOOMBOX.get()))) {
            return false;
        }
        // clientは予測だけ通し、serverの拒否後に通常のmenu slot同期で戻す。
        if (!authoritative) return true;
        final List<ItemStack> prospective = new ArrayList<>();
        for (int i = 0; i < ALBUM_SLOTS; i++) {
            final ItemStack saved = contents.getItem(i);
            if (!saved.isEmpty()) prospective.add(saved.copy());
        }
        for (final ItemStack saved : overflow) prospective.add(saved.copy());
        if (prospective.size() >= AlbumContents.STORAGE_DECODE_LIMIT) {
            storageRejected = true;
            return false;
        }
        prospective.add(stack.copy());
        final ItemStack candidateAlbum = album.copy();
        AlbumItem.setContents(candidateAlbum, new AlbumContents(prospective));
        //? if >=1.21 {
        final boolean accepted = AlbumStorageBudget.fits(candidateAlbum, owner.level().registryAccess())
                && fitsBoomboxOuter(candidateAlbum)
                && AlbumStorageBudget.fitsMenuSync(prospectiveMenuSlots(stack, candidateAlbum), getCarried().copy(),
                        owner.level().registryAccess());
        //?} else {
        /*final boolean accepted = AlbumStorageBudget.fits(candidateAlbum)
                && fitsBoomboxOuter(candidateAlbum)
                && AlbumStorageBudget.fitsMenuSync(prospectiveMenuSlots(stack, candidateAlbum), getCarried().copy());
        *///?}
        if (!accepted) storageRejected = true;
        return accepted;
    }

    private boolean fitsBoomboxOuter(ItemStack candidateAlbum) {
        final ItemStack candidateBoombox = new ItemStack(ModItems.BOOMBOX.get());
        //? if >=1.21 {
        candidateBoombox.set(ModDataComponents.BOOMBOX_CONTENTS.get(),
                new BoomboxContents(candidateAlbum, 1L));
        return AlbumStorageBudget.fits(candidateBoombox, owner.level().registryAccess());
        //?} else {
        /*BoomboxContents.store(candidateBoombox, new BoomboxContents(candidateAlbum, 1L));
        return AlbumStorageBudget.fits(candidateBoombox);
        *///?}
    }

    private List<ItemStack> prospectiveMenuSlots(ItemStack inserted, ItemStack candidateAlbum) {
        final List<ItemStack> prospective = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            final ItemStack current = slots.get(i).getItem();
            if (i == sourceMenuSlot) {
                prospective.add(candidateAlbum);
            } else {
                prospective.add(current.copy());
            }
        }
        // 入力元を消さず、Album slotの交換後に見える候補を必ず足す保守上界にする。
        // 通常クリックのcursorはgetCarried()側にも残しており、拒否判定が実同期量を過小評価しない。
        prospective.add(inserted.copy());
        if (sourceMenuSlot < 0) prospective.add(candidateAlbum);
        return prospective;
    }

    @Override public boolean stillValid(Player player) {
        return source != null && !source.isPlaced() && isOriginalAlbumHeld();
    }

    //? if >=26.1 {
    @Override public void clicked(int slotId, int button, ContainerInput input, Player player) {
        storageRejected = false;
        if (!stillValid(player) || (input == ContainerInput.SWAP && isSourceSwapButton(button))) return;
        handlingClick = true;
        try {
            super.clicked(slotId, button, input, player);
        } finally {
            handlingClick = false;
        }
        refillVisibleFromOverflow();
        notifyStorageRejected();
    }
    //?} else {
    /*@Override public void clicked(int slotId, int button, ClickType input, Player player) {
        storageRejected = false;
        if (!stillValid(player) || (input == ClickType.SWAP && isSourceSwapButton(button))) return;
        handlingClick = true;
        try {
            super.clicked(slotId, button, input, player);
        } finally {
            handlingClick = false;
        }
        refillVisibleFromOverflow();
        notifyStorageRejected();
    }
    *///?}

    @Override public @NotNull ItemStack quickMoveStack(Player player, int index) {
        // 閉じたmenuから遅れて届くshift-clickは、保存先が同一stackでない時点で一切処理しない。
        if (!stillValid(player)) return ItemStack.EMPTY;
        if (index == sourceMenuSlot) return ItemStack.EMPTY;
        final Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        final ItemStack stack = slot.getItem();
        final ItemStack original = stack.copy();
        if (index < ALBUM_SLOTS) {
            if (!moveItemStackTo(stack, ALBUM_SLOTS, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            storageRejected = false;
            if (!mayStore(stack)) {
                notifyStorageRejected();
                return ItemStack.EMPTY;
            }
            if (!moveItemStackTo(stack, 0, ALBUM_SLOTS, false)) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY); else slot.setChanged();
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        if (!handlingClick) refillVisibleFromOverflow();
        return original;
    }

    private final class AlbumSlot extends Slot {
        AlbumSlot(Container container, int slot, int x, int y) { super(container, slot, x, y); }
        @Override public boolean mayPlace(@NotNull ItemStack stack) { return mayStore(stack); }
        @Override public int getMaxStackSize() { return 1; }
    }

    private void notifyStorageRejected() {
        if (storageRejected && authoritative && owner instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(Component.translatable("message.music_disc_maker.album.storage_too_large"), true);
        }
        storageRejected = false;
    }

    /** 開いているAlbumの元stackを画面内で移動できないようにする。 */
    private static final class LockedSourceSlot extends Slot {
        LockedSourceSlot(Container container, int slot, int x, int y) { super(container, slot, x, y); }
        @Override public boolean mayPlace(@NotNull ItemStack stack) { return false; }
        @Override public boolean mayPickup(Player player) { return false; }
    }

    private boolean isOriginalAlbumHeld() {
        final ItemStack held = owner.getItemInHand(source.heldHand());
        // Slot同期はclientのItemStack参照を差し替える。保存先の参照同一性はserverだけで検証する。
        return !held.isEmpty() && held.is(ModItems.ALBUM.get()) && (!authoritative || album == held);
    }

    private static int findInventorySlot(Inventory inventory, ItemStack stack) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i) == stack) return i;
        }
        return -1;
    }

    /** SWAPはクリックしたslotの反対側（hotbar/offhand）も移動させるため、source側を別途保護する。 */
    private boolean isSourceSwapButton(int button) {
        if (sourceInventorySlot >= 0) return button == sourceInventorySlot;
        return source.heldHand() == net.minecraft.world.InteractionHand.OFF_HAND && button == 40;
    }
}

