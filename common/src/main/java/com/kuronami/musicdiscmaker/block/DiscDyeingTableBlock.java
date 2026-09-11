package com.kuronami.musicdiscmaker.block;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.platform.Services;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
//? if >=1.21.2 {
//?} elif >=1.21 {
/*import net.minecraft.world.Containers;
*///?} else {
/*import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
*///?}
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
//? if >=1.21.2 {
import net.minecraft.world.level.block.state.properties.EnumProperty;
//?} else {
/*import net.minecraft.world.level.block.state.properties.DirectionProperty;
*///?}
import net.minecraft.world.phys.BlockHitResult;

/**
 * custom disc の盤面色とアクセント色を塗り替える作業台。右クリックで GUI を開く。
 * かまど型の向き付きブロック (正面が設置プレイヤーを向く)。
 */
public class DiscDyeingTableBlock extends Block implements EntityBlock {

    //? if >=1.21.2 {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    //?} else {
    /*public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    *///?}

    public DiscDyeingTableBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    //? if >=1.21 {
    protected BlockState rotate(BlockState state, Rotation rotation) {
    //?} else {
    /*public BlockState rotate(BlockState state, Rotation rotation) {
    *///?}
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    //? if >=1.21 {
    protected BlockState mirror(BlockState state, Mirror mirror) {
    //?} else {
    /*public BlockState mirror(BlockState state, Mirror mirror) {
    *///?}
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DiscDyeingTableBlockEntity(pos, state);
    }

    @Override
    //? if >=1.21.2 {
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            // extended menu (BlockPos を client ctor へ運ぶ) の open は loader 固有 → SPI 経由。
            Services.MENU.openDiscDyeingTableMenu(serverPlayer, pos);
    //?} elif >=1.21 {
    /*protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            final BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DiscDyeingTableBlockEntity) {
                Services.MENU.openDiscDyeingTableMenu(serverPlayer, pos);
            }
    *///?} else {
    /*public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            final BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DiscDyeingTableBlockEntity) {
                Services.MENU.openDiscDyeingTableMenu(serverPlayer, pos);
            }
    *///?}
        }
        //? if >=1.21.2 {
        return InteractionResult.SUCCESS;
        //?} else {
        /*return InteractionResult.sidedSuccess(level.isClientSide);
        *///?}
    }

    // 撤去時の中身ドロップ: >=1.21.2 は BlockEntity#preRemoveSideEffects の既定 (Container を自動ドロップ)
    // に任せる。それ未満は onRemove で自分で落とす。
    //? if >=1.21.2 {
    //?} elif >=1.21 {
    /*@Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            final BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DiscDyeingTableBlockEntity table) {
                Containers.dropContents(level, pos, table);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
    *///?} else {
    /*@Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            final BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DiscDyeingTableBlockEntity table) {
                Containers.dropContents(level, pos, table);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
    *///?}
}
