package com.kuronami.musicdiscmaker.event;

import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
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

/**
 * vanilla jukebox の custom disc 再生制御。
 *
 * <p>v1.1.0 から custom disc は JUKEBOX_PLAYABLE component を持つので、挿入/取り出しは
 * バニラ ({@code JukeboxPlayable.tryInsertIntoJukebox} / popOut) が処理し、{@code setTheItem}
 * を mixin ({@link com.kuronami.musicdiscmaker.event.JukeboxDiscController}) がフックして
 * ストリーミングを起動/停止する。ここで処理するのは component を持たない旧 disc (v1.0.x 生成) の
 * 挿入フォールバックのみ。破壊時停止 (setTheItem を通らない) と late-joiner 同期は引き続き event で行う。
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
        // component を持つ disc はバニラに任せる (ここでは介入しない)。旧 disc のみフォールバック挿入。
        final boolean holdingLegacyDisc = held.is(ModItems.CUSTOM_MUSIC_DISC.get())
                && held.has(ModDataComponents.CUSTOM_TRACK.get())
                && !held.has(DataComponents.JUKEBOX_PLAYABLE);
        if (jukebox.getTheItem().isEmpty() && holdingLegacyDisc) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
            if (level instanceof ServerLevel) {
                // setTheItem の mixin が start + broadcast を行うので、ここではアイテム移動だけ。
                jukebox.setTheItem(held.copyWithCount(1));
                if (!player.getAbilities().instabuild) {
                    held.shrink(1);
                }
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

    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        final ServerLevel level = event.getLevel();
        final long now = System.currentTimeMillis();
        final ChunkPos chunkPos = event.getPos();
        final List<ActiveDiscRegistry.Playing> playing =
                ActiveDiscRegistry.activeInChunk(level.dimension(), chunkPos, now);
        final ServerPlayer player = event.getPlayer();

        // 強化版ジュークボックス (BE 権威・ActiveDiscRegistry 非使用) の late-join 再送。
        GoldenJukeboxLateJoin.resend(level, chunkPos, player);

        for (final ActiveDiscRegistry.Playing p : playing) {
            // 撤去済み jukebox の stale エントリを late-joiner に送らない (爆発/ピストン/コマンド除去の掃除)。
            if (!(level.getBlockEntity(p.pos()) instanceof JukeboxBlockEntity jb)
                    || !jb.getTheItem().is(ModItems.CUSTOM_MUSIC_DISC.get())) {
                ActiveDiscRegistry.stop(level.dimension(), p.pos());
                continue;
            }
            final long elapsed = Math.max(0L, now - p.startMillis());
            Services.NETWORK.sendToPlayer(player, PlayDiscPayload.vanilla(p.pos(), p.track(), elapsed));
        }

        final var chunk = level.getChunk(chunkPos.x, chunkPos.z);
        for (final BlockPos bePos : chunk.getBlockEntitiesPos()) {
            if (!new ChunkPos(bePos).equals(chunkPos)) continue;
            if (ActiveDiscRegistry.isActive(level.dimension(), bePos)) continue;
            if (!(chunk.getBlockEntity(bePos) instanceof JukeboxBlockEntity jukebox)) continue;
            final ItemStack disc = jukebox.getTheItem();
            if (!disc.is(ModItems.CUSTOM_MUSIC_DISC.get())) continue;
            final var track = disc.get(ModDataComponents.CUSTOM_TRACK.get());
            if (track == null || track.isEmpty()) continue;
            ActiveDiscRegistry.start(level.dimension(), bePos, track, now);
            Services.NETWORK.sendToPlayer(player, PlayDiscPayload.vanilla(bePos, track, 0L));
        }
    }

    /** server 停止で再生中状態を破棄 (シングルプレイのワールド退出含む)。 */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ActiveDiscRegistry.clear();
    }

    private static void broadcast(Level level, BlockPos pos,
            net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (level instanceof ServerLevel serverLevel) {
            Services.NETWORK.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(pos), payload);
        }
    }
}
