package com.kuronami.musicdiscmaker.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * loader 非依存の自前 payload (1.20.1 には CustomPacketPayload が無いため)。
 * 各ローダーがこの {@link #id()} で channel を引き、{@link #write(FriendlyByteBuf)} で buf を埋める。
 * 受信側は各 payload の {@code static read(FriendlyByteBuf)} で復元して {@code ModNetwork} のハンドラへ渡す。
 */
public interface ModPayload {

    ResourceLocation id();

    void write(FriendlyByteBuf buf);
}
