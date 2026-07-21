package com.kuronami.musicdiscmaker.menu;

import org.jetbrains.annotations.NotNull;

import com.kuronami.musicdiscmaker.block.EnhancedJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 強化版ジュークボックスの設定 menu。ディスクスロット 1 枠 + プレイヤーインベントリ。
 * 範囲/音量/リピート/再生停止は widget として Screen 側が描画し、C2S で BE に反映する。
 */
public class EnhancedJukeboxMenu extends AbstractContainerMenu {

    private final EnhancedJukeboxBlockEntity blockEntity;

    // client 側コンストラクタ (IMenuTypeExtension 経由で BlockPos を受け取る)
    public EnhancedJukeboxMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory, resolve(playerInventory, buf));
    }

    // server 側コンストラクタ
    public EnhancedJukeboxMenu(int containerId, Inventory playerInventory, EnhancedJukeboxBlockEntity blockEntity) {
        super(ModMenus.ENHANCED_JUKEBOX.get(), containerId);
        this.blockEntity = blockEntity;

        // ディスクスロット (再生可能ディスクのみ)
        addSlot(new DiscSlot(blockEntity, EnhancedJukeboxBlockEntity.SLOT_DISC, 12, 18));

        // プレイヤーインベントリ 3 行
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 120 + row * 18));
            }
        }
        // ホットバー
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 180));
        }
    }

    private static EnhancedJukeboxBlockEntity resolve(Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        final var be = playerInventory.player.level().getBlockEntity(buf.readBlockPos());
        if (be instanceof EnhancedJukeboxBlockEntity jukebox) {
            return jukebox;
        }
        throw new IllegalStateException("Enhanced Jukebox BlockEntity が見つからない");
    }

    public EnhancedJukeboxBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Override
    public boolean stillValid(Player player) {
        return blockEntity.getLevel() != null
                && blockEntity.getLevel().getBlockState(blockEntity.getBlockPos()).is(ModBlocks.ENHANCED_JUKEBOX.get())
                && player.distanceToSqr(
                        blockEntity.getBlockPos().getX() + 0.5,
                        blockEntity.getBlockPos().getY() + 0.5,
                        blockEntity.getBlockPos().getZ() + 0.5) <= 64.0;
    }

    @Override
    public @NotNull ItemStack quickMoveStack(Player player, int index) {
        final Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        final ItemStack stack = slot.getItem();
        final ItemStack original = stack.copy();

        final int blockSlots = 1;
        final int playerStart = blockSlots;
        final int playerEnd = slots.size();

        if (index < blockSlots) {
            // ディスクスロット → プレイヤー
            if (!moveItemStackTo(stack, playerStart, playerEnd, true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickCraft(stack, original);
        } else {
            // プレイヤー → ディスクスロット (再生可能ディスクのみ)
            if (!moveItemStackTo(stack, EnhancedJukeboxBlockEntity.SLOT_DISC, EnhancedJukeboxBlockEntity.SLOT_DISC + 1, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return original;
    }

    /** ディスクスロット: 再生可能ディスク (JUKEBOX_PLAYABLE) のみ受け入れる。 */
    private static class DiscSlot extends Slot {
        DiscSlot(EnhancedJukeboxBlockEntity be, int slot, int x, int y) {
            super(be, slot, x, y);
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            return stack.has(DataComponents.JUKEBOX_PLAYABLE);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
