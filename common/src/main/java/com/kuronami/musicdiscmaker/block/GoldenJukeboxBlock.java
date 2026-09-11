package com.kuronami.musicdiscmaker.block;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//? if >=1.21.2 {
import net.minecraft.server.level.ServerLevel;
//?} else {
//?}
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
//? if >=1.21.2 {
//?} elif >=1.21 {
/*import net.minecraft.world.ItemInteractionResult;
*///?} else {
//?}
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
//? if >=1.21 {
//?} else {
/*import net.minecraft.world.item.RecordItem;
 *///?}
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 強化版ジュークボックス。バニラ jukebox の上位互換で、ブロックごとに可聴範囲・音量・リピート・
 * 再生/停止を設定 GUI で調整できる。
 *
 * <p>手にディスクを持って右クリック → 挿入して再生 (vanilla 同等)。空手 (またはディスク以外を持って)
 * 右クリック → 設定 GUI。ディスクの取り出しは GUI 内スロットで行う。コンパレータ出力・再生中の
 * 通常信号は出さず、比較器で空0・停止/pause1・再生15を返す。
 */
public class GoldenJukeboxBlock extends Block implements EntityBlock {

    public static final BooleanProperty HAS_RECORD = BlockStateProperties.HAS_RECORD;

    public GoldenJukeboxBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HAS_RECORD, Boolean.FALSE)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HAS_RECORD, BlockStateProperties.HORIZONTAL_FACING);
    }

    /**
     * 上面テクスチャの溝がプレイヤーから見て左右に走るように置く。
     *
     * <p>{@code golden_jukebox_top.png} の黒い溝は {@code facing} の軸に沿って走るので、
     * かまど型の {@code getOpposite()} をそのまま使うと溝がプレイヤーから見て奥行き方向に伸びる。
     * 90 度ずらして溝を左右向きにする。<b>blockstate の y 対応 ({@code facing=north} → y=0) は
     * 変えない</b>。ここを動かすと {@code facing} を持たなかった版から読み込まれた既存の設置物
     * (すべて {@code facing=north} に倒れる) が一斉に 90 度回る。
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,
                context.getHorizontalDirection().getOpposite().getClockWise());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GoldenJukeboxBlockEntity(pos, state);
    }

    @Nullable
    @Override
    //? if >=1.21.2 {
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || !state.getValue(HAS_RECORD)) {
    //?} elif >=1.21 {
    /*public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || !state.getValue(HAS_RECORD)) {
    *///?} else {
    /*public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide || !state.getValue(HAS_RECORD)) {
    *///?}
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.GOLDEN_JUKEBOX.get(),
                GoldenJukeboxBlockEntity::serverTick);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> createTickerHelper(
            BlockEntityType<A> actual, BlockEntityType<E> expected, BlockEntityTicker<? super E> ticker) {
        return expected == actual ? (BlockEntityTicker<A>) ticker : null;
    }

    @Override
    //? if >=1.21.2 {
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        // 手にディスク (またはアルバム) & スロット空 → 挿入 (vanilla 同等)。
        // それ以外は空手インタラクション (= GUI) へ流す。
        if (GoldenJukeboxBlockEntity.isPlayableInSlot(stack)
    //?} elif >=1.21 {
    /*protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        // 手にディスク (またはアルバム) & スロット空 → 挿入 (vanilla 同等)。
        // それ以外は useWithoutItem (= GUI) へ流す。
        if (GoldenJukeboxBlockEntity.isPlayableInSlot(stack)
    *///?} else {
    /*public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        final ItemStack held = player.getItemInHand(hand);
        // 手にディスク & スロット空 → 挿入 (vanilla 同等)。
        if (held.getItem() instanceof RecordItem
    *///?}
                && level.getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity be
                && be.getDisc().isEmpty()) {
            //? if >=1.21.2 {
            if (!level.isClientSide()) {
                final ItemStack one = stack.consumeAndReturn(1, player);
                be.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, one);
            //?} elif >=1.21 {
            /*if (!level.isClientSide) {
                final ItemStack one = stack.consumeAndReturn(1, player);
                be.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, one);
            *///?} else {
            /*if (!level.isClientSide) {
                be.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, held.copyWithCount(1));
                if (!player.getAbilities().instabuild) {
                    held.shrink(1);
                }
            *///?}
                player.awardStat(Stats.PLAY_RECORD);
            }
            //? if >=1.21.2 {
            return InteractionResult.SUCCESS;
            //?} elif >=1.21 {
            /*return ItemInteractionResult.sidedSuccess(level.isClientSide);
            *///?} else {
            /*return InteractionResult.sidedSuccess(level.isClientSide);
            *///?}
        }
        //? if >=1.21.2 {
        return InteractionResult.TRY_WITH_EMPTY_HAND;
        //?} elif >=1.21 {
        /*return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        *///?} else {
        /*// それ以外 → 設定 GUI。
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity) {
            Services.MENU.openGoldenJukeboxMenu(serverPlayer, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
        *///?}
    }

    @Override
    //? if >=1.21.2 {
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            // extended menu (BlockPos を client ctor へ運ぶ) の open は loader 固有 → SPI 経由。
            Services.MENU.openGoldenJukeboxMenu(serverPlayer, pos);
    //?} elif >=1.21 {
    /*protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity) {
            Services.MENU.openGoldenJukeboxMenu(serverPlayer, pos);
    *///?} else {
    /*public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity be) {
                be.onBlockRemoved();
                Containers.dropContents(level, pos, be);
            }
    *///?}
        }
        //? if >=1.21.2 {
        return InteractionResult.SUCCESS;
        //?} elif >=1.21 {
        /*return InteractionResult.sidedSuccess(level.isClientSide);
        *///?} else {
        /*super.onRemove(state, level, pos, newState, movedByPiston);
        *///?}
    }

    @Override
    //? if >=1.21.2 {
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
        if (level.getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity be) {
            be.onBlockRemoved();
            Containers.dropContents(level, pos, be);
        }
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
    //?} elif >=1.21 {
    /*protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity be) {
                be.onBlockRemoved();
                Containers.dropContents(level, pos, be);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
    *///?} else {
    /*public boolean hasAnalogOutputSignal(BlockState state) {
    *///?}
        return true;
    }

    @Override
    //? if >=1.21.2 {
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos, Direction direction) {
    //?} elif >=1.21 {
    /*protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
    *///?} else {
    /*public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
    *///?}
        return level.getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity be ? be.getComparatorOutput() : 0;
    }

    @Override
    //? if >=1.21 {
    protected boolean isSignalSource(BlockState state) {
    //?} else {
    /*public boolean isSignalSource(BlockState state) {
    *///?}
        return true;
    }

    /** Golden itself never powers adjacent automation; state is exposed through the comparator. */
    @Override
    //? if >=1.21 {
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
    //?} else {
    /*public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
    *///?}
        return 0;
    }
}

