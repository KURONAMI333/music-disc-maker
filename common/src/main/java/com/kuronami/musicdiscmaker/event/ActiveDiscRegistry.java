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
 * server 側で「その jukebox に何をいつから入れたか」を追跡する。後から jukebox の chunk に入った
 * player へ経過 offset 付きで再生 packet を送るためのもの。
 *
 * <p>曲尺を過ぎたエントリも<b>捨てずに覚えておく</b>。捨てると「一度鳴り終わった」と「まだ一度も
 * 始まっていない」が区別できなくなり、chunk 再送の走査 (死亡リスポーン・TP・再ログイン・chunk
 * 再ロード) が刺さったままのディスクを新規挿入と読んで頭から鳴らし直す。エントリを消すのは
 * ディスクがスロットから消えたとき ({@link #stop}) と server 停止 ({@link #clear}) だけ。
 *
 * <p>in-memory のみ (server 停止で消える＝再起動後は再挿入で復帰、vanilla disc と同等)。
 * 全操作は server thread からのみ呼ばれる前提なので非同期化しない。
 */
public final class ActiveDiscRegistry {

    /**
     * 挿入済みエントリ。{@code startMillis} は wall-clock (offset 計算は server 側で行い payload で渡す)。
     * 曲尺を過ぎた後もこの形のまま残る ({@link #finishedBy} が真になるだけ)。
     */
    public record Playing(BlockPos pos, CustomTrackData track, long startMillis) {

        /**
         * {@code nowMillis} の時点で曲尺を過ぎているか。
         * 尺不明 ({@code durationMs <= 0}) のトラックは終わりが判定できないので常に再生中扱い。
         */
        public boolean finishedBy(long nowMillis) {
            final long dur = track.durationMs();
            return dur > 0L && nowMillis - startMillis >= dur;
        }
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

    /**
     * 指定 pos にディスクが入っていると registry が覚えているか (曲尺を過ぎた後も真)。
     * spurious な stop broadcast を避ける判定と、chunk 再送の「まだ挿さっていない」判定に使う。
     */
    public static boolean isActive(ResourceKey<Level> dim, BlockPos pos) {
        final Map<BlockPos, Playing> map = BY_DIM.get(dim);
        return map != null && map.containsKey(pos);
    }

    /**
     * 指定 pos の再生中エントリを返す (無ければ null)。純粋な read で prune しない。
     * album 互換のトラック差分判定 (現在ストリーム中の url と比較) に使う。
     */
    public static Playing current(ResourceKey<Level> dim, BlockPos pos) {
        final Map<BlockPos, Playing> map = BY_DIM.get(dim);
        return map == null ? null : map.get(pos);
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
     * 指定 chunk 内の既知エントリを全部返す (曲尺を過ぎたものを含む)。純粋な read で prune しない。
     *
     * <p>chunk 再送の走査はこちらを使う。撤去済み jukebox の掃除には鳴り終わったエントリも
     * 見えている必要があり、{@link #activeInChunk} だとその分が漏れる。
     */
    public static List<Playing> knownInChunk(ResourceKey<Level> dim, ChunkPos chunk) {
        final Map<BlockPos, Playing> map = BY_DIM.get(dim);
        if (map == null || map.isEmpty()) {
            return List.of();
        }
        final List<Playing> out = new ArrayList<>();
        for (final Playing p : map.values()) {
            if (new ChunkPos(p.pos()).equals(chunk)) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * 指定 chunk 内で「まだ再生中」のエントリを返す。曲尺を過ぎたものは<b>覚えたまま返さない</b>
     * (late-joiner へ送るのは今も鳴っているものだけ)。
     */
    public static List<Playing> activeInChunk(ResourceKey<Level> dim, ChunkPos chunk, long nowMillis) {
        final List<Playing> known = knownInChunk(dim, chunk);
        final List<Playing> out = new ArrayList<>(known.size());
        for (final Playing p : known) {
            if (!p.finishedBy(nowMillis)) {
                out.add(p);
            }
        }
        return out;
    }

    /** server 停止時に全消去 (シングルプレイのワールド退出含む)。 */
    public static void clear() {
        BY_DIM.clear();
    }
}
