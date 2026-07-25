package com.kuronami.musicdiscmaker.menu;

import org.jetbrains.annotations.NotNull;

import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * スピーカーの設定 menu。スロットを 1 つも持たない (音量・可聴範囲の 2 本のスライダーだけの画面)。
 * リンク操作は GUI に載せない (リンクはブロックアイテムのシフト右クリック)。
 */
public class SpeakerMenu extends AbstractContainerMenu {

    private final SpeakerBlockEntity blockEntity;

    // client 側コンストラクタ (extended MenuType 経由で BlockPos を受け取る)
    public SpeakerMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        this(containerId, resolve(playerInventory, pos));
    }

    // server 側コンストラクタ
    public SpeakerMenu(int containerId, SpeakerBlockEntity blockEntity) {
        super(ModMenus.SPEAKER.get(), containerId);
        this.blockEntity = blockEntity;
    }

    private static SpeakerBlockEntity resolve(Inventory playerInventory, BlockPos pos) {
        if (playerInventory.player.level().getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
            return speaker;
        }
        throw new IllegalStateException("Speaker BlockEntity が見つからない");
    }

    public SpeakerBlockEntity getBlockEntity() {
        return blockEntity;
    }

    @Override
    public boolean stillValid(Player player) {
        return blockEntity.getLevel() != null
                && blockEntity.getLevel().getBlockState(blockEntity.getBlockPos()).is(ModBlocks.SPEAKER.get())
                && player.distanceToSqr(
                        blockEntity.getBlockPos().getX() + 0.5,
                        blockEntity.getBlockPos().getY() + 0.5,
                        blockEntity.getBlockPos().getZ() + 0.5) <= 64.0;
    }

    @Override
    public @NotNull ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY; // スロットを持たない
    }
}
