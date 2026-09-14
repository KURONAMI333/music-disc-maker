package com.kuronami.musicdiscmaker.block;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.item.OversizeMediaRecovery;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
//? if >=1.21.2 {
//?} else {
/*import net.minecraft.world.InteractionHand;
*///?}
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
//? if >=1.21 {
import net.minecraft.world.level.LevelReader;
//?}
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
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
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 地面に置いたブームボックス。<b>本体は持ち歩くアイテムの方</b>で、このブロックは置いた姿。
 *
 * <p>右クリックで手持ちと同じ GUI が開く (設計上の決定 2026-09-07「設置されたラジオを右クリックで GUI」)。
 * 撤去すると、置いた時のアイテムがディスクごとそのまま返る ({@link BoomboxBlockEntity})。
 * loot table を空にしてあるのはそのためで、ここが唯一のドロップ経路。
 *
 * <p>正面はかまど型 (設置プレイヤーを向く)。あたり判定は 3D モデルの本体と取っ手に合わせた
 * 1 箱で、脚の隙間は埋めてある (脚だけ別箱にしても見た目に効かず、当たりだけ複雑になる)。
 */
public class BoomboxBlock extends Block implements EntityBlock {

    //? if >=1.21.2 {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    //?} else {
    /*public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    *///?}

    // 3D モデル (MDM_DECISIONS「ブームボックスの3Dモデルの形が確定した」) の外形:
    // 本体 x0..16 / y2..11 / z5..11、脚 y0..2、取っ手 y11..14。north 向きが素の姿。
    private static final VoxelShape SHAPE_NORTH = Block.box(0.0, 0.0, 4.0, 16.0, 14.0, 11.0);
    private static final VoxelShape SHAPE_SOUTH = Block.box(0.0, 0.0, 5.0, 16.0, 14.0, 12.0);
    private static final VoxelShape SHAPE_WEST = Block.box(4.0, 0.0, 0.0, 11.0, 14.0, 16.0);
    private static final VoxelShape SHAPE_EAST = Block.box(5.0, 0.0, 0.0, 12.0, 14.0, 16.0);

    public BoomboxBlock(Properties properties) {
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
        switch (state.getValue(FACING)) {
            case SOUTH:
                return SHAPE_SOUTH;
            case WEST:
                return SHAPE_WEST;
            case EAST:
                return SHAPE_EAST;
            default:
                return SHAPE_NORTH;
        }
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
        return new BoomboxBlockEntity(pos, state);
    }

