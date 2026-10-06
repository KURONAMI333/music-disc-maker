package com.kuronami.musicdiscmaker.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * server → client: 金ジューク付きトロッコの明示停止。破壊・除去時に撃ち、
 * client 側の lease ({@code SourcePlaybackOwnership}) と再生を畳む。
 * {@code entityId} は停止対象の経路照合に使う (旧 entity の停止が移動後の新しい経路を止めないため)。
 */
public record GoldenCartStopPayload(int entityId, PlaybackSourceStamp identity) implements ModPayload {

    public static final String PATH = "golden_cart_stop";

    public static GoldenCartStopPayload read(FriendlyByteBuf buf) {
        return new GoldenCartStopPayload(buf.readVarInt(), PlaybackSourceStamp.read(buf));
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        identity.write(buf);
    }
}
