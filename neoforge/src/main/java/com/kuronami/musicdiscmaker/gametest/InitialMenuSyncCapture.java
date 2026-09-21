package com.kuronami.musicdiscmaker.gametest;

import java.util.ArrayList;
import java.util.List;

//? if >=1.21.2 {
import net.minecraft.world.inventory.RemoteSlot;
//?} else {
/*import net.minecraft.core.NonNullList;
*///?}
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.item.ItemStack;

/** 一括同期と、その直後のslot/cursor単位同期を分けて検証するGameTest用capture。 */
final class InitialMenuSyncCapture implements ContainerSynchronizer {
    record SlotChange(int slot, ItemStack stack) { }

    int initialCalls;
    List<ItemStack> initial = List.of();
    ItemStack initialCarried = ItemStack.EMPTY;
    final List<SlotChange> slotChanges = new ArrayList<>();
    final List<ItemStack> carriedChanges = new ArrayList<>();

    //? if >=1.21.2 {
    @Override public void sendInitialData(AbstractContainerMenu menu, List<ItemStack> stacks,
    //?} else {
    /*@Override public void sendInitialData(AbstractContainerMenu menu, NonNullList<ItemStack> stacks,
    *///?}
            ItemStack carried, int[] data) {
        initialCalls++;
        initial = stacks.stream().map(ItemStack::copy).toList();
        initialCarried = carried.copy();
    }

    @Override public void sendSlotChange(AbstractContainerMenu menu, int slot, ItemStack stack) {
        slotChanges.add(new SlotChange(slot, stack.copy()));
    }

    @Override public void sendCarriedChange(AbstractContainerMenu menu, ItemStack carried) {
        carriedChanges.add(carried.copy());
    }
    @Override public void sendDataChange(AbstractContainerMenu menu, int slot, int value) { }

    //? if >=1.21.2 {
    @Override public RemoteSlot createSlot() {
        return new RemoteSlot() {
            private ItemStack remote = ItemStack.EMPTY;

            @Override public void force(ItemStack stack) { remote = stack.copy(); }
            @Override public void receive(net.minecraft.network.HashedStack stack) { }
            @Override public boolean matches(ItemStack stack) { return ItemStack.matches(remote, stack); }
        };
    }
    //?}

    void clearDeltas() {
        slotChanges.clear();
        carriedChanges.clear();
    }
}
