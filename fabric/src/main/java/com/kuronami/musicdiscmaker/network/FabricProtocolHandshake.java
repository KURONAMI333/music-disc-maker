package com.kuronami.musicdiscmaker.network;

import net.minecraft.server.level.ServerPlayer;

/**
 * B5/C12: fabric の play 段ハンドシェイク。参加した client へ wire 版
 * ({@code ModNetwork.PROTOCOL_VERSION}) を伝える。
 *
 * <p><b>版ゲートが無いのは fabric だけ</b> (NeoForge は registrar 交渉 / Forge 1.20.1 は
 * {@code acceptMissingOr} で login 段に在る)。ここはその play 段版。
 *
 * <p><b>盲送りしない。</b>Fabric API は相手がどの channel を宣言したかを把握しているので、
 * {@code ServerPlayNetworking.canSend} で先にそれを見る。宣言していない相手は MDM 無しか旧 MDM
 * で、どちらにも送る意味がない (旧クライアントを壊す経路が最初から存在しなくなる)。
 *
 * <p>channel API は 1.20.5 前後で変わる (String id → payload type) ので、ここで帯分岐する。
 */
public final class FabricProtocolHandshake {

    private FabricProtocolHandshake() {
    }

    /**
     * プレイヤー参加時に呼ぶ ({@code ServerPlayConnectionEvents.JOIN})。
     * 相手が channel を宣言しているときだけ版を送る。
     */
    public static void sendTo(ServerPlayer player) {
        //? if >=1.21 {
        if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player,
                ModPayloadTypes.Version.TYPE)) {
            return; // 相手は MDM 無しか旧 MDM。handshake を送っても意味がない
        }
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                ModPayloadTypes.wrap(new VersionPayload(ModNetwork.PROTOCOL_VERSION)));
        //?} else {
        /*final net.minecraft.resources.ResourceLocation channelId =
                LegacyPayloadIds.of(VersionPayload.PATH);
        if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, channelId)) {
            return; // 相手は MDM 無しか旧 MDM。handshake を送っても意味がない
        }
        final net.minecraft.network.FriendlyByteBuf buf =
                new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeUtf(ModNetwork.PROTOCOL_VERSION);
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, channelId, buf);
        */
        //?}
    }
}
