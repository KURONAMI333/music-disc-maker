package com.kuronami.musicdiscmaker.event;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import net.minecraft.core.BlockPos;

/**
 * {@link ActiveDiscRegistry} の永続化ファサード (B4)。registry が in-memory のままだと、
 * server 再起動後に chunk 再送の走査が鳴り終わりのディスクを「まだ始まっていない」と読んで
 * offset 0 で再生し直していた。このクラスが registry の start/stop を
 * {@link ActiveDiscSaveData} (次元ごとの SavedData) へ書き写し、再起動後の最初の chunk 走査で
 * 復元する。
 *
 * <h2>発火のしかた</h2>
 * <ul>
 *   <li>{@link #hydrate} — 各ローダーの chunk 再送処理 (neoforge/fabric/forge) の先頭から呼ぶ。
 *       server 参照はここで初めて捕まる (lazy)。1 server につき次元 1 回だけ実行する</li>
 *   <li>{@code onStart} / {@code onStop} — {@link ActiveDiscRegistry} が start/stop の内部から呼ぶ。
 *       hydrate が一度も走っていない (server 参照が無い) 間は no-op なので、GameTest のような
 *       chunk 走査を通らない経路では永続化も発生しない</li>
 * </ul>
 *
 * <p>registry の {@code clear()} / {@code clear(dim)} は永続層を消さない。server 停止で消えるのが
 * この修正の本体なので、消すのはディスクがスロットから外れた時だけである。
 */
public final class ActiveDiscPersistence {

    private static MinecraftServer server;
    private static final Set<ResourceKey<Level>> HYDRATED = new HashSet<>();

    private ActiveDiscPersistence() {
    }

    /**
     * chunk 再送の走査の最初に呼ぶ。その次元の SavedData を memory registry へ復元する。
     * server 参照が変わっていたら (シングルプレイでワールドを開き直した等) 状態をリセットする。
     */
    public static void hydrate(ServerLevel level) {
        final MinecraftServer current = level.getServer();
        if (server != current) {
            server = current;
            HYDRATED.clear();
        }
        final ResourceKey<Level> dim = level.dimension();
        if (!HYDRATED.add(dim)) {
            return;
        }
        ActiveDiscRegistry.restoreFrom(dim, ActiveDiscSaveData.get(level).entries());
    }

    /** registry の start からの転送。server を捕まえる前は no-op。 */
    static void onStart(ResourceKey<Level> dim, BlockPos pos,
            com.kuronami.musicdiscmaker.component.CustomTrackData track, long startMillis) {
        final ServerLevel level = levelOf(dim);
        if (level != null) {
            ActiveDiscSaveData.get(level).put(pos, track, startMillis);
        }
    }

    /** registry の stop からの転送。ディスクがスロットから消えたので保存側も消す。 */
    static void onStop(ResourceKey<Level> dim, BlockPos pos) {
        final ServerLevel level = levelOf(dim);
        if (level != null) {
            ActiveDiscSaveData.get(level).remove(pos);
        }
    }

    private static ServerLevel levelOf(ResourceKey<Level> dim) {
        final MinecraftServer s = server;
        return s == null ? null : s.getLevel(dim);
    }
}
