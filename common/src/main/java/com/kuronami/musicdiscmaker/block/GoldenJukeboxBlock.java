package com.kuronami.musicdiscmaker.block;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
 * redstone 信号 (15) はバニラ jukebox と同等。
 */
public class GoldenJukeboxBlock extends Block implements EntityBlock {

    public static final BooleanProperty HAS_RECORD = BlockStateProperties.HAS_RECORD;

    public GoldenJukeboxBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HAS_RECORD, Boolean.FALSE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HAS_RECORD);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GoldenJukeboxBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || !state.getValue(HAS_RECORD)) {
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
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        // 手にディスク (またはアルバム) & スロット空 → 挿入 (vanilla 同等)。
        // それ以外は useWithoutItem (= GUI) へ流す。
        if (GoldenJukeboxBlockEntity.isPlayableInSlot(stack)
                && level.getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity be
                && be.getDisc().isEmpty()) {
            if (!level.isClientSide) {
                final ItemStack one = stack.consumeAndReturn(1, player);
                be.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, one);
                player.awardStat(Stats.PLAY_RECORD);
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity) {
            Services.MENU.openGoldenJukeboxMenu(serverPlayer, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
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
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity be ? be.getComparatorOutput() : 0;
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return level.getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity be && be.isVanillaPlaying() ? 15 : 0;
    }
}
