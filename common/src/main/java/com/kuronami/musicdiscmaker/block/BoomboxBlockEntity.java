package com.kuronami.musicdiscmaker.block;

import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModItems;
//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
//?}

import net.minecraft.core.BlockPos;
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
import net.minecraft.world.ContainerHelper;
//? if >=1.21.2 {
import net.minecraft.world.Containers;
import net.minecraft.server.level.ServerLevel;
//?}
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
//? if >=1.21.2 {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?}

/**
 * 設置されたブームボックスの BlockEntity。
 *
 * <p><b>持っているのは「置かれたブームボックスのアイテムそのもの」1 個</b>で、ディスクは
 * そのアイテムの {@code BOOMBOX_CONTENTS} component の中に居る。設置は器ごと置く・撤去は器ごと
 * 返す、という 1 本の筋にしてあるので、中身の移し替えが要らない
 * (先行実装の Sophisticated Backpacks と同じ形。設計上の指摘 2026-09-07)。
 *
 * <p>{@link net.minecraft.world.Container} は実装していない。ホッパーで抜き差しさせる設計判断が
 * まだ無いから。1.21.5+ は {@code preRemoveSideEffects} が BlockEntity を外す前に呼ばれるため
 * このクラスが明示して落とし、旧版は {@link BoomboxBlock} の {@code onRemove} が落とす。
 */
public class BoomboxBlockEntity extends BlockEntity {

    /**
     * 取っ手が立ち切る / 倒れ切るまでの時間 (ms)。<b>0 にすると瞬時に切り替わる</b>ので、
     * 「アニメーションさせるか、パチッと切り替えるか」はこの 1 箇所で決まる。
     */
    public static final long HANDLE_FALL_MILLIS = 200L;

    /** 保存は 1 スロットの inventory として書く ({@link ContainerHelper} を帯ごとに使い分けられる)。 */
    private final NonNullList<ItemStack> stored = NonNullList.withSize(1, ItemStack.EMPTY);

    /**
     * client 専用: 取っ手の倒れ具合 (0 = 立つ / 1 = 倒れる)。BER が毎フレーム進める。
     *
     * <p>保存も同期もしない。<b>「いま鳴っているか」は client の音声側だけが知っている</b>ので
     * (BlockEntity は client へ同期していない)、見た目の状態も client にしか存在しない。
     */
    private float handleFall;

    /** {@link #handleFall} を最後に進めた時刻 (ms)。0 = 未初期化。 */
    private long handleFallMillis;

    public BoomboxBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BOOMBOX.get(), pos, state);
    }

    /**
     * 取っ手の倒れ具合を 1 フレームぶん進めて返す (client の BER から呼ぶ)。
     *
     * <p>tick ではなく実時間で進めるのは、呼び元が描画側だから。読み込み直後の 1 回目は補間せず
     * 現在の状態へ確定させる — chunk が視界に入った瞬間に取っ手が倒れ始めるのは事実と違う。
     *
     * @param down 倒れる側へ向かうか (再生中なら true)
     * @return 0 = 立つ / 1 = 倒れる
     */
    public float advanceHandleFall(boolean down) {
        final long now = System.currentTimeMillis();
        final long previous = handleFallMillis;
        handleFallMillis = now;
        if (previous == 0L || HANDLE_FALL_MILLIS <= 0L) {
            handleFall = down ? 1.0F : 0.0F;
            return handleFall;
        }
        final float step = Math.min(1.0F, (now - previous) / (float) HANDLE_FALL_MILLIS);
        handleFall = down ? Math.min(1.0F, handleFall + step) : Math.max(0.0F, handleFall - step);
        return handleFall;
    }

    /** 置かれているブームボックスのアイテム。撤去時にこれをそのまま返す。 */
    public ItemStack getStored() {
        return stored.get(0);
    }

    /** 設置時に、手に持っていたスタックの 1 個分を器として預ける。 */
    public void setStored(ItemStack stack) {
        stored.set(0, stack);
        setChanged();
    }

    /** 撤去時のドロップ用。{@link ContainerHelper} と同じ形で渡せるように list を返す。 */
    public NonNullList<ItemStack> contentsForDrop() {
        return stored;
    }

    //? if >=1.21.2 {
    /** BlockEntity が level から外れる前に、設置元を照合して停止し、本体を 1 個返す。 */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level instanceof ServerLevel serverLevel) {
            BoomboxPlayback.stopPlaced(serverLevel, pos, this);
            Containers.dropContents(serverLevel, pos, contentsForDrop());
        }
        super.preRemoveSideEffects(pos, state);
    }
    //?}

    // ── persistence ──

    @Override
    //? if >=1.21.2 {
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        stored.clear();
        ContainerHelper.loadAllItems(input, stored);
    //?} elif >=1.21 {
    /*protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        stored.clear();
        ContainerHelper.loadAllItems(tag.getCompound("boombox"), stored, registries);
    *///?} else {
    /*// 1.20.1 に loadAdditional は無く、読み出しの口は public な load(CompoundTag) 1 本。
    public void load(CompoundTag tag) {
        super.load(tag);
        stored.clear();
        ContainerHelper.loadAllItems(tag.getCompound("boombox"), stored);
    *///?}
        // 保存データが空 = 旧セーブか壊れた保存。器が消えるとブロックを壊しても何も返らないので、
        // 素のブームボックスを 1 個入れて最低限の器を保証する。
        if (stored.get(0).isEmpty()) {
            stored.set(0, new ItemStack(ModItems.BOOMBOX.get()));
        }
    }

    @Override
    //? if >=1.21.2 {
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, stored);
    //?} elif >=1.21 {
    /*protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        final CompoundTag boomboxTag = new CompoundTag();
        ContainerHelper.saveAllItems(boomboxTag, stored, registries);
        tag.put("boombox", boomboxTag);
    *///?} else {
    /*protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        final CompoundTag boomboxTag = new CompoundTag();
        ContainerHelper.saveAllItems(boomboxTag, stored);
        tag.put("boombox", boomboxTag);
    *///?}
    }
}
