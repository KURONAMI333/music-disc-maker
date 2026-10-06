package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.component.GoldenCartState;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;

import net.minecraft.network.FriendlyByteBuf;

/**
 * server → client: 開いている cart menu へ再生状態を届ける。64bit の世代や時刻を
 * 途中の値として表示しない ({@link BoomboxStatePayload} と同じ一発運び)。
 */
public record GoldenCartStatePayload(int containerId, int entityId, GoldenCartState state)
        implements ModPayload {

    public static final String PATH = "golden_cart_state";

    public static GoldenCartStatePayload read(FriendlyByteBuf buf) {
        final int containerId = buf.readVarInt();
        final int entityId = buf.readVarInt();
        final int disc = buf.readVarInt();
        final int track = buf.readVarInt();
        final int ordinal = buf.readUnsignedByte();
        if (ordinal >= PlaybackCursor.State.values().length) {
            throw new IllegalArgumentException("Invalid playback state");
        }
        final PlaybackCursor cursor = new PlaybackCursor(disc, track,
                PlaybackCursor.State.values()[ordinal], buf.readVarLong());
        return new GoldenCartStatePayload(containerId, entityId, new GoldenCartState(cursor,
                buf.readVarLong(), buf.readBoolean(), buf.readBoolean(),
                buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(containerId);
        buf.writeVarInt(entityId);
        buf.writeVarInt(state.cursor().discIndex());
        buf.writeVarInt(state.cursor().trackIndex());
        buf.writeByte(state.cursor().state().ordinal());
        buf.writeVarLong(state.cursor().generation());
        buf.writeVarLong(state.elapsedMs());
        buf.writeBoolean(state.repeat());
        buf.writeBoolean(state.shuffle());
        buf.writeVarInt(state.volumePercent());
        buf.writeVarInt(state.rangeBlocks());
        buf.writeBoolean(state.directional());
    }
}
