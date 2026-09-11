package com.kuronami.musicdiscmaker.event;
//? if >=1.21 {
//?} else {
/*import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
*/
//?}

//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.network.ModPayload;
//?} elif >=1.21 {
//?} else {
/*import com.kuronami.musicdiscmaker.network.ModPayload;
*/
//?}
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.register.ModDataComponents;
//?} else {
//?}
import com.kuronami.musicdiscmaker.register.ModItems;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
//? if >=1.21.2 {
import net.minecraft.core.component.DataComponents;
//?} elif >=1.21 {
/*import net.minecraft.core.component.DataComponents;
import com.kuronami.musicdiscmaker.network.ModPayload;
*/
//?} else {
//?}
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
//? if >=1.21 {
//?} else {
/*import net.minecraft.world.level.block.JukeboxBlock;
*/
//?}
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * vanilla jukebox に custom disc を出し入れする処理 (Fabric)。
 * custom disc は vanilla の jukebox_playable を持たないので、右クリックを event で intercept して
 * 自前で挿入/取り出し + 再生 packet を broadcast する (server authoritative)。
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
        // → main-hand のみ処理する (NeoForge は RightClickBlock+cancel で両手止まるが Fabric は止まらないため)。
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        // state 変更は server authoritative。
        //? if >=1.21.2 {
        if (level.isClientSide()) {
        //?} else {
/*        if (level.isClientSide) {
        */
        //?}
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
        //? if >=1.21.2 {
        if (!(level instanceof ServerLevel)) {
        //?} else {
/*        if (!(level instanceof ServerLevel serverLevel)) {
        */
        //?}
            return InteractionResult.PASS;
        }

        final ItemStack held = player.getItemInHand(hand);
        //? if >=1.21 {
        final boolean holdingLegacyDisc = held.is(ModItems.CUSTOM_MUSIC_DISC.get())
                && held.has(ModDataComponents.CUSTOM_TRACK.get())
                && !held.has(DataComponents.JUKEBOX_PLAYABLE);
        if (jukebox.getTheItem().isEmpty() && holdingLegacyDisc) {
            jukebox.setTheItem(held.copyWithCount(1));
        //?} else {
/*        final boolean jukeboxHasOurDisc = jukebox.getFirstItem().is(ModItems.CUSTOM_MUSIC_DISC.get());
        final boolean holdingOurDisc = held.is(ModItems.CUSTOM_MUSIC_DISC.get())
                && CustomMusicDiscItem.hasTrack(held);

        if (jukebox.getFirstItem().isEmpty() && holdingOurDisc) {
            insertOurDisc(serverLevel, pos, state, jukebox, held.copyWithCount(1));
        */
        //?}
            if (!player.getAbilities().instabuild) {
                held.shrink(1);
            }
            //? if >=1.21 {
            //?} else {
/*            return InteractionResult.SUCCESS;
        } else if (jukeboxHasOurDisc) {
            final ItemStack disc = jukebox.getFirstItem().copy();
            ejectOurDisc(serverLevel, pos, state, jukebox);
            if (!player.addItem(disc)) {
                player.drop(disc, false);
            }
            ActiveDiscRegistry.stop(serverLevel.dimension(), pos);
            broadcast(serverLevel, pos, new StopDiscPayload(pos));
            */
            //?}
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
                //? if >=1.21 {
                && jukebox.getTheItem().is(ModItems.CUSTOM_MUSIC_DISC.get())) {
                //?} else {
/*                && jukebox.getFirstItem().is(ModItems.CUSTOM_MUSIC_DISC.get())) {
                */
                //?}
            ActiveDiscRegistry.stop(serverLevel.dimension(), pos);
            broadcast(serverLevel, pos, new StopDiscPayload(pos));
        }
        return true;
    }

    //? if >=1.21 {
    //?} else {
/*    private static void insertOurDisc(ServerLevel level, BlockPos pos, BlockState state, JukeboxBlockEntity jukebox, ItemStack disc) {
        jukebox.setFirstItem(disc);
    }

    private static void ejectOurDisc(ServerLevel level, BlockPos pos, BlockState state, JukeboxBlockEntity jukebox) {
        jukebox.setRecordWithoutPlaying(ItemStack.EMPTY);
        level.setBlock(pos, state.setValue(JukeboxBlock.HAS_RECORD, Boolean.FALSE), 2);
    }
    */
    //?}
    private static void broadcast(ServerLevel level, BlockPos pos, ModPayload payload) {
        //? if >=26.1 {
        Services.NETWORK.sendToPlayersTrackingChunk(level, ChunkPos.containing(pos), payload);
        //?} else {
/*        Services.NETWORK.sendToPlayersTrackingChunk(level, new ChunkPos(pos), payload);
        */
        //?}
    }
}



