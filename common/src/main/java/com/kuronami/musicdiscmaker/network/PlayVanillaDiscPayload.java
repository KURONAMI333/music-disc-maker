package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.component.VanillaTrackData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** Golden record audio resolved from the client's resource pack, with server playback position. */
public record PlayVanillaDiscPayload(BlockPos jukeboxPos, VanillaTrackData track, long startOffsetMs,
        int rangeBlocks, int volumePercent, boolean directional, PlaybackSourceStamp identity) implements ModPayload {
    public PlayVanillaDiscPayload(BlockPos pos, VanillaTrackData track, long offset, int range, int volume,
            boolean directional, long generation) {
        this(pos, track, offset, range, volume, directional, new PlaybackSourceStamp(null, generation));
    }
    public long generation() { return identity.generation(); }
    public static final String PATH = "play_vanilla_disc";

    public PlayVanillaDiscPayload {
        jukeboxPos = jukeboxPos.immutable();
        java.util.Objects.requireNonNull(track, "track");
        java.util.Objects.requireNonNull(identity, "identity");
        if (startOffsetMs < 0 || rangeBlocks < 0 || volumePercent < 0) {
            throw new IllegalArgumentException("Negative vanilla playback setting");
        }
    }

    public static PlayVanillaDiscPayload read(FriendlyByteBuf buf) {
        return new PlayVanillaDiscPayload(BlockPos.of(buf.readLong()),
                new VanillaTrackData(buf.readUtf(256), buf.readVarLong()), buf.readVarLong(),
                buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), PlaybackSourceStamp.read(buf));
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(jukeboxPos.asLong());
        buf.writeUtf(track.soundEventId(), 256);
        buf.writeVarLong(track.durationMs());
        buf.writeVarLong(startOffsetMs);
        buf.writeVarInt(rangeBlocks);
        buf.writeVarInt(volumePercent);
        buf.writeBoolean(directional);
        identity.write(buf);
    }
}
