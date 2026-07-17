package com.kuronami.musicdiscmaker.menu;

import org.jetbrains.annotations.NotNull;

import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class MusicDiscMakerMenu extends AbstractContainerMenu {

    private final MusicDiscMakerBlockEntity blockEntity;

    // client 側コンストラクタ (extended MenuType 経由で BlockPos を受け取る)
    public MusicDiscMakerMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        this(containerId, playerInventory, resolve(playerInventory, pos));
    }

    // server 側コンストラクタ
    public MusicDiscMakerMenu(int containerId, Inventory playerInventory, MusicDiscMakerBlockEntity blockEntity) {
        super(ModMenus.MUSIC_DISC_MAKER.get(), containerId);
        this.blockEntity = blockEntity;

        // 入力 (空ディスク)
        addSlot(new Slot(blockEntity, MusicDiscMakerBlockEntity.SLOT_INPUT, 62, 47));
        // 出力 (custom disc) — 取り出し専用
        addSlot(new OutputSlot(blockEntity, MusicDiscMakerBlockEntity.SLOT_OUTPUT, 114, 47));

        // プレイヤーインベントリ 3 行 (200幅パネルで中央寄せ: x=19)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 19 + col * 18, 82 + row * 18));
            }
        }
        // ホットバー
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 19 + col * 18, 140));
        }
    }

    private static MusicDiscMakerBlockEntity resolve(Inventory playerInventory, BlockPos pos) {
        final var be = playerInventory.player.level().getBlockEntity(pos);
        if (be instanceof MusicDiscMakerBlockEntity maker) {
            return maker;
        }
        throw new IllegalStateException("Music Disc Maker BlockEntity が見つからない");
    }

    public MusicDiscMakerBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Override
    public boolean stillValid(Player player) {
        return blockEntity.getLevel() != null
                && blockEntity.getLevel().getBlockState(blockEntity.getBlockPos()).is(ModBlocks.MUSIC_DISC_MAKER.get())
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

        final int blockSlots = 2;
        final int playerStart = blockSlots;
        final int playerEnd = slots.size();

        if (index < blockSlots) {
            // ブロック → プレイヤー
            if (!moveItemStackTo(stack, playerStart, playerEnd, true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickCraft(stack, original);
        } else {
            // プレイヤー → 入力スロット (空ディスクのみ)
            if (!moveItemStackTo(stack, MusicDiscMakerBlockEntity.SLOT_INPUT, MusicDiscMakerBlockEntity.SLOT_INPUT + 1, false)) {
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

    /** 出力スロット: プレイヤーは挿入不可、取り出しのみ。 */
    private static class OutputSlot extends Slot {
        OutputSlot(MusicDiscMakerBlockEntity be, int slot, int x, int y) {
            super(be, slot, x, y);
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            return false;
        }
    }
}
