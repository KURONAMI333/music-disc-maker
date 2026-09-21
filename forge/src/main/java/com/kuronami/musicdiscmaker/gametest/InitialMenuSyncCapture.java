package com.kuronami.musicdiscmaker.gametest;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.NonNullList;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.world.item.ItemStack;

/** Forge 1.20.1の一括同期とslot/cursor単位同期を分けて検証するcapture。 */
final class InitialMenuSyncCapture implements ContainerSynchronizer {
    record SlotChange(int slot, ItemStack stack) { }

    int initialCalls;
    List<ItemStack> initial = List.of();
    ItemStack initialCarried = ItemStack.EMPTY;
    final List<SlotChange> slotChanges = new ArrayList<>();
    final List<ItemStack> carriedChanges = new ArrayList<>();

    @Override public void sendInitialData(AbstractContainerMenu menu, NonNullList<ItemStack> stacks,
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

    void clearDeltas() {
        slotChanges.clear();
        carriedChanges.clear();
    }
}
