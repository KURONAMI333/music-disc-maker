package com.kuronami.musicdiscmaker;

import com.kuronami.musicdiscmaker.event.ActiveDiscRegistry;
import com.kuronami.musicdiscmaker.event.FabricJukeboxEvents;
import com.kuronami.musicdiscmaker.network.ConfigureJukeboxPayload;
import com.kuronami.musicdiscmaker.network.ModNetwork;
import com.kuronami.musicdiscmaker.network.ResolveUrlPayload;
import com.kuronami.musicdiscmaker.register.ModRegistries;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Fabric entry。common ホルダ ({@code Mod*}) を {@code ModRegistries.init()} で touch して即時登録し
 * (onInitialize 中はレジストリが開いている)、受信配線・jukebox イベント・server 停止フックを設定する。
 *
 * <p>1.20.1 の networking は ResourceLocation + FriendlyByteBuf の旧 API。受信時は netty thread で
 * 即座に buf を読み、main thread へ enqueue して common の {@link ModNetwork} ハンドラへ委譲する。
 */
public class MusicDiscMakerFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // common ホルダの static 初期化を依存順に touch → Registry.register で即時登録される。
        ModRegistries.init();

        // server 受信: URL コミット (BlockEntity に保存 → 条件が揃えば自動生成)。
        ServerPlayNetworking.registerGlobalReceiver(ResolveUrlPayload.ID,
                (server, player, handler, buf, responseSender) -> {
                    final ResolveUrlPayload payload = ResolveUrlPayload.read(buf);
                    server.execute(() -> ModNetwork.handleResolveUrl(payload, player));
                });

        // server 受信: 強化版ジュークボックスの設定変更。
        ServerPlayNetworking.registerGlobalReceiver(ConfigureJukeboxPayload.ID,
                (server, player, handler, buf, responseSender) -> {
                    final ConfigureJukeboxPayload payload = ConfigureJukeboxPayload.read(buf);
                    server.execute(() -> ModNetwork.handleConfigureJukebox(payload, player));
                });

        // jukebox 出し入れ / 破壊イベント。
        FabricJukeboxEvents.register();

        // server 停止で再生中状態を破棄 (シングルプレイのワールド退出含む)。
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ActiveDiscRegistry.clear());

        MusicDiscMaker.LOGGER.info("Music Disc Maker (Fabric) initialized");
    }
}
