package com.kuronami.musicdiscmaker.event;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * vanilla jukebox に custom disc を出し入れする処理。
 * custom disc は vanilla の jukebox_playable を持たないので、右クリックを event で intercept して
 * 自前で挿入/取り出し + 再生 packet を broadcast する (server authoritative)。
 */
@EventBusSubscriber(modid = MusicDiscMaker.MODID)
public final class JukeboxHandler {

    private JukeboxHandler() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        final Level level = event.getLevel();
        final BlockPos pos = event.getPos();
        final BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.JUKEBOX)) {
            return;
        }
        final BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof JukeboxBlockEntity jukebox)) {
            return;
        }

        final Player player = event.getEntity();
        final ItemStack held = player.getItemInHand(event.getHand());
        final boolean jukeboxHasOurDisc = jukebox.getTheItem().is(ModItems.CUSTOM_MUSIC_DISC.get());
        final boolean holdingOurDisc = held.is(ModItems.CUSTOM_MUSIC_DISC.get())
                && held.has(ModDataComponents.CUSTOM_TRACK.get());

        if (jukebox.getTheItem().isEmpty() && holdingOurDisc) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
            if (!level.isClientSide) {
                final CustomTrackData track = held.get(ModDataComponents.CUSTOM_TRACK.get());
                jukebox.setTheItem(held.copyWithCount(1));
                if (!player.getAbilities().instabuild) {
                    held.shrink(1);
                }
                broadcast(level, pos, new PlayDiscPayload(pos, track));
            }
        } else if (jukeboxHasOurDisc) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
            if (!level.isClientSide) {
                final ItemStack disc = jukebox.getTheItem().copy();
                jukebox.setTheItem(ItemStack.EMPTY);
                if (!player.addItem(disc)) {
                    player.drop(disc, false);
                }
                broadcast(level, pos, new StopDiscPayload(pos));
            }
        }
    }

    /** jukebox 破壊時に鳴りっぱなしを防ぐ。 */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        final BlockPos pos = event.getPos();
        if (!event.getState().is(Blocks.JUKEBOX)) {
            return;
        }
        if (serverLevel.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox
                && jukebox.getTheItem().is(ModItems.CUSTOM_MUSIC_DISC.get())) {
            broadcast(serverLevel, pos, new StopDiscPayload(pos));
        }
    }

    private static void broadcast(Level level, BlockPos pos, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (level instanceof ServerLevel serverLevel) {
            PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(pos), payload);
        }
    }
}
