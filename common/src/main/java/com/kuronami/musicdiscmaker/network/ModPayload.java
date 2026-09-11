package com.kuronami.musicdiscmaker.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * loader / MC 版に依存しない payload の中立形。common はこの形だけを知り、
 * 各版の packet 機構への適合 (1.20.5+ の {@code CustomPacketPayload} / 1.20.1 の生 channel) は
 * loader 側が行う。
 *
 * <p>signature に id を置かないのは、id の型が版で改名されているため
 * (1.20.1 = {@code ResourceLocation} / 1.21.11 以降 = {@code Identifier})。
 * common が持つのは各 payload の {@code PATH} 文字列だけで、名前空間つきの id は loader が組み立てる。
 *
 * <p>buf 型は3版に共通して存在する {@link FriendlyByteBuf} を使う
 * ({@code RegistryFriendlyByteBuf} はこれを継承しているので modern 側でもそのまま渡せる)。
 * 書き込むのは String / 数値 / boolean / BlockPos だけで registry access を要さない。
 */
public interface ModPayload {

    /** buf へ書き出す。読み出しは各 payload の {@code static read(FriendlyByteBuf)}。 */
    void write(FriendlyByteBuf buf);
}
