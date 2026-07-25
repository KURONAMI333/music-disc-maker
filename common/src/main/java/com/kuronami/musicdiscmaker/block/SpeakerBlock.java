package com.kuronami.musicdiscmaker.block;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.event.SpeakerNetwork;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
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
 * スピーカー。強化版ジュークボックスを音源に、離れた場所で同じ曲を鳴らす。
 *
 * <p>リンクはスピーカーのブロックアイテムで音源をシフト右クリックして記憶させ、そのまま設置すると
 * 成立する (Create のメカニカルアーム型)。距離上限・dimension・1 音源あたり台数は設置時に検証し、
 * 弾いた理由はアクションバーに出す ({@link #setPlacedBy})。
 *
 * <p>置くだけで鳴り、レッドストーン信号を入れると黙る ({@link #POWERED} = ミュート)。空手の右クリックで
 * 音量・可聴範囲の小さな設定 GUI を開く。
 */
public class SpeakerBlock extends Block implements EntityBlock {

    /** レッドストーン信号あり = ミュート。 */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public SpeakerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POWERED, Boolean.FALSE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpeakerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.SPEAKER.get(), SpeakerBlockEntity::serverTick);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static <E extends BlockEntity, A extends BlockEntity> BlockEntityTicker<A> createTickerHelper(
            BlockEntityType<A> actual, BlockEntityType<E> expected, BlockEntityTicker<? super E> ticker) {
        return expected == actual ? (BlockEntityTicker<A>) ticker : null;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(POWERED,
                context.getLevel().hasNeighborSignal(context.getClickedPos()));
    }

    /**
     * 設置時のリンク確定。ブロックアイテムに記憶させた音源はここへ来る前に
     * {@code SpeakerBlockEntity#applyImplicitComponents} で BE に入っているので、ここでは検証と
     * 拒否理由の通知だけを行う (player 参照を持つのがこの経路だけのため)。
     */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!(level instanceof ServerLevel serverLevel)
                || !(level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker)) {
            return;
        }
        final BlockPos sourcePos = speaker.getSourcePos();
        if (sourcePos == null) {
            return; // 音源を記憶していないブロックアイテム = ただの設置
        }
        final String rejection = rejectionReason(serverLevel, pos, sourcePos);
        if (rejection != null) {
            speaker.setSourcePos(null);
            speaker.sync();
            notifyPlacer(placer, Component.translatable(rejection));
            return;
        }
        // BE は component 適用の時点では index 未登録 (onLoad が component より前に走る)。ここで登録する。
        speaker.setSourcePos(sourcePos);
        speaker.sync();
        notifyPlacer(placer, Component.translatable("music_disc_maker.speaker.linked"));
    }

    /** リンクを拒否する理由の lang キー。{@code null} = 受理。 */
    @Nullable
    private static String rejectionReason(ServerLevel level, BlockPos speakerPos, BlockPos sourcePos) {
        // dimension 跨ぎは component 側 (GlobalPos) で既に落としてあるので、ここは同一 level 前提。
        if (!level.isLoaded(sourcePos) || !level.getBlockState(sourcePos).is(ModBlocks.GOLDEN_JUKEBOX.get())) {
            return "music_disc_maker.speaker.link_failed.no_source";
        }
        final int linkRange = Config.speakerLinkRange();
        if (!sourcePos.closerThan(speakerPos, linkRange)) {
            return "music_disc_maker.speaker.link_failed.too_far";
        }
        if (SpeakerNetwork.countFor(level, sourcePos) >= Config.maxSpeakersPerSource()) {
            return "music_disc_maker.speaker.link_failed.too_many";
        }
        return null;
    }

    private static void notifyPlacer(@Nullable LivingEntity placer, Component message) {
        if (placer instanceof ServerPlayer player) {
            player.displayClientMessage(message, true);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof SpeakerBlockEntity) {
            Services.MENU.openSpeakerMenu(serverPlayer, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** レッドストーン信号でミュートを切り替え、client のスピーカー集合を更新させる。 */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
            BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level.isClientSide) {
            return;
        }
        final boolean powered = level.hasNeighborSignal(pos);
        if (powered == state.getValue(POWERED)) {
            return;
        }
        level.setBlock(pos, state.setValue(POWERED, powered), Block.UPDATE_CLIENTS);
        if (level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
            speaker.notifySourceChanged();
        }
    }
}
