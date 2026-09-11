package com.kuronami.musicdiscmaker.block;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.speaker.SpeakerPlacement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//? if >=1.21.1 {
import com.mojang.serialization.MapCodec;
//?} else {
//?}
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 面に取り付く PA ホーン本体。設置面と水平向きは vanilla の FaceAttachedHorizontalDirectionalBlock に委ねる。
 */
public class SpeakerBlock extends FaceAttachedHorizontalDirectionalBlock implements EntityBlock {

    public static final EnumProperty<HornTurn> HORN_TURN = EnumProperty.create("horn_turn", HornTurn.class);

    public enum HornTurn implements StringRepresentable {
        CENTER("center", 0),
        LEFT("left", 1),
        RIGHT("right", 2);

        private final String name;
        private final int orientationBand;

        HornTurn(String name, int orientationBand) {
            this.name = name;
            this.orientationBand = orientationBand;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        public int orientationBand() {
            return orientationBand;
        }

        public HornTurn next() {
            return switch (this) {
                case CENTER -> LEFT;
                case LEFT -> RIGHT;
                case RIGHT -> CENTER;
            };
        }
    }

    //? if >=1.21.1 {
    private static final MapCodec<SpeakerBlock> CODEC = simpleCodec(SpeakerBlock::new);
    //?} else {
    //?}

    public SpeakerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACE, AttachFace.WALL)
                .setValue(FACING, Direction.NORTH)
                .setValue(HORN_TURN, HornTurn.CENTER));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, FACING, HORN_TURN);
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(FACE) == AttachFace.FLOOR) {
            return Block.canSupportCenter(level, pos.below(), Direction.UP);
        }
        return super.canSurvive(state, level, pos);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        final BlockState state = super.getStateForPlacement(context);
        if (state == null) {
            return null;
        }
        if (context.getPlayer() == null) {
            return state;
        }
        if (state.getValue(FACE) == AttachFace.WALL) {
            // On a wall FACING is also the support-bearing state.  Keep it intact, but choose the horn's
            // centre/left/right bearing from the same placement yaw used by floor and ceiling speakers.
            return state.setValue(HORN_TURN, fromPlacementTurn(SpeakerPlacement.wallTurn(
                    toPlacementFacing(state.getValue(FACING)), context.getPlayer().getYRot())));
        }
        final SpeakerPlacement.Orientation orientation = SpeakerPlacement.floorOrCeiling(context.getPlayer().getYRot());
        return state.setValue(FACING, fromPlacementFacing(orientation.facing()))
                .setValue(HORN_TURN, fromPlacementTurn(orientation.turn()));
    }

    /**
     * Floor and ceiling speakers follow the placer rather than throwing away the diagonal part of their yaw.
     * Minecraft blockstates only store the four support bearings, so the adjacent 45-degree bearings are carried
     * by {@link HornTurn}.  The horn points back toward the player, matching the usual placed-block convention.
     */
    private static SpeakerPlacement.Facing toPlacementFacing(Direction direction) {
        return switch (direction) {
            case NORTH -> SpeakerPlacement.Facing.NORTH; case EAST -> SpeakerPlacement.Facing.EAST;
            case SOUTH -> SpeakerPlacement.Facing.SOUTH; case WEST -> SpeakerPlacement.Facing.WEST;
            default -> throw new IllegalArgumentException("Speaker support is not horizontal: " + direction);
        };
    }
    private static Direction fromPlacementFacing(SpeakerPlacement.Facing facing) { return switch (facing) {
        case NORTH -> Direction.NORTH; case EAST -> Direction.EAST; case SOUTH -> Direction.SOUTH; case WEST -> Direction.WEST;
    }; }
    private static HornTurn fromPlacementTurn(SpeakerPlacement.Turn turn) { return switch (turn) {
        case CENTER -> HornTurn.CENTER; case LEFT -> HornTurn.LEFT; case RIGHT -> HornTurn.RIGHT;
    }; }

    //? if >=1.21.1 {
    @Override
    protected MapCodec<? extends FaceAttachedHorizontalDirectionalBlock> codec() {
        return CODEC;
    }
    //?} else {
    //?}

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpeakerBlockEntity(pos, state);
    }

    @Override
    //? if >=1.21.2 {
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        // SpeakerItemのsneak使用（リンク解除）を先に通す。
        if (player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide()) {
                level.setBlock(pos, state.setValue(HORN_TURN, state.getValue(HORN_TURN).next()), 3);
            }
            return InteractionResult.SUCCESS;
        }
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof SpeakerBlockEntity) {
            Services.MENU.openSpeakerMenu(serverPlayer, pos);
        }
        return InteractionResult.SUCCESS;
    }
    //?} elif >=1.21 {
/*    protected net.minecraft.world.ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
            BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (player.isShiftKeyDown()) return net.minecraft.world.ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                level.setBlock(pos, state.setValue(HORN_TURN, state.getValue(HORN_TURN).next()), 3);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof SpeakerBlockEntity) {
            Services.MENU.openSpeakerMenu(serverPlayer, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
*/
    //?} else {
    /*    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (player.isShiftKeyDown()) {
            if (!player.getItemInHand(hand).isEmpty()) return InteractionResult.PASS;
            if (!level.isClientSide) {
                level.setBlock(pos, state.setValue(HORN_TURN, state.getValue(HORN_TURN).next()), 3);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof SpeakerBlockEntity) {
            Services.MENU.openSpeakerMenu(serverPlayer, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
*/
    //?}

    /** ticker は持たない。mute は隣接信号を問い合わせるだけで即時に反映される。 */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return null;
    }
}
