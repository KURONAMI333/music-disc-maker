package com.kuronami.musicdiscmaker.menu;

import java.util.function.BooleanSupplier;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * menuの一括full syncではplayer inventoryを空として送り、直後のslot単位同期へ分ける。
 *
 * <p>containerの初回packetはmenuに登録された全slotを1 packetへ詰めるため、個々には合法な
 * 大きいItemStackを複数持っているだけでも2 MiBのframe上限を越える。実inventoryは変更せず、
 * {@link Slot#getItem()} の同期用viewだけを一時的に空にする。
 */
class DeferredInitialInventorySlot extends Slot {
    private final BooleanSupplier deferred;

    DeferredInitialInventorySlot(Container container, int slot, int x, int y, BooleanSupplier deferred) {
        super(container, slot, x, y);
        this.deferred = deferred;
    }

    @Override public ItemStack getItem() {
        return deferred.getAsBoolean() ? ItemStack.EMPTY : super.getItem();
    }
}
