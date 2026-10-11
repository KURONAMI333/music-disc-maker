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

/**
 * Album (2段) と Golden Album (6段) の収納 menu。段数は menu type で決まり、client も server と同じ
 * 枠数で組み立てる。永続値への反映は server 側 container の変更時だけに行う。
 */
public class AlbumMenu extends AbstractContainerMenu {
    private final int rows;
    private final int albumSlots;
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
    private AlbumStorageBudget.Result storageFailure;
    private boolean deferFullSyncValues;

    //? if >=1.21 {
    public AlbumMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(id, inventory, BoomboxSource.STREAM_CODEC.decode(buf));
    }

    public static AlbumMenu golden(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        return golden(id, inventory, BoomboxSource.STREAM_CODEC.decode(buf));
    }
    //?} else {
    /*public AlbumMenu(int id, Inventory inventory, FriendlyByteBuf buf) {
        this(id, inventory, BoomboxSource.read(buf));
    }

    public static AlbumMenu golden(int id, Inventory inventory, FriendlyByteBuf buf) {
        return golden(id, inventory, BoomboxSource.read(buf));
    }
    *///?}

    public AlbumMenu(int id, Inventory inventory, BoomboxSource source) {
        this(ModMenus.ALBUM.get(), AlbumMenuLayout.ROWS, id, inventory, source);
    }

    public static AlbumMenu golden(int id, Inventory inventory, BoomboxSource source) {
        return new AlbumMenu(ModMenus.GOLDEN_ALBUM.get(), AlbumMenuLayout.GOLDEN_ROWS, id, inventory, source);
    }

    /** server 側で開く時の入口。手に持っている Album の種類で menu type と段数を選ぶ。 */
    public static AlbumMenu forHeld(int id, Inventory inventory, BoomboxSource source) {
        return isGoldenHeld(inventory.player, source)
                ? golden(id, inventory, source) : new AlbumMenu(id, inventory, source);
    }

    /** 画面の題。手に持っている Album の種類の名前。 */
    public static Component title(Player player, BoomboxSource source) {
        return Component.translatable(isGoldenHeld(player, source)
                ? "item.music_disc_maker.golden_album" : "item.music_disc_maker.album");
    }

    private static boolean isGoldenHeld(Player player, BoomboxSource source) {
        return source != null && source.isHeld()
                && player.getItemInHand(source.heldHand()).is(ModItems.GOLDEN_ALBUM.get());
    }

    private AlbumMenu(net.minecraft.world.inventory.MenuType<?> type, int rows, int id, Inventory inventory,
            BoomboxSource source) {
        super(type, id);
        this.rows = rows;
        this.albumSlots = AlbumMenuLayout.capacity(rows);
        this.source = source;
        this.owner = inventory.player;
        // AlbumMenuを開けるのは手持ちのAlbumだけ。wire経由では設置・装備・任意intのsourceが
        // 届き得るので、ここでheldHand()を無条件に呼ぶとmenu生成中に例外になりclientが切断される。
        // 非held sourceは空のAlbumとして生成し、stillValid=falseで無効なmenuとして扱う。
        this.album = source != null && source.isHeld()
                ? inventory.player.getItemInHand(source.heldHand()) : ItemStack.EMPTY;
        this.authoritative = !inventory.player.level().isClientSide();
        // 選択hotbar番号の公開APIは版によって異なる。保持中stackの同一参照で探せば、
        // menuを開いた元Albumだけを全対応帯でロックできる。
        this.sourceInventorySlot = source != null && source.isHeld()
                && source.heldHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                ? findInventorySlot(inventory, album) : -1;
        this.overflow = new ArrayList<>();
        this.contents = new SimpleContainer(albumSlots) {
            @Override public void setChanged() { super.setChanged(); save(); }
        };
        if (authoritative && AlbumItem.capacity(album) == albumSlots) {
            final List<ItemStack> saved = AlbumItem.contents(album).discs();
            loading = true;
            for (int i = 0; i < Math.min(saved.size(), albumSlots); i++) contents.setItem(i, saved.get(i));
            for (int i = albumSlots; i < saved.size(); i++) overflow.add(saved.get(i).copy());
            loading = false;
        }
        for (int i = 0; i < albumSlots; i++) {
            addSlot(new AlbumSlot(contents, i, AlbumMenuLayout.albumSlotX(rows, i), AlbumMenuLayout.albumSlotY(rows, i)));
        }
        for (int row = 0; row < 3; row++) for (int col = 0; col < AlbumMenuLayout.COLUMNS; col++) {
            addSlot(new DeferredInitialInventorySlot(inventory, col + row * AlbumMenuLayout.COLUMNS + 9,
                    AlbumMenuLayout.albumSlotX(rows, col), AlbumMenuLayout.playerInventoryY(rows, row),
                    () -> deferFullSyncValues));
        }
        for (int col = 0; col < AlbumMenuLayout.COLUMNS; col++) {
            addSlot(col == sourceInventorySlot
                    ? new LockedSourceSlot(inventory, col, AlbumMenuLayout.albumSlotX(rows, col),
                            AlbumMenuLayout.hotbarY(rows), () -> deferFullSyncValues)
                    : new DeferredInitialInventorySlot(inventory, col, AlbumMenuLayout.albumSlotX(rows, col),
                            AlbumMenuLayout.hotbarY(rows), () -> deferFullSyncValues));
        }
        for (int i = albumSlots; i < slots.size(); i++) {
            if (slots.get(i).container == inventory && slots.get(i).getContainerSlot() == sourceInventorySlot) {
                sourceMenuSlot = i;
                break;
            }
        }
    }

    /**
     * 初回の一括packetに残す内部Album枠を、実ItemStack codecで先に検証する。
     * player inventoryは{@link #sendAllDataToRemote()}が直後のslot単位packetへ分割する。
     */
    public static boolean fitsInitialMenuSync(Inventory inventory, BoomboxSource source) {
        return inspectInitialMenuSync(inventory, source).fits();
    }

    public static AlbumStorageBudget.Result inspectInitialMenuSync(Inventory inventory, BoomboxSource source) {
        if (source == null || !source.isHeld()) return AlbumStorageBudget.invalidSource();
        final ItemStack heldAlbum = inventory.player.getItemInHand(source.heldHand());
        final int capacity = AlbumItem.capacity(heldAlbum);
        if (capacity == 0) return AlbumStorageBudget.invalidSource();
        final List<ItemStack> initialSlots = new ArrayList<>(capacity);
        final List<ItemStack> saved = AlbumItem.contents(heldAlbum).discs();
        for (int i = 0; i < capacity; i++) {
            initialSlots.add(i < saved.size() ? saved.get(i).copy() : ItemStack.EMPTY);
        }
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) initialSlots.add(ItemStack.EMPTY);
        //? if >=1.21 {
        return AlbumStorageBudget.inspectInitialMenuSync(initialSlots, ItemStack.EMPTY,
                inventory.player.level().registryAccess());
        //?} else {
        /*return AlbumStorageBudget.inspectInitialMenuSync(initialSlots, ItemStack.EMPTY);
        *///?}
    }

    /**
     * vanillaのfull syncは内部枠・player inventory・cursorを1 packetへ詰める。player側とcursorを
     * 空で送り、同じ同期中に通常のslot差分として復元して、合法な大きい値を合算しない。
     * stateId不一致のbroadcastFullStateやspectator clickもこの経路を通るため、初回だけに限らない。
     */
    @Override public void sendAllDataToRemote() {
        if (!authoritative) {
            super.sendAllDataToRemote();
            return;
        }
        deferFullSyncValues = true;
        try {
            super.sendAllDataToRemote();
        } finally {
            deferFullSyncValues = false;
        }
        super.broadcastChanges();
    }

    @Override public ItemStack getCarried() {
        return deferFullSyncValues ? ItemStack.EMPTY : super.getCarried();
    }

    private void save() {
        if (loading || !authoritative || !isOriginalAlbumHeld()) return;
        final List<ItemStack> saved = new ArrayList<>();
        for (int i = 0; i < albumSlots; i++) if (!contents.getItem(i).isEmpty()) saved.add(contents.getItem(i));
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
        for (int i = 0; i < albumSlots; i++) {
            if (contents.getItem(i).isEmpty()) {
                hasGap = true;
                break;
            }
        }
        if (!hasGap) return;

        final List<ItemStack> ordered = new ArrayList<>();
        for (int i = 0; i < albumSlots; i++) {
            final ItemStack stack = contents.getItem(i);
            if (!stack.isEmpty()) ordered.add(stack.copy());
        }
        for (final ItemStack stack : overflow) ordered.add(stack.copy());
        loading = true;
        for (int i = 0; i < albumSlots; i++) {
            contents.setItem(i, i < ordered.size() ? ordered.get(i) : ItemStack.EMPTY);
        }
        overflow.clear();
        for (int i = albumSlots; i < ordered.size(); i++) overflow.add(ordered.get(i));
        loading = false;
        save();
    }

    private boolean mayStore(ItemStack stack) {
        if (!AlbumContents.canInsert(stack, com.kuronami.musicdiscmaker.component.PlayableDiscs::isDisc,
                candidate -> AlbumItem.isAlbum(candidate) || candidate.is(ModItems.BOOMBOX.get()))) {
            return false;
        }
        // clientは予測だけ通し、serverの拒否後に通常のmenu slot同期で戻す。
        if (!authoritative) return true;
        final List<ItemStack> prospective = new ArrayList<>();
        for (int i = 0; i < albumSlots; i++) {
            final ItemStack saved = contents.getItem(i);
            if (!saved.isEmpty()) prospective.add(saved.copy());
        }
        for (final ItemStack saved : overflow) prospective.add(saved.copy());
        if (prospective.size() >= AlbumContents.STORAGE_DECODE_LIMIT) {
            storageFailure = AlbumStorageBudget.entryLimit(AlbumStorageBudget.Stage.ALBUM);
            return false;
        }
        prospective.add(stack.copy());
        final ItemStack candidateAlbum = album.copy();
        AlbumItem.setContents(candidateAlbum, new AlbumContents(prospective));
        //? if >=1.21 {
        AlbumStorageBudget.Result result = AlbumStorageBudget.inspect(candidateAlbum, owner.level().registryAccess()).at(AlbumStorageBudget.Stage.ALBUM);
        if (result.fits()) result = inspectBoomboxOuter(candidateAlbum);
        if (result.fits()) result = AlbumStorageBudget.inspectMenuSync(prospectiveMenuSlots(stack, candidateAlbum), getCarried().copy(), owner.level().registryAccess());
        //?} else {
        /*AlbumStorageBudget.Result result = AlbumStorageBudget.inspect(candidateAlbum).at(AlbumStorageBudget.Stage.ALBUM);
        if (result.fits()) result = inspectBoomboxOuter(candidateAlbum);
        if (result.fits()) result = AlbumStorageBudget.inspectMenuSync(prospectiveMenuSlots(stack, candidateAlbum), getCarried().copy());
        *///?}
        if (!result.fits()) storageFailure = result;
        return result.fits();
    }

    private AlbumStorageBudget.Result inspectBoomboxOuter(ItemStack candidateAlbum) {
        final ItemStack candidateBoombox = new ItemStack(ModItems.BOOMBOX.get());
        //? if >=1.21 {
        candidateBoombox.set(ModDataComponents.BOOMBOX_CONTENTS.get(), new BoomboxContents(candidateAlbum, 1L));
        return AlbumStorageBudget.inspect(candidateBoombox, owner.level().registryAccess()).at(AlbumStorageBudget.Stage.BOOMBOX_OUTER);
        //?} else {
        /*BoomboxContents.store(candidateBoombox, new BoomboxContents(candidateAlbum, 1L));
        return AlbumStorageBudget.inspect(candidateBoombox).at(AlbumStorageBudget.Stage.BOOMBOX_OUTER);
        *///?}
    }

    /**
     * 内部枠のfull syncと、挿入クリックで変わり得る値だけを保守的に合算する。
     * 無関係なplayer inventoryはslot単位で同期され、クリックのchangedSlotsにも入らない。
     * 交換で外へ出る旧盤は内部枠に残し、入力元・cursor・更新後の元Albumも予算から落とさない。
     */
    private List<ItemStack> prospectiveMenuSlots(ItemStack inserted, ItemStack candidateAlbum) {
        final List<ItemStack> prospective = new ArrayList<>(albumSlots + 2);
        for (int i = 0; i < albumSlots; i++) prospective.add(contents.getItem(i).copy());
        prospective.add(candidateAlbum);
        prospective.add(inserted.copy());
        return prospective;
    }

    /** 画面の段数。 */
    public int rows() {
        return rows;
    }

    @Override public boolean stillValid(Player player) {
        return source != null && !source.isPlaced() && isOriginalAlbumHeld();
    }

    //? if >=26.1 {
    @Override public void clicked(int slotId, int button, ContainerInput input, Player player) {
        storageFailure = null;
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
        storageFailure = null;
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
        if (!stillValid(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        if (index == sourceMenuSlot) return ItemStack.EMPTY;
        final Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        final ItemStack stack = slot.getItem();
        final ItemStack original = stack.copy();
        if (index < albumSlots) {
            if (!moveItemStackTo(stack, albumSlots, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            storageFailure = null;
            if (!mayStore(stack)) {
                notifyStorageRejected();
                return ItemStack.EMPTY;
            }
            if (!moveItemStackTo(stack, 0, albumSlots, false)) return ItemStack.EMPTY;
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
        if (storageFailure != null && authoritative && owner instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(Component.translatable(storageFailure.rejectionKey("message.music_disc_maker.album.storage_too_large")), true);
        }
        storageFailure = null;
    }

    /** 開いているAlbumの元stackを画面内で移動できないようにする。 */
    private static final class LockedSourceSlot extends DeferredInitialInventorySlot {
        LockedSourceSlot(Container container, int slot, int x, int y,
                java.util.function.BooleanSupplier deferred) {
            super(container, slot, x, y, deferred);
        }
        @Override public boolean mayPlace(@NotNull ItemStack stack) { return false; }
        @Override public boolean mayPickup(Player player) { return false; }
    }

    private boolean isOriginalAlbumHeld() {
        if (source == null || !source.isHeld()) return false;
        final ItemStack held = owner.getItemInHand(source.heldHand());
        // Slot同期はclientのItemStack参照を差し替える。保存先の参照同一性はserverだけで検証する。
        return AlbumItem.capacity(held) == albumSlots && (!authoritative || album == held);
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
        return source != null && source.isHeld()
                && source.heldHand() == net.minecraft.world.InteractionHand.OFF_HAND && button == 40;
    }
}

