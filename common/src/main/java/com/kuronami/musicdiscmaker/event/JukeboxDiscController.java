package com.kuronami.musicdiscmaker.event;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
//? if >=1.21 {
//?} else {
/*import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
*///?}
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.register.ModDataComponents;
//?} else {
//?}
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * jukebox の中身が変わったとき (右クリック挿入/取り出し・ホッパー・コマンド・他 mod 経由を問わず)
 * に custom disc のストリーミング再生を開始/停止する loader 非依存の単一入口。
 *
 * <p>custom disc は JUKEBOX_PLAYABLE component を持つので、挿入/排出はバニラの
 * {@link net.minecraft.world.level.block.entity.JukeboxBlockEntity#setTheItem} が処理する。
 * その setTheItem を {@link com.kuronami.musicdiscmaker.mixin.JukeboxBlockEntityMixin} が TAIL フックし、
 * このメソッドへ流すことで、あらゆる経路の再生を1点に集約する。
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
                //? if >=1.21 {
                && newItem.has(ModDataComponents.CUSTOM_TRACK.get());
                //?} else {
                /*&& CustomMusicDiscItem.hasTrack(newItem);

                *///?}
        if (isOurDisc) {
            //? if >=1.21 {
            final CustomTrackData track = newItem.get(ModDataComponents.CUSTOM_TRACK.get());
            //?} else {
            /*final CustomTrackData track = CustomMusicDiscItem.getTrack(newItem);
            *///?}
            ActiveDiscRegistry.start(serverLevel.dimension(), key, track, System.currentTimeMillis());
            //? if >=26.1 {
            Services.NETWORK.sendToPlayersTrackingChunk(serverLevel, ChunkPos.containing(key),
            //?} else {
            /*Services.NETWORK.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(key),
            *///?}
                    PlayDiscPayload.vanilla(key, track, 0L));
        //? if >=1.21.2 {
        } else if (ActiveDiscRegistry.isTracked(serverLevel.dimension(), key)) {
        //?} elif >=1.21 {
        /*} else if (ActiveDiscRegistry.isActive(serverLevel.dimension(), key)) {
        *///?} else {
        /*} else if (ActiveDiscRegistry.isTracked(serverLevel.dimension(), key)) {
        *///?}
            ActiveDiscRegistry.stop(serverLevel.dimension(), key);
            //? if >=26.1 {
            Services.NETWORK.sendToPlayersTrackingChunk(serverLevel, ChunkPos.containing(key),
            //?} else {
            /*Services.NETWORK.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(key),
            *///?}
                    new StopDiscPayload(key));
        }
    }
}

