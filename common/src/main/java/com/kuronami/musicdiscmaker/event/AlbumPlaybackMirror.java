package com.kuronami.musicdiscmaker.event;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Additional Additions のアルバムが jukebox で custom disc を「再生中」の状態を、MDM の
 * ストリーミング再生にミラーする単一の入口。**AA 固有の型を一切参照しない** (loader 側の
 * {@code AdditionalAdditionsCompat} が AA 状態を読み、ここには vanilla/MDM 型だけ渡す)。
 *
 * <p>state は {@link ActiveDiscRegistry} を唯一の真実として再利用する。これにより既存の
 * late-joiner 同期 (ChunkWatch) が album 再生にもそのまま効く。毎 tick 呼ばれる前提で
 * idempotent (差分が無ければ何も送らない)。全操作は server thread からのみ。
 */
public final class AlbumPlaybackMirror {

    private AlbumPlaybackMirror() {
    }

    /**
     * album jukebox の現在状態を反映する (server 側・毎 tick)。
     *
     * @param mdmDisc いま album が再生中の MDM custom disc stack。null なら「MDM ストリーム対象なし」
     *                (アルバム停止・空・vanilla disc トラック・トラック index 範囲外を含む)。
     */
    public static void mirror(ServerLevel level, BlockPos pos, @Nullable ItemStack mdmDisc) {
        final ResourceKey<Level> dim = level.dimension();
        final BlockPos key = pos.immutable();
        final ActiveDiscRegistry.Playing existing = ActiveDiscRegistry.current(dim, key);

        if (mdmDisc != null) {
            final CustomTrackData track = mdmDisc.get(ModDataComponents.CUSTOM_TRACK.get());
            if (track != null && !track.isEmpty()) {
                // 同じ url を既にストリーム中なら何もしない (毎 tick の再送を防ぐ)。
                if (existing != null && track.url().equals(existing.track().url())) {
                    return;
                }
                // 新規トラック (差し替え含む)。client 側 startPlayback が旧ストリームを停止して張り替える。
                ActiveDiscRegistry.start(dim, key, track, System.currentTimeMillis());
                // アルバム再生は強化版ジュークボックスではないので vanilla 経路 (既定 range / volume 100)。
                Services.NETWORK.sendToPlayersTrackingChunk(level, new ChunkPos(key),
                        PlayDiscPayload.vanilla(key, track, 0L));
                return;
            }
        }

        // MDM ストリーム対象なし → 直前まで鳴らしていたなら停止する。
        if (existing != null) {
            ActiveDiscRegistry.stop(dim, key);
            Services.NETWORK.sendToPlayersTrackingChunk(level, new ChunkPos(key),
                    new StopDiscPayload(key));
        }
    }
}
