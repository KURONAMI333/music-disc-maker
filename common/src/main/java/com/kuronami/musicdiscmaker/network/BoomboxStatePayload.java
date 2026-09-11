package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.component.BoomboxPlaybackState;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import net.minecraft.network.FriendlyByteBuf;

/** 画面状態を一つのpacketで送る。64bitの世代や時刻を途中の値として表示しない。 */
public record BoomboxStatePayload(int containerId, BoomboxPlaybackState state) implements ModPayload {
    public static final String PATH = "boombox_state";

    public static BoomboxStatePayload read(FriendlyByteBuf buf) {
        final int containerId = buf.readVarInt();
        final int disc = buf.readVarInt();
        final int track = buf.readVarInt();
        final int ordinal = buf.readUnsignedByte();
        if (ordinal >= PlaybackCursor.State.values().length) throw new IllegalArgumentException("Invalid playback state");
        final PlaybackCursor cursor = new PlaybackCursor(disc, track, PlaybackCursor.State.values()[ordinal], buf.readVarLong());
        return new BoomboxStatePayload(containerId, new BoomboxPlaybackState(cursor, buf.readVarLong(),
                buf.readBoolean(), buf.readBoolean(), buf.readVarInt()));
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(containerId);
        buf.writeVarInt(state.cursor().discIndex());
        buf.writeVarInt(state.cursor().trackIndex());
        buf.writeByte(state.cursor().state().ordinal());
        buf.writeVarLong(state.cursor().generation());
        buf.writeVarLong(state.elapsedMs());
        buf.writeBoolean(state.repeat());
        buf.writeBoolean(state.shuffle());
        buf.writeVarInt(state.volumePercent());
    }
}
