package com.kuronami.musicdiscmaker.menu;

import org.jetbrains.annotations.NotNull;

import com.kuronami.musicdiscmaker.block.DiscDyeingTableBlockEntity;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModMenus;

//? if >=1.21.2 {
import net.minecraft.network.RegistryFriendlyByteBuf;
//?} else {
/*import net.minecraft.core.BlockPos;
*///?}
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Disc Dyeing Table の menu。入力ディスク 1 / 染料 2 / 出力 1 + プレイヤーインベントリ。
 *
 * <p><b>座標は「テクスチャの枠 + 1」。</b>確定表 (MDM_DECISIONS.md「染色ブロック GUI の配置が
 * 確定した」) が載せているのは 18x18 の枠の左上で、{@code addSlot} が要るのはその内側 16x16 の
 * 左上なので 1px ずれる。枠 (14,35) → addSlot (15,36) の関係で全スロットが並んでいる。
 * 確定表と見比べて 1 を引き算し直さないこと。
 */
public class DiscDyeingTableMenu extends AbstractContainerMenu {

    /** ブロック側スロット数 (入力・染料 2・出力)。 */
    private static final int BLOCK_SLOTS = 4;

    private final DiscDyeingTableBlockEntity blockEntity;

    //? if >=1.21.2 {
    // client 側コンストラクタ (IMenuTypeExtension 経由で BlockPos を受け取る)
    public DiscDyeingTableMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory, resolve(playerInventory, buf));
    //?} else {
    /*// client 側コンストラクタ (extended MenuType 経由で BlockPos を受け取る)
    public DiscDyeingTableMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        this(containerId, playerInventory, resolve(playerInventory, pos));
    *///?}
    }

    // server 側コンストラクタ
    public DiscDyeingTableMenu(int containerId, Inventory playerInventory, DiscDyeingTableBlockEntity blockEntity) {
        super(ModMenus.DISC_DYEING_TABLE.get(), containerId);
        this.blockEntity = blockEntity;

        // 入力 (custom disc)
        addSlot(new FilteredSlot(blockEntity, DiscDyeingTableBlockEntity.SLOT_INPUT, 15, 36));
        // 染料 左=盤面色 / 右=アクセント色
        addSlot(new FilteredSlot(blockEntity, DiscDyeingTableBlockEntity.SLOT_DYE_BOARD, 69, 79));
        addSlot(new FilteredSlot(blockEntity, DiscDyeingTableBlockEntity.SLOT_DYE_ACCENT, 91, 79));
        // 出力 — 取り出し専用
        addSlot(new ResultSlot(blockEntity, DiscDyeingTableBlockEntity.SLOT_OUTPUT, 143, 36));

        // プレイヤーインベントリ 3 行
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 113 + row * 18));
            }
        }
        // ホットバー
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 171));
        }
    }

    //? if >=1.21.2 {
    private static DiscDyeingTableBlockEntity resolve(Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        final var be = playerInventory.player.level().getBlockEntity(buf.readBlockPos());
    //?} else {
    /*private static DiscDyeingTableBlockEntity resolve(Inventory playerInventory, BlockPos pos) {
        final var be = playerInventory.player.level().getBlockEntity(pos);
    *///?}
        if (be instanceof DiscDyeingTableBlockEntity table) {
            return table;
        }
        throw new IllegalStateException("Disc Dyeing Table BlockEntity not found");
    }

    public DiscDyeingTableBlockEntity getBlockEntity() {
        return blockEntity;
    }

    /** プレビュー窓に出す完成見本。片側だけでも、その領域を反映した出力になる。 */
    public ItemStack getPreviewStack() {
        return blockEntity.getItem(DiscDyeingTableBlockEntity.SLOT_OUTPUT);
    }

    @Override
    public boolean stillValid(Player player) {
        return blockEntity.getLevel() != null
                && blockEntity.getLevel().getBlockState(blockEntity.getBlockPos())
                        .is(ModBlocks.DISC_DYEING_TABLE.get())
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

        if (index < BLOCK_SLOTS) {
            // ブロック → プレイヤー
            if (!moveItemStackTo(stack, BLOCK_SLOTS, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickCraft(stack, original);
        } else if (blockEntity.canPlaceItem(DiscDyeingTableBlockEntity.SLOT_INPUT, stack)) {
            // プレイヤー → 入力
            if (!moveItemStackTo(stack, DiscDyeingTableBlockEntity.SLOT_INPUT,
                    DiscDyeingTableBlockEntity.SLOT_INPUT + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else if (blockEntity.canPlaceItem(DiscDyeingTableBlockEntity.SLOT_DYE_BOARD, stack)) {
            // プレイヤー → 染料 2 枠。先に空枠を埋め、同色でも左への合流で右を塞がない。
            if (!moveDyeToTable(stack)) {
                return ItemStack.EMPTY;
            }
        } else {
            return ItemStack.EMPTY;
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

    private boolean moveDyeToTable(ItemStack stack) {
        if (!getSlot(DiscDyeingTableBlockEntity.SLOT_DYE_BOARD).hasItem()
                && moveItemStackTo(stack, DiscDyeingTableBlockEntity.SLOT_DYE_BOARD,
                        DiscDyeingTableBlockEntity.SLOT_DYE_BOARD + 1, false)) {
            return true;
        }
        if (!getSlot(DiscDyeingTableBlockEntity.SLOT_DYE_ACCENT).hasItem()
                && moveItemStackTo(stack, DiscDyeingTableBlockEntity.SLOT_DYE_ACCENT,
                        DiscDyeingTableBlockEntity.SLOT_DYE_ACCENT + 1, false)) {
            return true;
        }
        return moveItemStackTo(stack, DiscDyeingTableBlockEntity.SLOT_DYE_BOARD,
                DiscDyeingTableBlockEntity.SLOT_DYE_ACCENT + 1, false);
    }

    /** 入力・染料スロット: 受け入れ規則は BlockEntity 側 ({@code canPlaceItem}) が単一の正本。 */
    private static class FilteredSlot extends Slot {

        private final DiscDyeingTableBlockEntity table;
        private final int slotIndex;

        FilteredSlot(DiscDyeingTableBlockEntity be, int slot, int x, int y) {
            super(be, slot, x, y);
            this.table = be;
            this.slotIndex = slot;
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            return table.canPlaceItem(slotIndex, stack);
        }
    }

    /**
     * 出力スロット: プレイヤーは挿入不可、取り出しのみ。取り出したら入力を 1 減らす
     * (減らさないとディスクが無限に増える)。
     */
    private static class ResultSlot extends Slot {

        private final DiscDyeingTableBlockEntity table;

        ResultSlot(DiscDyeingTableBlockEntity be, int slot, int x, int y) {
            super(be, slot, x, y);
            this.table = be;
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            return false;
        }

        @Override
        public void onTake(@NotNull Player player, @NotNull ItemStack stack) {
            super.onTake(player, stack);
            table.onResultTaken();
        }
    }
}
