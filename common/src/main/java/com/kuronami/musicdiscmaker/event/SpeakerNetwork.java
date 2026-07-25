package com.kuronami.musicdiscmaker.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.network.SpeakerEntry;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 音源 (強化版ジュークボックス) → ぶら下がるスピーカー集合の server 揮発 逆引き index。
 *
 * <p>永続の正本は各 {@link SpeakerBlockEntity} の NBT で、この index はロード中のスピーカーだけを
 * 保持する。BE の {@code onLoad} / {@code setRemoved} が自己登録・解除するので、chunk のロード状態を
 * そのまま反映する。{@link ActiveDiscRegistry} と同じ規律 (server thread only・再起動で消える)。
 *
 * <p>用途は 2 つ。① {@code broadcast} の宛先計算 (音源チャンクを追跡していない遠方スピーカー傍の
 * player へ payload を届ける) ② スピーカー集合が変わった時に client へ再送する起点。
 */
public final class SpeakerNetwork {

    private static final Map<ResourceKey<Level>, Map<BlockPos, Set<BlockPos>>> INDEX = new HashMap<>();

    private SpeakerNetwork() {
    }

    public static void register(ServerLevel level, BlockPos speakerPos, BlockPos sourcePos) {
        INDEX.computeIfAbsent(level.dimension(), k -> new HashMap<>())
                .computeIfAbsent(sourcePos.immutable(), k -> new LinkedHashSet<>())
                .add(speakerPos.immutable());
    }

    public static void unregister(ServerLevel level, BlockPos speakerPos, BlockPos sourcePos) {
        final Map<BlockPos, Set<BlockPos>> bySource = INDEX.get(level.dimension());
        if (bySource == null) {
            return;
        }
        final Set<BlockPos> speakers = bySource.get(sourcePos);
        if (speakers == null) {
            return;
        }
        speakers.remove(speakerPos);
        if (speakers.isEmpty()) {
            bySource.remove(sourcePos);
        }
    }

    /** 音源にぶら下がるロード中スピーカーの位置 (ミュート・上限フィルタ前)。 */
    public static Set<BlockPos> speakersOf(ServerLevel level, BlockPos sourcePos) {
        final Map<BlockPos, Set<BlockPos>> bySource = INDEX.get(level.dimension());
        if (bySource == null) {
            return Collections.emptySet();
        }
        final Set<BlockPos> speakers = bySource.get(sourcePos);
        return speakers == null ? Collections.emptySet() : Collections.unmodifiableSet(speakers);
    }

    /** 音源に既にぶら下がっているスピーカー台数 (リンク上限の判定に使う)。 */
    public static int countFor(ServerLevel level, BlockPos sourcePos) {
        return speakersOf(level, sourcePos).size();
    }

    /**
     * client へ送る有効スピーカー集合。ミュート中・BE が引けないものを除き、config の 1 音源あたり
     * 上限で切る (上限はリンク時にも弾くので、ここは防御的な二重の歯止め)。
     */
    public static List<SpeakerEntry> activeEntries(ServerLevel level, BlockPos sourcePos) {
        final Set<BlockPos> speakers = speakersOf(level, sourcePos);
        if (speakers.isEmpty()) {
            return List.of();
        }
        final int cap = Config.maxSpeakersPerSource();
        final List<SpeakerEntry> entries = new ArrayList<>(Math.min(speakers.size(), cap));
        for (final BlockPos pos : speakers) {
            if (entries.size() >= cap) {
                break;
            }
            if (!(level.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) || speaker.isMuted()) {
                continue;
            }
            entries.add(new SpeakerEntry(pos, speaker.getVolumePercent(), speaker.getRangeBlocks()));
        }
        return entries;
    }

    /** スピーカー集合が変わったので、音源に client 側の集合を更新させる。音源が未ロードなら何もしない。 */
    public static void notifySource(ServerLevel level, BlockPos sourcePos) {
        if (!level.isLoaded(sourcePos)) {
            return; // 強制ロードしない (鯖負荷とプレイヤーの期待の両方に反する)
        }
        if (level.getBlockEntity(sourcePos) instanceof GoldenJukeboxBlockEntity jukebox) {
            jukebox.broadcastSpeakerSet();
        }
    }

    /** server 停止で index を破棄する (シングルプレイのワールド退出含む)。 */
    public static void clear() {
        INDEX.clear();
    }
}
