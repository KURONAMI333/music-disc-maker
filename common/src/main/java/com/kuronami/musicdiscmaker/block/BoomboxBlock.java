package com.kuronami.musicdiscmaker.block;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.register.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/**
 * ブームボックス。手持ちでも設置でも鳴る携帯プレイヤー。
 *
 * <p>設置中の挙動は強化版ジュークボックスと同じ (ディスクを持って右クリックで挿入・空手で設定 GUI)。
 * 違うのは ① ディスクと設定を壊しても持ち歩ける ({@link BoomboxBlockEntity}) ② レッドストーン出力を
 * 持たない ③ スピーカー網の音源にはならない (リンク側が強化版ジュークボックスだけを見ているので、
 * こちらは何もしなくてよい)。
 */
public class BoomboxBlock extends GoldenJukeboxBlock {

    /** 正面 (スピーカー面) の向き。設置したプレイヤーの方を向く。 */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public BoomboxBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(HAS_RECORD, Boolean.FALSE)
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BoomboxBlockEntity(pos, state);
    }

    /**
     * 親の ticker は BE 型が強化版ジュークボックスのときしか付かない
     * ({@code createTickerHelper} は {@code expected == actual} 判定)。ここを継ぐと設置した
     * ブームボックスが一度も tick されず、chunk 復元・リピート・ラジオ再アームが全部死ぬ。
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide || !state.getValue(HAS_RECORD)) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.BOOMBOX.get(),
                GoldenJukeboxBlockEntity::serverTick);
    }

    /** ディスクはブロックアイテムの component で持ち出す (単体ドロップと二重になる)。 */
    @Override
    protected boolean dropsDiscOnRemove() {
        return false;
    }

    // ── レッドストーン: 携帯プレイヤーなので信号源にもコンパレータ源にもしない。
    //    強化版ジュークボックスの出力仕様 (P4 でビートモードへ変わる) とも切り離しておく。

    @Override
    protected boolean isSignalSource(BlockState state) {
        return false;
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return false;
    }
}
