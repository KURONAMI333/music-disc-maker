package com.kuronami.musicdiscmaker.block;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
//? if >=1.21.2 {
//?} else {
/*import net.minecraft.world.InteractionHand;
*///?}
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * ディスクを飾る台座。<b>ディスクを 1 枚だけ斜めに掛けて見せる</b>ブロック。
 *
 * <p>右クリックで置く / 外す / 差し替える。持ち物が空なら外し、MDM のカスタムディスクを
 * 持っていれば置くか、既存の盤と1操作で交換する。
 * 盤と曲名を描くのは {@code DiscPedestalRenderer} で、このブロックの模型は台だけを持つ。
 *
 * <p>正面はかまど型 (設置プレイヤーを向く)。前側の返しと、背面側に開いた 2 本の
 * 支柱で盤を受けるため、<b>あたり判定も facing ごとに模型と同じ向きへ回す</b>。
 */
public class DiscPedestalBlock extends Block implements EntityBlock {

    //? if >=1.21.2 {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    //?} else {
    /*public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    *///?}

    // Bounds of the low base, retaining lip, and two sloped back supports.
    private static final VoxelShape NORTH_COLLISION_SHAPE = Shapes.or(
            Block.box(1.0, 0.0, 3.0, 15.0, 3.0, 13.0),
            Block.box(2.0, 3.0, 5.0, 14.0, 4.0, 7.0),
            Block.box(4.0, 2.6, 6.0, 6.0, 10.5, 10.0),
            Block.box(10.0, 2.6, 6.0, 12.0, 10.5, 10.0));
    private static final VoxelShape EAST_COLLISION_SHAPE = Shapes.or(
            Block.box(3.0, 0.0, 1.0, 13.0, 3.0, 15.0),
            Block.box(9.0, 3.0, 2.0, 11.0, 4.0, 14.0),
            Block.box(6.0, 2.6, 4.0, 10.0, 10.5, 6.0),
            Block.box(6.0, 2.6, 10.0, 10.0, 10.5, 12.0));
    private static final VoxelShape SOUTH_COLLISION_SHAPE = Shapes.or(
            Block.box(1.0, 0.0, 3.0, 15.0, 3.0, 13.0),
            Block.box(2.0, 3.0, 9.0, 14.0, 4.0, 11.0),
            Block.box(10.0, 2.6, 6.0, 12.0, 10.5, 10.0),
            Block.box(4.0, 2.6, 6.0, 6.0, 10.5, 10.0));
    private static final VoxelShape WEST_COLLISION_SHAPE = Shapes.or(
            Block.box(3.0, 0.0, 1.0, 13.0, 3.0, 15.0),
            Block.box(5.0, 3.0, 2.0, 7.0, 4.0, 14.0),
            Block.box(6.0, 2.6, 10.0, 10.0, 10.5, 12.0),
            Block.box(6.0, 2.6, 4.0, 10.0, 10.5, 6.0));

    // 選択枠は細い2本の支柱をなぞらず、台と盤の表示域を一つの枠で包む。
    // 当たり判定は下の *_COLLISION_SHAPE のままなので、見た目のために通行可能域を増やさない。
    private static final VoxelShape NORTH_SELECTION_SHAPE = Block.box(1.0, 0.0, 3.0, 15.0, 13.0, 13.0);
    private static final VoxelShape EAST_SELECTION_SHAPE = Block.box(3.0, 0.0, 1.0, 13.0, 13.0, 15.0);
    private static final VoxelShape SOUTH_SELECTION_SHAPE = Block.box(1.0, 0.0, 3.0, 15.0, 13.0, 13.0);
    private static final VoxelShape WEST_SELECTION_SHAPE = Block.box(3.0, 0.0, 1.0, 13.0, 13.0, 15.0);

    public DiscPedestalBlock(Properties properties) {
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
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    //?} else {
    /*// 1.20.1 の BlockBehaviour はこの一群が public。protected へ絞ると可視性の縮小で落ちる。
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    *///?}
        return switch (state.getValue(FACING)) {
            case EAST -> EAST_SELECTION_SHAPE;
            case SOUTH -> SOUTH_SELECTION_SHAPE;
            case WEST -> WEST_SELECTION_SHAPE;
            default -> NORTH_SELECTION_SHAPE;
        };
    }

