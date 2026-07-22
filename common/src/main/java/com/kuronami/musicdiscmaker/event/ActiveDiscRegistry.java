package com.kuronami.musicdiscmaker.event;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * server 側で「再生中の jukebox」を追跡する。後から jukebox の chunk に入った player へ、
 * 経過 offset 付きで再生 packet を送れるようにするためのもの。
 *
 * <p>in-memory のみ (server 停止で消える＝再起動後は再挿入で復帰、vanilla disc と同等)。
 * 全操作は server thread からのみ呼ばれる前提なので非同期化しない。
 */
public final class ActiveDiscRegistry {

    /** 再生中エントリ。{@code startMillis} は wall-clock (offset 計算は server 側で行い payload で渡す)。 */
    public record Playing(BlockPos pos, CustomTrackData track, long startMillis) {
    }

    private static final Map<ResourceKey<Level>, Map<BlockPos, Playing>> BY_DIM = new HashMap<>();

    private ActiveDiscRegistry() {
    }

    public static void start(ResourceKey<Level> dim, BlockPos pos, CustomTrackData track, long nowMillis) {
        if (track == null || track.isEmpty()) {
            return;
        }
        final BlockPos key = pos.immutable();
        BY_DIM.computeIfAbsent(dim, d -> new HashMap<>()).put(key, new Playing(key, track, nowMillis));
    }

    public static void stop(ResourceKey<Level> dim, BlockPos pos) {
        final Map<BlockPos, Playing> map = BY_DIM.get(dim);
        if (map != null) {
            map.remove(pos);
            if (map.isEmpty()) {
                BY_DIM.remove(dim);
            }
        }
    }

    /**
     * 指定 chunk 内で「まだ再生中」のエントリを返す。
     * 既に曲尺を過ぎたエントリ (durationMs を持つもの) はこの呼び出しで prune する。
     */
    public static List<Playing> activeInChunk(ResourceKey<Level> dim, ChunkPos chunk, long nowMillis) {
        final Map<BlockPos, Playing> map = BY_DIM.get(dim);
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        final List<Playing> out = new ArrayList<>();
        map.values().removeIf(p -> {
            final long dur = p.track().durationMs();
            if (dur > 0L && nowMillis - p.startMillis() >= dur) {
                return true; // 自然終了済み → 除去 (chunk 一致でも送らない)
            }
            if (ChunkPos.containing(p.pos()).equals(chunk)) {
                out.add(p);
            }
            return false;
        });
        if (map.isEmpty()) {
            BY_DIM.remove(dim);
        }
        return out;
    }

    public static boolean isTracked(ResourceKey<Level> dim, BlockPos pos) {
        final Map<BlockPos, Playing> map = BY_DIM.get(dim);
        return map != null && map.containsKey(pos.immutable());
    }

    /** 指定 pos の再生中エントリを返す (無ければ null)。アルバム互換の差分検出に使う。 */
    public static Playing current(ResourceKey<Level> dim, BlockPos pos) {
        final Map<BlockPos, Playing> map = BY_DIM.get(dim);
        return map == null ? null : map.get(pos.immutable());
    }

    public static void clear() {
        BY_DIM.clear();
    }
}
