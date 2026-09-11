package com.kuronami.musicdiscmaker.menu;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.minecraft.core.BlockPos;
//? if >=1.21.2 {
import net.minecraft.network.RegistryFriendlyByteBuf;
//?} else {
//?}
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;

/** 固定speakerの音量だけを変更する最小menu。clientはDataSlot値だけを読む。 */
public final class SpeakerMenu extends AbstractContainerMenu {

    /** vanilla button packetのsigned byte帯を越えないGUI値。実音量はこの値の2倍。 */
    public static final int VOLUME_STEPS = 100;

    private final BlockPos pos;
    @Nullable
    private final SpeakerBlockEntity blockEntity;
    private final DataSlot volume;
    private final DataSlot muted;

    //? if >=1.21.2 {
    public SpeakerMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(containerId, inventory, buf.readBlockPos());
    }
    //?} else {
    //?}

    /** Fabricを含む全帯のclient側extended menu ctor。client BEの到着を待たない。 */
    public SpeakerMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(ModMenus.SPEAKER.get(), containerId);
        this.pos = pos.immutable();
        this.blockEntity = null;
        this.volume = addDataSlot(new ClientDataSlot());
        this.muted = addDataSlot(new ClientDataSlot());
    }

    /** server側ctor。 */
    public SpeakerMenu(int containerId, Inventory inventory, SpeakerBlockEntity blockEntity) {
        super(ModMenus.SPEAKER.get(), containerId);
        this.pos = blockEntity.getBlockPos().immutable();
        this.blockEntity = blockEntity;
        this.volume = addDataSlot(new DataSlot() {
            @Override public int get() { return blockEntity.getVolumePercent(); }
            @Override public void set(int value) { blockEntity.setVolumePercent(value); }
        });
        this.muted = addDataSlot(new DataSlot() {
            @Override public int get() { return blockEntity.isMuted() ? 1 : 0; }
            @Override public void set(int value) { }
        });
    }

    public int getVolumePercent() {
        return volume.get();
    }

    public boolean isMuted() {
        return muted.get() != 0;
    }

    public BlockPos getBlockPos() {
        return pos;
    }

    @Override
    public boolean stillValid(Player player) {
        return blockEntity != null && player.level() == blockEntity.getLevel()
                && player.level().getBlockState(pos).is(ModBlocks.SPEAKER.get())
                && player.level().getBlockEntity(pos) == blockEntity
                && player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D;
    }

    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (button < 0 || button > VOLUME_STEPS
                || player.containerMenu != this || !stillValid(player)) {
            return false;
        }
        blockEntity.setVolumePercent(button * 2);
        return true;
    }

    @Override
    public @NotNull ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    private static final class ClientDataSlot extends DataSlot {
        private int value;
        @Override public int get() { return value; }
        @Override public void set(int value) { this.value = value; }
    }
}
