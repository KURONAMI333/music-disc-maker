package com.kuronami.musicdiscmaker.event;

import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.platform.Services;
//?} else {
//?}
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
//? if >=1.21.2 {
//?} else {
/*import com.kuronami.musicdiscmaker.platform.Services;
*/
//?}
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
//? if >=1.21.2 {
//?} else {
/*import net.minecraft.core.component.DataComponents;
*/
//?}
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
//? if >=26.1 {
//?} else {
/*import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
*/
//?}
import net.neoforged.neoforge.event.level.LevelEvent;
//? if >=26.1 {
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
//?} else {
//?}
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

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
        //? if >=1.21.2 {
        final boolean jukeboxHasOurDisc = jukebox.getTheItem().is(ModItems.CUSTOM_MUSIC_DISC.get());
        final boolean holdingOurDisc = held.is(ModItems.CUSTOM_MUSIC_DISC.get())
                && held.has(ModDataComponents.CUSTOM_TRACK.get());

        if (jukebox.getTheItem().isEmpty() && holdingOurDisc) {
        //?} else {
/*        final boolean holdingLegacyDisc = held.is(ModItems.CUSTOM_MUSIC_DISC.get())
                && held.has(ModDataComponents.CUSTOM_TRACK.get())
                && !held.has(DataComponents.JUKEBOX_PLAYABLE);
        if (jukebox.getTheItem().isEmpty() && holdingLegacyDisc) {
        */
        //?}
            event.setCanceled(true);
            //? if >=1.21.2 {
            event.setCancellationResult(InteractionResult.SUCCESS);
            //?} else {
/*            event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
            */
            //?}
            if (level instanceof ServerLevel) {
                // 再生開始/broadcast は JukeboxBlockEntityMixin (setTheItem フック) の onContentChanged が担う。
                jukebox.setTheItem(held.copyWithCount(1));
                if (!player.getAbilities().instabuild) {
                    held.shrink(1);
                //? if >=1.21.2 {
                }
            }
        } else if (jukeboxHasOurDisc) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
            if (level instanceof ServerLevel) {
                final ItemStack disc = jukebox.getTheItem().copy();
                jukebox.setTheItem(ItemStack.EMPTY);
                if (!player.addItem(disc)) {
                    player.drop(disc, false);
                //?} else {
                //?}
                }
            }
        }
    }


    /** jukebox 破壊時に鳴りっぱなしを防ぐ。 */
    @SubscribeEvent
    //? if >=26.1 {
    public static void onBlockBreak(BreakBlockEvent event) {
    //?} else {
/*    public static void onBlockBreak(BlockEvent.BreakEvent event) {
    */
    //?}
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
        ActiveDiscPersistence.hydrate(event.getLevel());
        final ServerLevel level = event.getLevel();
        final long now = System.currentTimeMillis();
        final ChunkPos chunkPos = event.getPos();
        final List<ActiveDiscRegistry.Playing> known =
                ActiveDiscRegistry.knownInChunk(level.dimension(), chunkPos);
        final ServerPlayer player = event.getPlayer();

        //? if >=1.21.2 {
        //?} else {
/*        GoldenJukeboxLateJoin.resend(level, chunkPos, player);
        */
        //?}
        for (final ActiveDiscRegistry.Playing p : known) {
            // 撤去済み jukebox の stale エントリを late-joiner に送らない (爆発/ピストン/コマンド除去の掃除)。
            // 鳴り終わったエントリもここで捨てる (これが registry の掃除経路)。
            if (!(level.getBlockEntity(p.pos()) instanceof JukeboxBlockEntity jb)
                    || !jb.getTheItem().is(ModItems.CUSTOM_MUSIC_DISC.get())) {
                ActiveDiscRegistry.stop(level.dimension(), p.pos());
                continue;
            }
            // 鳴り終わったディスクは覚えたまま送らない (送ると頭出しで鳴り直す)。
            if (p.finishedBy(now)) {
                continue;
            }
            final long elapsed = Math.max(0L, now - p.startMillis());
            //? if >=1.21.2 {
            // 送信は 1.21.1 と同じ Services.NETWORK 経由 (headless テストが送信を捕まえられる唯一の経路)。
            Services.NETWORK.sendToPlayer(player, PlayDiscPayload.vanilla(p.pos(), p.track(), elapsed));
            //?} else {
/*            Services.NETWORK.sendToPlayer(player, PlayDiscPayload.vanilla(p.pos(), p.track(), elapsed));
            */
            //?}
        }

        //? if >=26.1 {
        final var chunk = level.getChunk(chunkPos.x(), chunkPos.z());
        //?} else {
/*        final var chunk = level.getChunk(chunkPos.x, chunkPos.z);
        */
        //?}
        for (final BlockPos bePos : chunk.getBlockEntitiesPos()) {
            //? if >=26.1 {
            if (!ChunkPos.containing(bePos).equals(chunkPos)) continue;
            if (ActiveDiscRegistry.isTracked(level.dimension(), bePos)) continue;
            //?} elif >=1.21.2 {
/*            if (!new ChunkPos(bePos).equals(chunkPos)) continue;
            if (ActiveDiscRegistry.isTracked(level.dimension(), bePos)) continue;
            */
            //?} else {
/*            if (!new ChunkPos(bePos).equals(chunkPos)) continue;
            if (ActiveDiscRegistry.isActive(level.dimension(), bePos)) continue;
            */
            //?}
            if (!(chunk.getBlockEntity(bePos) instanceof JukeboxBlockEntity jukebox)) continue;
            final ItemStack disc = jukebox.getTheItem();
            if (!disc.is(ModItems.CUSTOM_MUSIC_DISC.get())) continue;
            //? if >=1.21.2 {
            final CustomTrackData track = disc.get(ModDataComponents.CUSTOM_TRACK.get());
            //?} else {
/*            final var track = disc.get(ModDataComponents.CUSTOM_TRACK.get());
            */
            //?}
            if (track == null || track.isEmpty()) continue;
            ActiveDiscRegistry.start(level.dimension(), bePos, track, now);
            //? if >=1.21.2 {
            Services.NETWORK.sendToPlayer(player, PlayDiscPayload.vanilla(bePos, track, 0L));
        }

        for (final BlockPos bePos : chunk.getBlockEntitiesPos()) {
            if (chunk.getBlockEntity(bePos) instanceof
                    com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity enhanced) {
                enhanced.resendTo(player);
            }
            //?} elif >=1.21.2 {
/*            Services.NETWORK.sendToPlayer(player, PlayDiscPayload.vanilla(bePos, track, 0L));
        }

        for (final BlockPos bePos : chunk.getBlockEntitiesPos()) {
            if (!new ChunkPos(bePos).equals(chunkPos)) continue;
            if (chunk.getBlockEntity(bePos) instanceof
                    com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity enhanced) {
                enhanced.resendTo(player);
            }
            */
            //?} else {
/*            Services.NETWORK.sendToPlayer(player, PlayDiscPayload.vanilla(bePos, track, 0L));
            */
            //?}
        }
    }

    /** server 停止で再生中状態を破棄 (シングルプレイのワールド退出含む)。 */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        ActiveDiscRegistry.clear();
        SpeakerPlayback.clear(event.getServer());
        SpeakerNetwork.clear(event.getServer());
    }

    /**
     * 次元がアンロードされたらその次元のエントリを捨てる。次元が落ちると chunk はもう誰にも
     * watch されず、chunk 再入を待つ掃除には二度と届かない (server 停止まで残り続ける)。
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            ActiveDiscRegistry.clear(serverLevel.dimension());
        }
    }

    //? if >=1.21.2 {
    private static void broadcast(Level level, BlockPos pos, com.kuronami.musicdiscmaker.network.ModPayload payload) {
    //?} else {
/*    private static void broadcast(Level level, BlockPos pos,
            com.kuronami.musicdiscmaker.network.ModPayload payload) {
    */
    //?}
        if (level instanceof ServerLevel serverLevel) {
            //? if >=26.1 {
            Services.NETWORK.sendToPlayersTrackingChunk(serverLevel, ChunkPos.containing(pos), payload);
            //?} elif >=1.21.2 {
/*            Services.NETWORK.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(pos), payload);
            */
            //?} else {
/*            Services.NETWORK.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(pos), payload);
            */
            //?}
        }
    }
}



