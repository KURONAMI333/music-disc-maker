package com.kuronami.musicdiscmaker.network;

import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;

/**
 * MP3専用の小分け転送。各方向で許可されたopcodeだけをハンドラが受理する。
 * パスは存在せず、hashは固定長、曲名はベース名だけ、chunkは最大16KiB。
 */
public record LocalAudioPayload(int operation, UUID id, int containerId, long generation,
        String hash, long offset, long size, String name, byte[] bytes) implements ModPayload {
    public static final String PATH = "local_audio";
    public static final UUID NONE = new UUID(0, 0);
    public static final int BEGIN = 0, CHUNK = 1, CANCEL = 2, FETCH = 3, NEXT = 4;
    public static final int READY = 5, ACK = 6, DONE = 7, ERROR = 8, DATA = 9;
    public LocalAudioPayload {
        if (operation < BEGIN || operation > DATA || hash.length() > 64 || name.length() > 256
                || bytes.length > 16384) throw new IllegalArgumentException("Invalid audio packet");
        bytes = bytes.clone();
    }
    public static LocalAudioPayload control(int op, UUID id, int menu, long generation,
            String hash, long offset, long size, String name) {
        return new LocalAudioPayload(op, id, menu, generation, hash, offset, size, name, new byte[0]);
    }
    public static LocalAudioPayload read(FriendlyByteBuf b) {
        return new LocalAudioPayload(b.readVarInt(), b.readUUID(), b.readVarInt(), b.readLong(),
                b.readUtf(64), b.readLong(), b.readLong(), b.readUtf(256), b.readByteArray(16384));
    }
    public void write(FriendlyByteBuf b) {
        b.writeVarInt(operation); b.writeUUID(id); b.writeVarInt(containerId); b.writeLong(generation);
        b.writeUtf(hash, 64); b.writeLong(offset); b.writeLong(size); b.writeUtf(name, 256); b.writeByteArray(bytes);
    }
}
