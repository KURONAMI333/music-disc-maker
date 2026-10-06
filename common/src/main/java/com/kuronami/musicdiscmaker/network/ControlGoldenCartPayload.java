package com.kuronami.musicdiscmaker.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * client → server: 開いている金ジューク cart の操作要求。{@link ControlBoomboxPayload} と同じ
 * 「menu + 世代」検証に加え、対象を {@code entityId} で照合する (座標で照合できない移動音源)。
 */
public record ControlGoldenCartPayload(int entityId, int containerId, long generation,
        int action, long value) implements ModPayload {

    public static final String PATH = "control_golden_cart";

    public static final int PLAY = 0;
    public static final int PAUSE = 1;
    public static final int PREVIOUS = 2;
    public static final int NEXT = 3;
    public static final int SEEK = 4;
    public static final int REPEAT = 5;
    public static final int SHUFFLE = 6;
    public static final int VOLUME = 7;
    public static final int RANGE = 8;
    public static final int DIRECTIONAL = 9;

    public static ControlGoldenCartPayload read(FriendlyByteBuf buf) {
        return new ControlGoldenCartPayload(buf.readVarInt(), buf.readVarInt(),
                buf.readVarLong(), buf.readVarInt(), buf.readVarLong());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeVarInt(containerId);
        buf.writeVarLong(generation);
        buf.writeVarInt(action);
        buf.writeVarLong(value);
    }
}
