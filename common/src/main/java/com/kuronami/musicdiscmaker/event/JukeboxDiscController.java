package com.kuronami.musicdiscmaker.event;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * jukebox の中身が変わったとき (右クリック挿入/取り出し・ホッパー・コマンドを問わず) に
 * custom disc のストリーミング再生を開始/停止する単一の入口。
 *
 * <p>disc は RecordItem かつ {@code minecraft:music_discs} タグなので、挿入は
 * {@link net.minecraft.world.level.block.entity.JukeboxBlockEntity#setItem} が処理する。この setItem は
 * ContainerSingleItem の setFirstItem・ホッパー・コマンドが通る一本道 (一次ソースで確認済み)。その
 * setItem を mixin で TAIL フックしここへ流すことで、あらゆる挿入経路の再生を1点に集約する。
 * ワールド読込は items 直代入で setItem を通らないため誤発火しない。
 */
public final class JukeboxDiscController {

    private JukeboxDiscController() {
    }

    /**
     * jukebox の中身が {@code newItem} に変わった直後に呼ぶ (server 側)。
     * custom disc なら再生開始、それ以外 (空含む) で再生中なら停止。
     */
    public static void onContentChanged(Level level, BlockPos pos, ItemStack newItem) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final BlockPos key = pos.immutable();
        final boolean isOurDisc = newItem != null
                && newItem.is(ModItems.CUSTOM_MUSIC_DISC.get())
                && CustomMusicDiscItem.hasTrack(newItem);

        if (isOurDisc) {
            final CustomTrackData track = CustomMusicDiscItem.getTrack(newItem);
            ActiveDiscRegistry.start(serverLevel.dimension(), key, track, System.currentTimeMillis());
            Services.NETWORK.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(key),
                    new PlayDiscPayload(key, track, 0L));
        } else if (ActiveDiscRegistry.isTracked(serverLevel.dimension(), key)) {
            ActiveDiscRegistry.stop(serverLevel.dimension(), key);
            Services.NETWORK.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(key),
                    new StopDiscPayload(key));
        }
    }
}
