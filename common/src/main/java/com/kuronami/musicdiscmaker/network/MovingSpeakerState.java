package com.kuronami.musicdiscmaker.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/** World-space receivers remain usable when the moving entity is outside client tracking range. */
public record MovingSpeakerState(Vec3 sourcePosition, List<SpeakerEntry> speakers) {
    public MovingSpeakerState {
        if (!Double.isFinite(sourcePosition.x) || !Double.isFinite(sourcePosition.y) || !Double.isFinite(sourcePosition.z)) {
            throw new IllegalArgumentException("Non-finite moving source position");
        }
        speakers = List.copyOf(speakers);
    }

    public static MovingSpeakerState empty(BlockPos localPos) {
        return new MovingSpeakerState(Vec3.atCenterOf(localPos), List.of());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeDouble(sourcePosition.x);
        buf.writeDouble(sourcePosition.y);
        buf.writeDouble(sourcePosition.z);
        buf.writeVarInt(speakers.size());
        for (SpeakerEntry entry : speakers) {
            buf.writeBlockPos(entry.pos());
            buf.writeVarInt(entry.volumePercent());
            buf.writeBoolean(entry.muted());
            buf.writeVarInt(entry.orientation());
        }
    }

    public static MovingSpeakerState read(FriendlyByteBuf buf) {
        final Vec3 source = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        final int count = buf.readVarInt();
        if (count < 0 || count > buf.readableBytes() / 11) throw new IllegalArgumentException("Invalid moving speaker count");
        final List<SpeakerEntry> speakers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) speakers.add(new SpeakerEntry(buf.readBlockPos(), buf.readVarInt(), buf.readBoolean(), buf.readVarInt()));
        return new MovingSpeakerState(source, speakers);
    }

    public boolean reaches(Vec3 listener, int range, int sourceVolume, boolean alreadyListening) {
        final double limit = (double) range * range;
        if ((alreadyListening || sourceVolume > 0) && listener.distanceToSqr(sourcePosition) <= limit) return true;
        for (SpeakerEntry speaker : speakers) {
            if ((alreadyListening || (!speaker.muted() && speaker.volumePercent() > 0))
                    && listener.distanceToSqr(Vec3.atCenterOf(speaker.pos())) <= limit) return true;
        }
        return false;
    }
}
