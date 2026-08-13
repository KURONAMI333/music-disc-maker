package com.kuronami.musicdiscmaker.gametest;

import java.util.ArrayList;
import java.util.List;

import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * GameTest 用の {@link INetworkHelper}。実際には送らず「何を誰に送ろうとしたか」だけ記録する。
 *
 * <p>2 つの理由でこれが要る。① GameTest の mock プレイヤーは NeoForge のチャンネル交渉を通って
 * いないので、実 payload を送ると即例外になる ② payload の中身 (再生 offset) は build や通常の
 * GameTest では一切見えない面で、実際に穴が出た面でもある (chunk 再送で offset 0 に巻き戻る)。
 */
public final class CapturingNetwork implements INetworkHelper {

    /** 1 回の送信要求。{@code chunk} は chunk 宛て、{@code player} は player 宛てのときだけ入る。 */
    public record Sent(String kind, CustomPacketPayload payload, ChunkPos chunk, ServerPlayer player) {
    }

    private final List<Sent> sent = new ArrayList<>();

    public List<Sent> sent() {
        return sent;
    }

    /** 指定 payload 型に対する送信要求だけを取り出す。 */
    public List<Sent> of(Class<? extends CustomPacketPayload> type) {
        return sent.stream().filter(s -> type.isInstance(s.payload())).toList();
    }

    public void clear() {
        sent.clear();
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
}
