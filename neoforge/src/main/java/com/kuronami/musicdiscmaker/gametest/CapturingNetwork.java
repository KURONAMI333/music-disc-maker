package com.kuronami.musicdiscmaker.gametest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/**
 * GameTest 用の {@link INetworkHelper}。実際には送らず「何を誰に送ろうとしたか」だけ記録する。
 *
 * <p>2 つの理由でこれが要る。① GameTest の mock プレイヤーは NeoForge のチャンネル交渉を通って
 * いないので、実 payload を送ると即例外になる ② 「payload が正しい宛先へ行くか」は build や
 * 通常の GameTest では一切見えない面で、実際に穴が出た面でもある (P2 の配送漏れ)。
 */
public final class CapturingNetwork implements INetworkHelper {

    /** 1 回の送信要求。 */
    public record Sent(String kind, CustomPacketPayload payload, ChunkPos chunk, Entity entity) {
    }

    private final List<Sent> sent = new ArrayList<>();
    /** テストが仕込む「このチャンクはこの player たちが追跡している」。実 server の追跡集合の代わり。 */
    private final Map<ChunkPos, List<ServerPlayer>> watchers = new HashMap<>();

    public List<Sent> sent() {
        return sent;
    }

    /**
     * チャンクの追跡 player を仕込む。headless の GameTest server には接続 player が居ないので、
     * 「複数チャンクを同じ player が追跡している」配置はここで作る。
     */
    public void watch(ChunkPos chunk, ServerPlayer... players) {
        watchers.computeIfAbsent(chunk, k -> new ArrayList<>()).addAll(List.of(players));
    }

    /**
     * 指定 payload 型を「誰に何通送ったか」。宛先 player ごとの通数。
     *
     * <p>{@link #chunksOf} はチャンク集合へ畳むので player 単位の重複が構造的に見えない。
     * 実際の受け手は player なので、配送の検査はこの粒度で置く。
     */
    public Map<ServerPlayer, Integer> countPerPlayer(Class<? extends CustomPacketPayload> type) {
        final Map<ServerPlayer, Integer> counts = new LinkedHashMap<>();
        for (final Sent s : of(type)) {
            if (s.entity() instanceof ServerPlayer player) {
                counts.merge(player, 1, Integer::sum);
            }
        }
        return counts;
    }

    /** 指定 payload 型に対する送信要求だけを取り出す。 */
    public List<Sent> of(Class<? extends CustomPacketPayload> type) {
        return sent.stream().filter(s -> type.isInstance(s.payload())).toList();
    }

    /** 指定 payload 型が撃たれたチャンクの集合。 */
    public Set<ChunkPos> chunksOf(Class<? extends CustomPacketPayload> type) {
        final Set<ChunkPos> chunks = new LinkedHashSet<>();
        for (final Sent s : of(type)) {
            if (s.chunk() != null) {
                chunks.add(s.chunk());
            }
        }
        return chunks;
    }

    public void clear() {
        sent.clear();
    }

    @Override
    public Collection<ServerPlayer> playersTrackingChunk(ServerLevel level, ChunkPos chunk) {
        return watchers.getOrDefault(chunk, List.of());
    }

    /**
     * 宛先チャンク集合も記録したうえで、本来の player 単位の畳み込み配送を実行する。
     *
     * <p>チャンクの記録は「遠方スピーカーのチャンクが宛先に入っているか」を見る既存テスト用、
     * player 単位の記録 ({@code sendToPlayer} 経由) は「同じ player に何通行ったか」を見る用。
     * 両方要る — 前者だけだと重複配送が構造的に見えない。
     */
    @Override
    public void sendToPlayersTrackingChunks(ServerLevel level, Collection<ChunkPos> chunks,
            CustomPacketPayload payload) {
        for (final ChunkPos chunk : chunks) {
            sent.add(new Sent("chunk", payload, chunk, null));
        }
        INetworkHelper.super.sendToPlayersTrackingChunks(level, chunks, payload);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        sent.add(new Sent("server", payload, null, null));
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        sent.add(new Sent("player", payload, null, player));
    }

    @Override
    public void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos chunk, CustomPacketPayload payload) {
        sent.add(new Sent("chunk", payload, chunk, null));
    }

    @Override
    public void sendToPlayersTrackingEntityAndSelf(Entity entity, CustomPacketPayload payload) {
        sent.add(new Sent("entityAndSelf", payload, null, entity));
    }
}
