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
 * <p>memory の内容は {@link ActiveDiscPersistence} が次元ごとの SavedData
 * ({@link ActiveDiscSaveData}) へ書き写し、server 再起動後の最初の chunk 走査で復元する
 * (B4「再起動後の頭出し」)。全操作は server thread からのみ呼ばれる前提なので非同期化しない。
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
        ActiveDiscPersistence.onStart(dim, key, track, nowMillis);
    }

    //? if >=1.21.2 {
    //?} elif >=1.21 {
    /*/^*
     * 指定 pos にディスクが入っていると registry が覚えているか (曲尺を過ぎた後も真)。
     * spurious な stop broadcast を避ける判定と、chunk 再送の「まだ挿さっていない」判定に使う。
     ^/
    public static boolean isActive(ResourceKey<Level> dim, BlockPos pos) {
        final Map<BlockPos, Playing> map = BY_DIM.get(dim);
        return map != null && map.containsKey(pos);
    }

    /^*
     * 指定 pos の再生中エントリを返す (無ければ null)。純粋な read で prune しない。
     * album 互換のトラック差分判定 (現在ストリーム中の url と比較) に使う。
     ^/
    public static Playing current(ResourceKey<Level> dim, BlockPos pos) {
        final Map<BlockPos, Playing> map = BY_DIM.get(dim);
        return map == null ? null : map.get(pos);
    }

    *///?} else {
    //?}
    public static void stop(ResourceKey<Level> dim, BlockPos pos) {
        final Map<BlockPos, Playing> map = BY_DIM.get(dim);
        if (map != null) {
            map.remove(pos);
            if (map.isEmpty()) {
                BY_DIM.remove(dim);
            }
        }
        ActiveDiscPersistence.onStop(dim, pos.immutable());
    }

    /**
     * 永続層 ({@link ActiveDiscSaveData}) から復元する。chunk 再送の走査の最初に
     * {@link ActiveDiscPersistence#hydrate} が呼ぶ。memory に無い鍵だけ入れる
     * (走査が先に新規検出していた場合でも memory 側が正)。ここでは永続層への書き戻しをしない。
     */
    static void restoreFrom(ResourceKey<Level> dim, java.util.Collection<Playing> saved) {
        final Map<BlockPos, Playing> map =
                BY_DIM.computeIfAbsent(dim, d -> new HashMap<>());
        for (final Playing p : saved) {
            map.putIfAbsent(p.pos(), p);
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
            //? if >=26.1 {
            if (ChunkPos.containing(p.pos()).equals(chunk)) {
            //?} else {
            /*if (new ChunkPos(p.pos()).equals(chunk)) {
            *///?}
                out.add(p);
            }
        }
        return out;
    }

    /**
     * 指定 chunk 内で「まだ再生中」のエントリを返す。曲尺を過ぎたものは<b>覚えたまま返さない</b>
     * (late-joiner へ送るのは今も鳴っているものだけ)。
     *
     * <p>production からは呼ばれなくなったが、prune の再導入を防ぐ番人として意図的に残す
     * ({@link #knownInChunk} を使わずここへ戻すと registry から鳴り終わりエントリが消え、
     * chunk 再送が頭出しに退行する)。
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

    //? if >=1.21.2 {
    public static boolean isTracked(ResourceKey<Level> dim, BlockPos pos) {
        final Map<BlockPos, Playing> map = BY_DIM.get(dim);
        return map != null && map.containsKey(pos.immutable());
    }

    /** 指定 pos の再生中エントリを返す (無ければ null)。アルバム互換の差分検出に使う。 */
    public static Playing current(ResourceKey<Level> dim, BlockPos pos) {
        final Map<BlockPos, Playing> map = BY_DIM.get(dim);
        return map == null ? null : map.get(pos.immutable());
    }

    //?} elif >=1.21 {
    //?} else {
    /*public static boolean isTracked(ResourceKey<Level> dim, BlockPos pos) {
        final Map<BlockPos, Playing> map = BY_DIM.get(dim);
        return map != null && map.containsKey(pos.immutable());
    }

    *///?}
    public static void clear() {
        BY_DIM.clear();
    }

    /**
     * 次元のアンロード時にその次元だけ消去する。次元が落ちるとその中の chunk はもう誰にも
     * watch されないので、chunk 再入を待つ掃除 (chunk 再送の走査) には二度と届かない。
     * 消さないと、その次元のエントリは server が止まるまで残り続ける。
     *
     * @param dim アンロードされた次元
     */
    public static void clear(ResourceKey<Level> dim) {
        BY_DIM.remove(dim);
    }
}