    /**
     * 設置してある機体の打刻。<b>server 側だけ</b>。
     *
     * <p>やっているのは「この機体はまだ在る」を {@code BoomboxPlayback} に伝えることだけで、
     * 鳴っていなければ 2 回の map 参照で戻る。停止はこの打刻が途絶えたことで決まるので、
     * ブロックが壊れた・chunk が抜けた・世界を出た が全部同じ 1 本の経路になる。
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return (tickLevel, tickPos, tickState, blockEntity) -> {
            if (blockEntity instanceof BoomboxBlockEntity boombox && tickLevel instanceof ServerLevel server) {
                BoomboxPlayback.servePlaced(server, tickPos, boombox);
            }
        };
    }

    @Override
    //? if >=1.21.2 {
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof BoomboxBlockEntity) {
            final BoomboxSource source = BoomboxSource.placed(pos);
            if (BoomboxMenu.fitsInitialMenuSync(player.getInventory(), source)) {
                Services.MENU.openBoomboxMenu(serverPlayer, source);
            } else if (player.isSecondaryUseActive()) {
                OversizeMediaRecovery.notify(serverPlayer, OversizeMediaRecovery.recoverPlacedBoombox(serverPlayer,
                        (ServerLevel) level, pos, (BoomboxBlockEntity) level.getBlockEntity(pos)));
            } else {
                serverPlayer.sendSystemMessage(Component.translatable("message.music_disc_maker.boombox.open_too_large"), true);
            }
        }
        return InteractionResult.SUCCESS;
    //?} elif >=1.21 {
    /*protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof BoomboxBlockEntity) {
            final BoomboxSource source = BoomboxSource.placed(pos);
            if (BoomboxMenu.fitsInitialMenuSync(player.getInventory(), source)) {
                Services.MENU.openBoomboxMenu(serverPlayer, source);
            } else if (player.isSecondaryUseActive()) {
                OversizeMediaRecovery.notify(serverPlayer, OversizeMediaRecovery.recoverPlacedBoombox(serverPlayer,
                        (ServerLevel) level, pos, (BoomboxBlockEntity) level.getBlockEntity(pos)));
            } else {
                serverPlayer.sendSystemMessage(Component.translatable("message.music_disc_maker.boombox.open_too_large"), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    *///?} else {
    /*// 1.20.1 に useWithoutItem は無い。手に何を持っていても同じ口 (use) に来るので、
    // 「手が空いている時だけ」の絞りはバニラ側が持たない。ここでは持ち物を見ずに開ける
    // — ブームボックスは持ち歩ける器なので、持ったまま置いた機体を開く操作が普通に起きる。
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof BoomboxBlockEntity) {
            final BoomboxSource source = BoomboxSource.placed(pos);
            if (BoomboxMenu.fitsInitialMenuSync(player.getInventory(), source)) {
                Services.MENU.openBoomboxMenu(serverPlayer, source);
            } else if (player.isSecondaryUseActive()) {
                OversizeMediaRecovery.notify(serverPlayer, OversizeMediaRecovery.recoverPlacedBoombox(serverPlayer,
                        (ServerLevel) level, pos, (BoomboxBlockEntity) level.getBlockEntity(pos)));
            } else {
                serverPlayer.sendSystemMessage(Component.translatable("message.music_disc_maker.boombox.open_too_large"), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    *///?}
    }

    // 中クリック (pick block)。BlockItem を持たないブロックなので、明示しないと何も取れない。
    // 返すのは置いた時のアイテムそのもの = ディスクごと複製される。
    @Override
    //? if >=1.21.2 {
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        if (level.getBlockEntity(pos) instanceof BoomboxBlockEntity be && !be.getStored().isEmpty()) {
            return be.getStored().copy();
        }
        return new ItemStack(ModItems.BOOMBOX.get());
    }
    //?} elif >=1.21 {
    /*public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        if (level.getBlockEntity(pos) instanceof BoomboxBlockEntity be && !be.getStored().isEmpty()) {
            return be.getStored().copy();
        }
        return new ItemStack(ModItems.BOOMBOX.get());
    }
    *///?} else {
    /*// 1.20.1 の引数は LevelReader ではなく BlockGetter。
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        if (level.getBlockEntity(pos) instanceof BoomboxBlockEntity be && !be.getStored().isEmpty()) {
            return be.getStored().copy();
        }
        return new ItemStack(ModItems.BOOMBOX.get());
    }
    *///?}

    // 撤去時は「置いた時のアイテム」を 1 個だけ返す。loot table は空なので二重ドロップにならない。
    // 1.21.5+ は BlockEntity#preRemoveSideEffects。ここへ来る時点では BE が level から外れている。
    //? if >=1.21.2 {
    //?} elif >=1.21 {
    /*@Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
            boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof BoomboxBlockEntity be) {
                if (level instanceof ServerLevel serverLevel) {
                    BoomboxPlayback.stopPlaced(serverLevel, pos, be);
                }
                Containers.dropContents(level, pos, be.contentsForDrop());
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
    *///?} else {
    /*@Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
            boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof BoomboxBlockEntity be) {
                if (level instanceof ServerLevel serverLevel) {
                    BoomboxPlayback.stopPlaced(serverLevel, pos, be);
                }
                Containers.dropContents(level, pos, be.contentsForDrop());
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
    *///?}
}
