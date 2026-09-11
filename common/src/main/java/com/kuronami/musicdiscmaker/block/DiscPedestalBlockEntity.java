package com.kuronami.musicdiscmaker.block;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModItems;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.register.ModDataComponents;
//?} else {
/*import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
*///?}

import net.minecraft.core.BlockPos;
//? if >=1.21 {
import net.minecraft.core.HolderLookup;
//?}
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
//? if >=1.21.2 {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?}

/**
 * ディスクを飾る台座の BlockEntity。<b>ディスクを 1 枚だけ</b>持つ。
 *
 * <p>中身は client にも同期する ({@link #getUpdateTag} / {@link #getUpdatePacket})。
 * ブームボックスと違い、この BlockEntity は<b>見た目そのものが中身で決まる</b>ので、
 * client が中身を知らないと盤も曲名も出ない。
 *
 * <p>持つのはディスクのアイテムだけで、曲名は<b>ディスク側の {@link CustomTrackData}</b> から読む
 * ({@link #storedTitle()})。プレイリストディスクも列の先頭の曲を {@code CUSTOM_TRACK} に持っているので、
 * 単曲とプレイリストで読み口が分かれない。
 */
public class DiscPedestalBlockEntity extends BlockEntity {

    /** 保存は 1 スロットの inventory として書く ({@link ContainerHelper} を帯ごとに使い分けられる)。 */
    private final NonNullList<ItemStack> stored = NonNullList.withSize(1, ItemStack.EMPTY);

    public DiscPedestalBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DISC_PEDESTAL.get(), pos, state);
    }

    /** 飾ってあるディスク。空なら {@link ItemStack#EMPTY}。 */
    public ItemStack getStored() {
        return stored.get(0);
    }

    /**
     * 飾るディスクを差し替える。<b>server 側から呼ぶ</b>。
     *
     * <p>{@code setChanged} だけでは client に届かない (chunk を読み直すまで見た目が変わらない) ので、
     * ここで {@code sendBlockUpdated} まで撃つ。
     */
    public void setStored(ItemStack stack) {
        stored.set(0, stack);
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    /** 撤去時のドロップ用。{@link ContainerHelper} と同じ形で渡せるように list を返す。 */
    public NonNullList<ItemStack> contentsForDrop() {
        return stored;
    }

    /**
     * 台の下に出す曲名。<b>飾っていない / 曲メタを持たないディスクなら空文字</b>。
     *
     * @return 曲名。無ければ空文字
     */
    public String storedTitle() {
        return titleOf(getStored());
    }

    /** 台の下に出すアーティスト名。曲メタに無ければ空文字。 */
    public String storedAuthor() {
        return authorOf(getStored());
    }

    /**
     * ディスクのスタックから曲名を読む。
     *
     * <p>MDM のディスクだけを見る。バニラのレコードは曲メタを持たないので空文字になる。
     *
     * @param stack 読み出し元
     * @return 曲名。無ければ空文字
     */
    public static String titleOf(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(ModItems.CUSTOM_MUSIC_DISC.get())) {
            return "";
        }
        //? if >=1.21 {
        final CustomTrackData track = stack.get(ModDataComponents.CUSTOM_TRACK.get());
        //?} else {
        /*final CustomTrackData track = CustomMusicDiscItem.getTrack(stack);
        *///?}
        if (track == null || track.isEmpty() || track.title().isBlank()) {
            return "";
        }
        return track.title();
    }

    /**
     * ディスクのスタックからアーティスト名を読む。
     *
     * <p>曲名と同じく MDM のカスタムディスクだけを見る。バニラの盤に作者名を捏造しない。
     *
     * @param stack 読み出し元
     * @return アーティスト名。無ければ空文字
     */
    public static String authorOf(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(ModItems.CUSTOM_MUSIC_DISC.get())) {
            return "";
        }
        //? if >=1.21 {
        final CustomTrackData track = stack.get(ModDataComponents.CUSTOM_TRACK.get());
        //?} else {
        /*final CustomTrackData track = CustomMusicDiscItem.getTrack(stack);
        *///?}
        if (track == null || track.isEmpty() || track.author().isBlank()) {
            return "";
        }
        return track.author();
    }

    /** 台座に載せられるスタックか (MDM のカスタムディスクだけ。プレイリストディスクも同じアイテム)。 */
    public static boolean accepts(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.CUSTOM_MUSIC_DISC.get());
    }

    // ── persistence ──

    @Override
    //? if >=1.21.2 {
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        stored.clear();
        ContainerHelper.loadAllItems(input, stored);
    }
    //?} elif >=1.21 {
    /*protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        stored.clear();
        ContainerHelper.loadAllItems(tag.getCompound("pedestal"), stored, registries);
    }
    *///?} else {
    /*// 1.20.1 に loadAdditional は無く、読み出しの口は public な load(CompoundTag) 1 本。
    public void load(CompoundTag tag) {
        super.load(tag);
        stored.clear();
        ContainerHelper.loadAllItems(tag.getCompound("pedestal"), stored);
    }
    *///?}

    @Override
    //? if >=1.21.2 {
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, stored);
    }
    //?} elif >=1.21 {
    /*protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        final CompoundTag pedestalTag = new CompoundTag();
        ContainerHelper.saveAllItems(pedestalTag, stored, registries);
        tag.put("pedestal", pedestalTag);
    }
    *///?} else {
    /*protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        final CompoundTag pedestalTag = new CompoundTag();
        ContainerHelper.saveAllItems(pedestalTag, stored);
        tag.put("pedestal", pedestalTag);
    }
    *///?}

    // ── client 同期 ──

    @Override
    //? if >=1.21.2 {
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }
    //?} elif >=1.21 {
    /*public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        final CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }
    *///?} else {
    /*public CompoundTag getUpdateTag() {
        final CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }
    *///?}

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
