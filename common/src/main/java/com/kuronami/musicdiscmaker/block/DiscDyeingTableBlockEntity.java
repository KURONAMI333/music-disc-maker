package com.kuronami.musicdiscmaker.block;

import org.jetbrains.annotations.NotNull;

import com.kuronami.musicdiscmaker.color.DiscDye;
import com.kuronami.musicdiscmaker.component.DiscDyeData;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
//? if >=26.1 {
import net.minecraft.core.component.DataComponents;
//?} else {
//?}
import net.minecraft.core.Direction;
//? if >=1.21.2 {
//?} elif >=1.21 {
/*import net.minecraft.core.HolderLookup;
*///?} else {
//?}
import net.minecraft.core.NonNullList;
//? if >=1.21.2 {
//?} else {
/*import net.minecraft.nbt.CompoundTag;
*///?}
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
//? if >=1.21.2 {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?} else {
//?}

/**
 * Disc Dyeing Table の BlockEntity。入力ディスク 1 / 染料 2 (盤面色・アクセント色) / 出力 1 の 4 スロット。
 *
 * <p>出力は {@link #updateResult()} が入力から組み直す見本で、取り出した時だけ入力が 1 減る
 * (バニラの作業台と同じ形)。
 *
 * <p>{@link WorldlyContainer} を実装して face slot を全方向で空にしている = <b>ホッパーは投入も
 * 取り出しもできない</b>。ホッパーは {@code Container#removeItem} を直接叩いて {@code Slot#onTake}
 * を通らないので、取り出しを開けると入力が減らないままディスクが増殖する。NeoForge 側でも
 * {@code Capabilities.Item.BLOCK} をこの BE 型に登録していない (登録は BE 型ごと)。
 */
public class DiscDyeingTableBlockEntity extends BlockEntity implements WorldlyContainer {

    public static final int SLOT_INPUT = 0;
    /** 盤面色の染料 (GUI 左)。 */
    public static final int SLOT_DYE_BOARD = 1;
    /** アクセント色の染料 (GUI 右)。 */
    public static final int SLOT_DYE_ACCENT = 2;
    public static final int SLOT_OUTPUT = 3;
    private static final int SIZE = 4;

