package com.kuronami.musicdiscmaker.menu;

import org.jetbrains.annotations.NotNull;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
//? if >=1.21 {
//?} else {
/*import com.kuronami.musicdiscmaker.client.GoldenJukeboxLayout;
*///?}
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
//? if >=1.21 {
//?} else {
/*import net.minecraft.world.item.RecordItem;
*///?}

/**
 * 強化版ジュークボックスの設定 menu。ディスクスロット 1 枠 + プレイヤーインベントリ。
 * 範囲/音量/リピート/再生停止は widget として Screen 側が描画し、C2S で BE に反映する。
 */
public class GoldenJukeboxMenu extends AbstractContainerMenu {

    private final GoldenJukeboxBlockEntity blockEntity;

    //? if >=1.21.2 {
    // client 側コンストラクタ (IMenuTypeExtension 経由で BlockPos を受け取る)
    public GoldenJukeboxMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        this(containerId, playerInventory, resolve(playerInventory, buf));
    //?} else {
    /*// client 側コンストラクタ (extended MenuType 経由で BlockPos を受け取る)
    public GoldenJukeboxMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        this(containerId, playerInventory, resolve(playerInventory, pos));
    *///?}
    }

    // server 側コンストラクタ
    public GoldenJukeboxMenu(int containerId, Inventory playerInventory, GoldenJukeboxBlockEntity blockEntity) {
        super(ModMenus.GOLDEN_JUKEBOX.get(), containerId);
        this.blockEntity = blockEntity;

        // スロット座標は branding/gen_golden_jukebox_gui.py (レイアウトの正本) の
        // addSlot 値と一致させる。バニラ標準式 (imageHeight=242 → row0=160, hotbar=218)
        // でテクスチャのスロット枠 (frame = addSlot-1 の 18x18) と 1px 単位で整合する。

        // ディスクスロット (再生可能ディスクのみ)
        //? if >=1.21 {
        addSlot(new DiscSlot(blockEntity, GoldenJukeboxBlockEntity.SLOT_DISC, 8, 18));
        //?} else {
        /*addSlot(new DiscSlot(blockEntity, GoldenJukeboxBlockEntity.SLOT_DISC,
                GoldenJukeboxLayout.DISC_X, GoldenJukeboxLayout.DISC_Y));
        *///?}

        // プレイヤーインベントリ 3 行
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                //? if >=1.21 {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 160 + row * 18));
                //?} else {
                /*addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        GoldenJukeboxLayout.INV_X + col * 18, GoldenJukeboxLayout.INV_Y0 + row * 18));
                *///?}
            }
        }
        // ホットバー
        for (int col = 0; col < 9; col++) {
            //? if >=1.21 {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 218));
            //?} else {
            /*addSlot(new Slot(playerInventory, col,
                    GoldenJukeboxLayout.INV_X + col * 18, GoldenJukeboxLayout.HOT_Y));
            *///?}
        }
    }

    //? if >=1.21.2 {
    private static GoldenJukeboxBlockEntity resolve(Inventory playerInventory, RegistryFriendlyByteBuf buf) {
        final var be = playerInventory.player.level().getBlockEntity(buf.readBlockPos());
    //?} else {
    /*private static GoldenJukeboxBlockEntity resolve(Inventory playerInventory, BlockPos pos) {
        final var be = playerInventory.player.level().getBlockEntity(pos);
    *///?}
        if (be instanceof GoldenJukeboxBlockEntity jukebox) {
            return jukebox;
        }
        //? if >=1.21.2 {
        throw new IllegalStateException("Enhanced Jukebox BlockEntity not found");
        //?} elif >=1.21 {
        /*throw new IllegalStateException("Enhanced Jukebox BlockEntity not found");
        *///?} else {
        /*throw new IllegalStateException("Enhanced Jukebox BlockEntity not found");
        *///?}
    }

    public GoldenJukeboxBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Override
    public boolean stillValid(Player player) {
        return blockEntity.getLevel() != null
                && blockEntity.getLevel().getBlockState(blockEntity.getBlockPos()).is(ModBlocks.GOLDEN_JUKEBOX.get())
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
            //? if >=1.21 {
            if (!moveItemStackTo(stack, GoldenJukeboxBlockEntity.SLOT_DISC, GoldenJukeboxBlockEntity.SLOT_DISC + 1, false)) {
            //?} else {
            /*if (!moveItemStackTo(stack, GoldenJukeboxBlockEntity.SLOT_DISC,
                    GoldenJukeboxBlockEntity.SLOT_DISC + 1, false)) {
            *///?}
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

    /** ディスクスロット: 再生可能ディスク (JUKEBOX_PLAYABLE) とアルバムのみ受け入れる。 */
    private static class DiscSlot extends Slot {
        DiscSlot(GoldenJukeboxBlockEntity be, int slot, int x, int y) {
            super(be, slot, x, y);
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            //? if >=1.21 {
            return GoldenJukeboxBlockEntity.isPlayableInSlot(stack);
            //?} else {
            /*return stack.getItem() instanceof RecordItem;
            *///?}
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}

