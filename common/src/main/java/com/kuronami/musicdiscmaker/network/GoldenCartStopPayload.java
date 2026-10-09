package com.kuronami.musicdiscmaker.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * server → client: 金ジューク付きトロッコの明示停止。破壊・除去時に撃ち、
 * client 側の lease ({@code SourcePlaybackOwnership}) と再生を畳む。
 * {@code entityId} は停止対象の経路照合に使う (旧 entity の停止が移動後の新しい経路を止めないため)。
 * {@code naturalEnd} は曲を鳴らし切ったことによる停止 ({@link StopDiscPayload#naturalEnd()} と同じ意味)。
 */
public record GoldenCartStopPayload(int entityId, PlaybackSourceStamp identity, boolean naturalEnd)
        implements ModPayload {

    public static final String PATH = "golden_cart_stop";

    /** 曲の途中で止める知らせ。 */
    public GoldenCartStopPayload(int entityId, PlaybackSourceStamp identity) {
        this(entityId, identity, false);
    }

    public static GoldenCartStopPayload read(FriendlyByteBuf buf) {
        return new GoldenCartStopPayload(buf.readVarInt(), PlaybackSourceStamp.read(buf), buf.readBoolean());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        identity.write(buf);
        buf.writeBoolean(naturalEnd);
    }
}
