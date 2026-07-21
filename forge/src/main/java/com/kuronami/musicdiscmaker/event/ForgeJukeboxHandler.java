package com.kuronami.musicdiscmaker.event;

import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.network.ModPayload;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
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
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * vanilla jukebox に custom disc を出し入れする処理 (Forge)。
 * 右クリックを event で intercept して挿入/取り出し + 再生 packet を broadcast する (server authoritative)。
 *
 * <p>v1.1.0 から disc は RecordItem かつ {@code minecraft:music_discs} タグなので、挿入は
 * {@code setFirstItem} でバニラの再生状態 (isPlaying・音符・コンパレータ・Amendments の回転) に乗る。
 * 取り出しは {@code setRecordWithoutPlaying(EMPTY)} でスロットをクリア (タグゲートを迂回。item 空で
 * isRecordPlaying=false → 回転停止)。音声は LavaPlayer (packet 経由)、jukebox が鳴らす SoundEvent は無音。
 */
@Mod.EventBusSubscriber(modid = MusicDiscMaker.MODID)
public final class ForgeJukeboxHandler {

    private ForgeJukeboxHandler() {
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
        final boolean jukeboxHasOurDisc = jukebox.getFirstItem().is(ModItems.CUSTOM_MUSIC_DISC.get());
        final boolean holdingOurDisc = held.is(ModItems.CUSTOM_MUSIC_DISC.get())
                && CustomMusicDiscItem.hasTrack(held);

        if (jukebox.getFirstItem().isEmpty() && holdingOurDisc) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
            if (level instanceof ServerLevel serverLevel) {
                // 再生開始/broadcast は JukeboxBlockEntityMixin (setItem フック) が担う。
                // insertOurDisc→setFirstItem→setItem 経由で onContentChanged が発火するので、
                // ここで明示 start すると二重 broadcast になる (client 側 async ロードが競合)。
                insertOurDisc(serverLevel, pos, state, jukebox, held.copyWithCount(1));
                if (!player.getAbilities().instabuild) {
                    held.shrink(1);
                }
            }
        } else if (jukeboxHasOurDisc) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
            if (level instanceof ServerLevel serverLevel) {
                final ItemStack disc = jukebox.getFirstItem().copy();
                ejectOurDisc(serverLevel, pos, state, jukebox);
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
                && jukebox.getFirstItem().is(ModItems.CUSTOM_MUSIC_DISC.get())) {
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

        for (final ActiveDiscRegistry.Playing p : playing) {
            // 撤去済み jukebox の stale エントリを late-joiner に送らない (爆発/ピストン/コマンド除去の掃除)。
            if (!(level.getBlockEntity(p.pos()) instanceof JukeboxBlockEntity jb)
                    || !jb.getFirstItem().is(ModItems.CUSTOM_MUSIC_DISC.get())) {
                ActiveDiscRegistry.stop(level.dimension(), p.pos());
                continue;
            }
            final long elapsed = Math.max(0L, now - p.startMillis());
            Services.NETWORK.sendToPlayer(player, PlayDiscPayload.vanilla(p.pos(), p.track(), elapsed));
        }

        final var chunk = level.getChunk(chunkPos.x, chunkPos.z);
        for (final BlockPos bePos : chunk.getBlockEntitiesPos()) {
            if (!new ChunkPos(bePos).equals(chunkPos)) continue;
            // 強化版ジュークボックスは BE 自身が権威。現在位置で per-block 設定つきの再送を任せる。
            if (chunk.getBlockEntity(bePos) instanceof GoldenJukeboxBlockEntity enhanced) {
                enhanced.resendTo(player);
                continue;
            }
            if (ActiveDiscRegistry.isTracked(level.dimension(), bePos)) continue;
            if (!(chunk.getBlockEntity(bePos) instanceof JukeboxBlockEntity jukebox)) continue;
            final ItemStack disc = jukebox.getFirstItem();
            if (!disc.is(ModItems.CUSTOM_MUSIC_DISC.get())) continue;
            if (!CustomMusicDiscItem.hasTrack(disc)) continue;
            final CustomTrackData track = CustomMusicDiscItem.getTrack(disc);
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