    /** face slot 無し = ホッパー連携を開けない (クラス javadoc)。 */
    private static final int[] NO_SLOTS = new int[0];

    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    public DiscDyeingTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DISC_DYEING_TABLE.get(), pos, state);
    }

    /**
     * 出力スロットの見本を入力から組み直す。
     *
     * <p>左は盤面、右はアクセントだけを上書きする。空の側は入力ディスクの染色値を保持し、
     * 未染色なら未染色のまま残す。結果は入力のコピーから作るため、曲データや表示名なども保持する。
     */
    private void updateResult() {
        final ItemStack input = items.get(SLOT_INPUT);
        if (input.isEmpty()) {
            items.set(SLOT_OUTPUT, ItemStack.EMPTY);
            return;
        }
        final ItemStack result = input.copyWithCount(1);
        final DiscDye board = dyeOf(items.get(SLOT_DYE_BOARD));
        final DiscDye accent = dyeOf(items.get(SLOT_DYE_ACCENT));
        if (board != null || accent != null) {
            CustomMusicDiscItem.setDye(result,
                    DiscDyeData.withOverrides(CustomMusicDiscItem.getDye(input), board, accent));
        }
        items.set(SLOT_OUTPUT, result);
    }

    /**
     * 染料アイテムから {@link DiscDye} を引く。染料でなければ {@code null}。
     *
     * <p>26.1 で {@code DyeItem} から色が外れ、{@code DataComponents.DYE} へ移った。
     * どちらの帯でもバニラ {@code DyeColor} の id 文字列 ("light_blue" 等) で {@code DiscDye} と
     * 1 対 1 に対応する。
     */
    private static DiscDye dyeOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        //? if >=26.1 {
        final DyeColor color = stack.get(DataComponents.DYE);
        //?} else {
        /*final DyeColor color = stack.getItem() instanceof DyeItem dyeItem ? dyeItem.getDyeColor() : null;
        *///?}
        return color == null ? null : DiscDye.byId(color.getName());
    }

    /**
     * 出力を取り出した時 (server)。入力を 1 減らし、<b>実際に使った側だけ</b>染料を 1 つ
     * 減らして見本を組み直す (染料は消費する = 2026-09-06 KURONAMI333 裁定。バニラの織機・革防具と揃える)。
     */
    public void onResultTaken() {
        final ItemStack input = items.get(SLOT_INPUT);
        final ItemStack boardDye = items.get(SLOT_DYE_BOARD);
        final ItemStack accentDye = items.get(SLOT_DYE_ACCENT);
        // 条件は updateResult() が各領域へ実際に焼いた条件と揃える。
        final boolean usedBoardDye = !input.isEmpty() && dyeOf(boardDye) != null;
        final boolean usedAccentDye = !input.isEmpty() && dyeOf(accentDye) != null;
        if (!input.isEmpty()) {
            input.shrink(1);
        }
        if (usedBoardDye) {
            boardDye.shrink(1);
        }
        if (usedAccentDye) {
            accentDye.shrink(1);
        }
        updateResult();
        setChanged();
    }

    /** 出力スロット以外が動いた時だけ見本を組み直す (組み直しは出力を直接書くので再入しない)。 */
    private void onContentsChanged(int slot) {
        if (slot != SLOT_OUTPUT) {
            updateResult();
        }
        setChanged();
    }

    // ── persistence ──

    @Override
    //? if >=1.21.2 {
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items.clear();
        ContainerHelper.loadAllItems(input, items);
    //?} elif >=1.21 {
    /*protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clear();
        ContainerHelper.loadAllItems(tag.getCompound("inventory"), items, registries);
    *///?} else {
    /*public void load(CompoundTag tag) {
        super.load(tag);
        items.clear();
        ContainerHelper.loadAllItems(tag.getCompound("inventory"), items);
    *///?}
        updateResult();
    }

    @Override
    //? if >=1.21.2 {
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
    //?} elif >=1.21 {
    /*protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        final CompoundTag invTag = new CompoundTag();
        ContainerHelper.saveAllItems(invTag, items, registries);
        tag.put("inventory", invTag);
    *///?} else {
    /*protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        final CompoundTag invTag = new CompoundTag();
        ContainerHelper.saveAllItems(invTag, items);
        tag.put("inventory", invTag);
    *///?}
    }

    // ── Container ──

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (final ItemStack stack : items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public @NotNull ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public @NotNull ItemStack removeItem(int slot, int amount) {
        final ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) {
            onContentsChanged(slot);
        }
        return removed;
    }

    @Override
    public @NotNull ItemStack removeItemNoUpdate(int slot) {
        final ItemStack removed = ContainerHelper.takeItem(items, slot);
        if (!removed.isEmpty()) {
            onContentsChanged(slot);
        }
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (!stack.isEmpty() && stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        onContentsChanged(slot);
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        // 入力は custom disc のみ (染めるのは MDM が作ったディスクの盤面とアクセント)。
        // 染料は 16 色の DyeItem。出力は挿入不可。
        return switch (slot) {
            case SLOT_INPUT -> stack.is(ModItems.CUSTOM_MUSIC_DISC.get());
            case SLOT_DYE_BOARD, SLOT_DYE_ACCENT -> stack.getItem() instanceof DyeItem;
            default -> false;
        };
    }

    @Override
    public void clearContent() {
        items.clear();
    }

    // ── WorldlyContainer: 全方向で face slot 無し = ホッパーは触れない ──

    @Override
    public int[] getSlotsForFace(Direction side) {
        return NO_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return false;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return false;
    }
}
