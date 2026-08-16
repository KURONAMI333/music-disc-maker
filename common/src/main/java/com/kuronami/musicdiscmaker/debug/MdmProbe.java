package com.kuronami.musicdiscmaker.debug;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * 一時的な実機診断ログ (蓄音機経路の切り分け専用)。全て INFO で {@code [MDM-PROBE]} 接頭辞を持つ。
 *
 * <p>毎 tick 呼ばれる経路から使うので、出力は<b>立ち上がり/立ち下がりのエッジ</b>と
 * <b>座標ごとのスロットル</b>でしか出さない。原因が確定したらこのクラスごと消す。
 *
 * <p>判定表 (server 側の {@code latest.log} を上から読む):
 * <ol>
 *   <li>{@code hook alive} が 1 行も無い → mixin が当たっていない (注入失敗)。</li>
 *   <li>{@code hook alive} はあるが {@code gramophone state} が出ない → {@code isPlaying} が
 *       server 側で立っていない。</li>
 *   <li>{@code state} に {@code customTrack=false} → 挿したディスクに MDM の track component が無い。</li>
 *   <li>{@code state ... customTrack=true} なのに {@code mirror start} が出ない →
 *       {@link com.kuronami.musicdiscmaker.event.AlbumPlaybackMirror} の dedup に飲まれている
 *       ({@code mirror dedup} 行が出る)。</li>
 *   <li>{@code mirror start ... recipients=0} → 送信先が居ない (chunk tracking の問題)。</li>
 *   <li>{@code mirror start ... recipients>=1} なのに client の {@code play recv} が出ない → 転送層。</li>
 *   <li>{@code play recv} はあるが {@code play skipped} → client 側 dedup。</li>
 * </ol>
 */
public final class MdmProbe {

    private static final String TAG = "[MDM-PROBE] ";
    /** 同じ (座標, 種別) の反復ログを抑える間隔 (ms)。 */
    private static final long THROTTLE_MS = 10_000L;

    private static final AtomicBoolean GRAMOPHONE_HOOK_ALIVE = new AtomicBoolean();
    private static final Map<BlockPos, Boolean> GRAMOPHONE_PLAYING = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_LOG = new ConcurrentHashMap<>();

    private MdmProbe() {
    }

    /**
     * {@code FurnitureGramophoneMixin} の注入先頭から呼ぶ。プロセスで最初の 1 回だけ「当たっている」を
     * 出し、以降は {@code isPlaying} のエッジでだけ状態を出す。
     */
    public static void gramophoneHook(Level level, BlockPos pos, boolean isPlaying,
            @Nullable ItemStack record) {
        if (GRAMOPHONE_HOOK_ALIVE.compareAndSet(false, true)) {
            MusicDiscMaker.LOGGER.info(TAG + "gramophone hook alive: pos={} clientSide={}",
                    pos, level.isClientSide);
        }
        final Boolean was = GRAMOPHONE_PLAYING.put(pos.immutable(), isPlaying);
        if (was != null && was == isPlaying) {
            return; // 状態変化なし = 毎 tick の連打。出さない。
        }
        final ItemStack stack = record == null ? ItemStack.EMPTY : record;
        final CustomTrackData track = stack.isEmpty() ? null : stack.get(ModDataComponents.CUSTOM_TRACK.get());
        MusicDiscMaker.LOGGER.info(
                TAG + "gramophone state: pos={} isPlaying={} item={} customTrack={} url={} clientSide={}",
                pos, isPlaying, stack.isEmpty() ? "<empty>" : stack.getItem(),
                track != null && !track.isEmpty(), track == null ? "-" : track.url(), level.isClientSide);
    }

    /**
     * {@code AlbumPlaybackMirror.mirror} の分岐から呼ぶ。{@code start}/{@code stop} は状態変化なので
     * 毎回出し、{@code dedup}/{@code idle} は毎 tick 走るのでスロットルする。
     *
     * @param branch     {@code start} / {@code stop} / {@code dedup} / {@code idle}
     * @param recipients 送信先プレイヤー数。送信しない分岐では {@code -1}
     */
    public static void mirrorBranch(String branch, ServerLevel level, BlockPos pos, @Nullable String url,
            int recipients) {
        final boolean edge = "start".equals(branch) || "stop".equals(branch);
        if (!edge && !allow(branch, pos)) {
            return;
        }
        MusicDiscMaker.LOGGER.info(TAG + "mirror {}: pos={} url={} recipients={}",
                branch, pos, url == null ? "-" : url, recipients < 0 ? "n/a" : recipients);
    }

    /** その chunk を追っている player 数。数えられなければ -1。 */
    public static int recipients(ServerLevel level, ChunkPos chunk) {
        try {
            return level.getChunkSource().chunkMap.getPlayers(chunk, false).size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    /** client 側 {@code ClientPlaybackManager.startPlayback} の入口。 */
    public static void clientPlayReceived(BlockPos pos, @Nullable CustomTrackData track, long startOffsetMs,
            int rangeBlocks, int volumePercent, boolean directional) {
        MusicDiscMaker.LOGGER.info(
                TAG + "play recv: pos={} url={} offset={}ms range={} vol={} directional={}",
                pos, track == null ? "-" : track.url(), startOffsetMs, rangeBlocks, volumePercent, directional);
    }

    /** client 側でロードを起こさずに帰った (同じ曲の再送とみなした)。 */
    public static void clientPlaySkipped(BlockPos pos, @Nullable CustomTrackData track) {
        MusicDiscMaker.LOGGER.info(TAG + "play skipped (client dedup): pos={} url={}",
                pos, track == null ? "-" : track.url());
    }

    private static boolean allow(String branch, BlockPos pos) {
        final String key = branch + "@" + pos.asLong();
        final long now = System.currentTimeMillis();
        final Long last = LAST_LOG.get(key);
        if (last != null && now - last < THROTTLE_MS) {
            return false;
        }
        LAST_LOG.put(key, now);
        return true;
    }
}
