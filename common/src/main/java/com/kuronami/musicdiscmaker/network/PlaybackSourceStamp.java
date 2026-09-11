package com.kuronami.musicdiscmaker.network;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/** Goldenの永続IDと再生世代。通常jukeboxなどIDを持たない音源はnull IDで送る。 */
public record PlaybackSourceStamp(UUID sourceId, long generation) {
    public static final PlaybackSourceStamp NONE = new PlaybackSourceStamp(null, 0L);
    public PlaybackSourceStamp {
        if (generation < 0L) throw new IllegalArgumentException("Negative playback generation");
    }
    public static PlaybackSourceStamp read(FriendlyByteBuf buf) {
        final UUID id = buf.readBoolean() ? buf.readUUID() : null;
        return new PlaybackSourceStamp(id, buf.readVarLong());
    }
    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(sourceId != null);
        if (sourceId != null) buf.writeUUID(sourceId);
        buf.writeVarLong(generation);
    }
}
