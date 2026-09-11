package com.kuronami.musicdiscmaker.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** 音源ごとの聴取点更新。Playと分け、設定変更のたびに音声をロードし直さない。 */
public record SpeakerSetPayload(BlockPos sourcePos, int rangeBlocks, int volumePercent,
        boolean directional, List<SpeakerEntry> speakers, PlaybackSourceStamp identity) implements ModPayload {
    public SpeakerSetPayload(BlockPos pos, int range, int volume, boolean directional, List<SpeakerEntry> speakers) {
        this(pos, range, volume, directional, speakers, PlaybackSourceStamp.NONE);
    }
    public static final String PATH = "speaker_set";

    public SpeakerSetPayload {
        sourcePos = sourcePos.immutable();
        speakers = List.copyOf(speakers);
    }

    public static SpeakerSetPayload read(FriendlyByteBuf buf) {
        final BlockPos source = BlockPos.of(buf.readLong());
        final int range = buf.readVarInt();
        final int volume = buf.readVarInt();
        final boolean directional = buf.readBoolean();
        final int count = buf.readVarInt();
        // 1件の最小サイズは座標8 + volume1 + mute1 + orientation1 bytes。
        if (count < 0 || count > buf.readableBytes() / 11) {
            throw new IllegalArgumentException("Invalid speaker entry count: " + count);
        }
        final List<SpeakerEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new SpeakerEntry(BlockPos.of(buf.readLong()), buf.readVarInt(),
                    buf.readBoolean(), buf.readVarInt()));
        }
        return new SpeakerSetPayload(source, range, volume, directional, entries, PlaybackSourceStamp.read(buf));
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(sourcePos.asLong());
        buf.writeVarInt(rangeBlocks);
        buf.writeVarInt(volumePercent);
        buf.writeBoolean(directional);
        buf.writeVarInt(speakers.size());
        for (SpeakerEntry entry : speakers) {
            buf.writeLong(entry.pos().asLong());
            buf.writeVarInt(entry.volumePercent());
            buf.writeBoolean(entry.muted());
            buf.writeVarInt(entry.orientation());
        }
        identity.write(buf);
    }
}
