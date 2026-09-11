package com.kuronami.musicdiscmaker.network;

import net.minecraft.network.FriendlyByteBuf;

/** 開いている機体と表示世代に対する操作。位置や機体idだけでは操作先を決めない。 */
public record ControlBoomboxPayload(int containerId, long generation, int action, long value) implements ModPayload {
    public static final String PATH = "control_boombox";
    public static final int PLAY = 0, PAUSE = 1, PREVIOUS = 2, NEXT = 3,
            SEEK = 4, REPEAT = 5, SHUFFLE = 6, VOLUME = 7;

    public static ControlBoomboxPayload read(FriendlyByteBuf buf) {
        return new ControlBoomboxPayload(buf.readVarInt(), buf.readVarLong(), buf.readVarInt(), buf.readVarLong());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(containerId);
        buf.writeVarLong(generation);
        buf.writeVarInt(action);
        buf.writeVarLong(value);
    }
}
