package com.kuronami.musicdiscmaker.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * server → client: 金のジュークボックス付きトロッコが載せた金のジュークボックスの状態。
 *
 * <p>設置した金のジュークボックスが block entity の同期で送るのと同じ tag
 * ({@code GoldenJukeboxBlockEntity#mountedStateTag}) を、GUI を開いている player へ届ける。
 * client はトロッコの載せた BE に読み込み、同じ画面がそれを表示する。
 */
public record GoldenCartSyncPayload(int entityId, CompoundTag state) implements ModPayload {

    public static final String PATH = "golden_cart_sync";

    public static GoldenCartSyncPayload read(FriendlyByteBuf buf) {
        final int entityId = buf.readVarInt();
        final CompoundTag state = buf.readNbt();
        return new GoldenCartSyncPayload(entityId, state == null ? new CompoundTag() : state);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeNbt(state);
    }
}