    @Override
    //? if >=1.21 {
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    //?} else {
    /*public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
    *///?}
        return switch (state.getValue(FACING)) {
            case EAST -> EAST_COLLISION_SHAPE;
            case SOUTH -> SOUTH_COLLISION_SHAPE;
            case WEST -> WEST_COLLISION_SHAPE;
            default -> NORTH_COLLISION_SHAPE;
        };
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
        return new DiscPedestalBlockEntity(pos, state);
    }

    /**
     * 置く / 外すの本体。<b>手に何を持っていてもここへ来る</b>。
     *
     * <p>1.21 以降は空手用の口 ({@code useWithoutItem}) だが、バニラの既定の {@code useItemOn} が
     * 「空手の口へ回す」を返すので、メインハンドに物を持っていてもここが呼ばれる
     * (実測: {@code ServerPlayerGameMode#useItemOn} の TryEmptyHandInteraction 分岐)。
     * だから帯ごとに {@code useItemOn} を書き分けずに済む。
     *
     * @return 何もしなかった時は PASS。持っているアイテム側の動作を塞がない
     */
    private static InteractionResult interact(BlockState state, Level level, BlockPos pos, Player player,
            ItemStack held) {
        if (!(level.getBlockEntity(pos) instanceof DiscPedestalBlockEntity pedestal)) {
            return InteractionResult.PASS;
        }
        final ItemStack shown = pedestal.getStored();
        if (shown.isEmpty()) {
            if (!DiscPedestalBlockEntity.accepts(held)) {
                return InteractionResult.PASS;
            }
            if (!level.isClientSide()) {
                pedestal.setStored(held.copyWithCount(1));
                if (!player.getAbilities().instabuild) {
                    held.shrink(1);
                }
                playPedestalSound(state, level, pos, 0.9F);
            }
        } else {
            if (!held.isEmpty() && !DiscPedestalBlockEntity.accepts(held)) {
                return InteractionResult.PASS;
            }
            if (!level.isClientSide()) {
                final ItemStack previous = shown.copy();
                if (held.isEmpty()) {
                    pedestal.setStored(ItemStack.EMPTY);
                } else {
                    pedestal.setStored(held.copyWithCount(1));
                    if (!player.getAbilities().instabuild) {
                        held.shrink(1);
                    }
                }
                if (!player.addItem(previous)) {
                    player.drop(previous, false);
                }
                playPedestalSound(state, level, pos, 0.7F);
            }
        }
        //? if >=1.21.2 {
        return InteractionResult.SUCCESS;
        //?} else {
        /*return InteractionResult.sidedSuccess(level.isClientSide);
        *///?}
    }

    /** 置く / 外すの音。ブロック自身の設置音を流用する (専用の音を持たないため)。 */
    private static void playPedestalSound(BlockState state, Level level, BlockPos pos, float pitch) {
        final SoundType sound = state.getSoundType();
        level.playSound((Entity) null, pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * pitch);
    }

    @Override
    //? if >=1.21 {
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        return interact(state, level, pos, player, player.getMainHandItem());
    }
    //?} else {
    /*// 1.20.1 に useWithoutItem は無い。手に何を持っていても同じ口 (use) に来る。
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        return interact(state, level, pos, player, player.getItemInHand(hand));
    }
    *///?}

    // 撤去時に飾ってあるディスクを落とす。loot table が返すのは台座そのものだけ。
    @Override
    //? if >=1.21.2 {
    protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos,
            boolean movedByPiston) {
        if (level.getBlockEntity(pos) instanceof DiscPedestalBlockEntity pedestal) {
            Containers.dropContents(level, pos, pedestal.contentsForDrop());
        }
        super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
    }
    //?} elif >=1.21 {
    /*protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
            boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof DiscPedestalBlockEntity pedestal) {
                Containers.dropContents(level, pos, pedestal.contentsForDrop());
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
    *///?} else {
    /*public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
            boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof DiscPedestalBlockEntity pedestal) {
                Containers.dropContents(level, pos, pedestal.contentsForDrop());
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
    *///?}
}
