package com.kuronami.musicdiscmaker.event;

import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
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
            if (level instanceof ServerLevel serverLevel) {
                final CustomTrackData track = held.get(ModDataComponents.CUSTOM_TRACK.get());
                jukebox.setTheItem(held.copyWithCount(1));
                if (!player.getAbilities().instabuild) {
                    held.shrink(1);
                }
                ActiveDiscRegistry.start(serverLevel.dimension(), pos, track, System.currentTimeMillis());
                broadcast(serverLevel, pos, new PlayDiscPayload(pos, track, 0L));
            }
        } else if (jukeboxHasOurDisc) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
            if (level instanceof ServerLevel serverLevel) {
                final ItemStack disc = jukebox.getTheItem().copy();
                jukebox.setTheItem(ItemStack.EMPTY);
                if (!player.addItem(disc)) {
                    player.drop(disc, false);
                }
                ActiveDiscRegistry.stop(serverLevel.dimension(), pos);
                broadcast(serverLevel, pos, new StopDiscPayload(pos));
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
            ActiveDiscRegistry.stop(serverLevel.dimension(), pos);
            broadcast(serverLevel, pos, new StopDiscPayload(pos));
        }
    }

    /** 後から jukebox の chunk に入った player に、経過 offset 付きで再生 packet を送る (途中から同期再生)。 */
    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        final ServerLevel level = event.getLevel();
        final long now = System.currentTimeMillis();
        final List<ActiveDiscRegistry.Playing> playing =
                ActiveDiscRegistry.activeInChunk(level.dimension(), event.getPos(), now);
        if (playing.isEmpty()) {
            return;
        }
        final ServerPlayer player = event.getPlayer();
        for (final ActiveDiscRegistry.Playing p : playing) {
            final long elapsed = Math.max(0L, now - p.startMillis());
            PacketDistributor.sendToPlayer(player, new PlayDiscPayload(p.pos(), p.track(), elapsed));
        }
    }

    /** server 停止で再生中状態を破棄 (シングルプレイのワールド退出含む)。 */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ActiveDiscRegistry.clear();
    }

    private static void broadcast(Level level, BlockPos pos, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (level instanceof ServerLevel serverLevel) {
            PacketDistributor.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(pos), payload);
        }
    }
}
