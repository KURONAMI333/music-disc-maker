package com.kuronami.musicdiscmaker.event;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.network.ModPayload;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * vanilla jukebox に custom disc を出し入れする処理 (Fabric)。
 * custom disc は vanilla の MUSIC_DISCS タグを持たないので、右クリックを event で intercept して
 * 自前で挿入/取り出し + 再生 packet を broadcast する (server authoritative)。
 *
 * <p>v1.1.0 から disc は RecordItem かつ {@code minecraft:music_discs} タグなので、挿入は
 * {@code setFirstItem} でバニラの再生状態 (isPlaying・音符・コンパレータ・Amendments の回転) に乗る。
 * 取り出しは {@code setRecordWithoutPlaying(EMPTY)} でスロットをクリア。音声は LavaPlayer (packet 経由)。
 */
public final class FabricJukeboxEvents {

    private FabricJukeboxEvents() {
    }

    public static void register() {
        UseBlockCallback.EVENT.register(FabricJukeboxEvents::onUseBlock);
        PlayerBlockBreakEvents.BEFORE.register(FabricJukeboxEvents::onBlockBreak);
    }

    private static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        // UseBlockCallback は main/off 両手分・両 side で発火する。
        // off-hand を処理すると、main-hand の挿入直後に off-hand(空) が排出条件にマッチして即座に出してしまう。
        // → main-hand のみ処理する。
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        // state 変更は server authoritative。
        if (level.isClientSide) {
            return InteractionResult.PASS;
        }
        final BlockPos pos = hit.getBlockPos();
        final BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.JUKEBOX)) {
            return InteractionResult.PASS;
        }
        final BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof JukeboxBlockEntity jukebox)) {
            return InteractionResult.PASS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
        }

        final ItemStack held = player.getItemInHand(hand);
        final boolean jukeboxHasOurDisc = jukebox.getFirstItem().is(ModItems.CUSTOM_MUSIC_DISC.get());
        final boolean holdingOurDisc = held.is(ModItems.CUSTOM_MUSIC_DISC.get())
                && CustomMusicDiscItem.hasTrack(held);

        if (jukebox.getFirstItem().isEmpty() && holdingOurDisc) {
            final CustomTrackData track = CustomMusicDiscItem.getTrack(held);
            insertOurDisc(serverLevel, pos, state, jukebox, held.copyWithCount(1));
            if (!player.getAbilities().instabuild) {
                held.shrink(1);
            }
            ActiveDiscRegistry.start(serverLevel.dimension(), pos, track, System.currentTimeMillis());
            broadcast(serverLevel, pos, new PlayDiscPayload(pos, track, 0L));
            return InteractionResult.SUCCESS;
        } else if (jukeboxHasOurDisc) {
            final ItemStack disc = jukebox.getFirstItem().copy();
            ejectOurDisc(serverLevel, pos, state, jukebox);
            if (!player.addItem(disc)) {
                player.drop(disc, false);
            }
            ActiveDiscRegistry.stop(serverLevel.dimension(), pos);
            broadcast(serverLevel, pos, new StopDiscPayload(pos));
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /** jukebox 破壊時に鳴りっぱなしを防ぐ。BE はまだ存在する (破壊前) ので中身を読める。 */
    private static boolean onBlockBreak(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return true;
        }
        if (!state.is(Blocks.JUKEBOX)) {
            return true;
        }
        if (blockEntity instanceof JukeboxBlockEntity jukebox
                && jukebox.getFirstItem().is(ModItems.CUSTOM_MUSIC_DISC.get())) {
            ActiveDiscRegistry.stop(serverLevel.dimension(), pos);
            broadcast(serverLevel, pos, new StopDiscPayload(pos));
        }
        return true;
    }

    /**
     * custom disc を jukebox に置く。disc は v1.1.0 から RecordItem かつ music_discs タグなので、
     * {@code setFirstItem}→{@code setItem(0)} が startPlaying まで走る（= isPlaying・音符パーティクル・
     * コンパレータ・Amendments の回転。HAS_RECORD も内部でセット）。再生音は無音 SoundEvent。
     */
    private static void insertOurDisc(ServerLevel level, BlockPos pos, BlockState state, JukeboxBlockEntity jukebox, ItemStack disc) {
        jukebox.setFirstItem(disc);
    }

    /** custom disc を jukebox から外す (HAS_RECORD を下ろす)。 */
    private static void ejectOurDisc(ServerLevel level, BlockPos pos, BlockState state, JukeboxBlockEntity jukebox) {
        jukebox.setRecordWithoutPlaying(ItemStack.EMPTY);
        level.setBlock(pos, state.setValue(JukeboxBlock.HAS_RECORD, Boolean.FALSE), 2);
    }

    private static void broadcast(ServerLevel level, BlockPos pos, ModPayload payload) {
        Services.NETWORK.sendToPlayersTrackingChunk(level, new ChunkPos(pos), payload);
    }
}
