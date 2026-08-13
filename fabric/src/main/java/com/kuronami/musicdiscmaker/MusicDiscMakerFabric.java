package com.kuronami.musicdiscmaker;

import com.kuronami.musicdiscmaker.event.ActiveDiscRegistry;
import com.kuronami.musicdiscmaker.event.FabricJukeboxEvents;
import com.kuronami.musicdiscmaker.network.ConfigureJukeboxPayload;
import com.kuronami.musicdiscmaker.network.ModNetwork;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.ResolveUrlPayload;
import com.kuronami.musicdiscmaker.network.SeekJukeboxPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.register.ModRegistries;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * Fabric entry。common ホルダ ({@code Mod*}) を {@code ModRegistries.init()} で touch して即時登録し
 * (onInitialize 中はレジストリが開いている)、payload 型の登録・受信配線・jukebox イベント・server 停止
 * フックを設定する。
 */
public class MusicDiscMakerFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // common ホルダの static 初期化を依存順に touch → Registry.register で即時登録される。
        ModRegistries.init();

        // payload 型を登録する。
        PayloadTypeRegistry.playC2S().register(ResolveUrlPayload.TYPE, ResolveUrlPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ConfigureJukeboxPayload.TYPE, ConfigureJukeboxPayload.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(SeekJukeboxPayload.TYPE, SeekJukeboxPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(PlayDiscPayload.TYPE, PlayDiscPayload.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(StopDiscPayload.TYPE, StopDiscPayload.STREAM_CODEC);
        // SB Fabric port の backpack jukebox 用 (port 非依存。port が無ければ送信されないだけ)。
        com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompat.registerPayload();

        // server 受信: URL コミット (main thread へ enqueue して BlockEntity に保存)。
        ServerPlayNetworking.registerGlobalReceiver(ResolveUrlPayload.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> ModNetwork.handleResolveUrl(payload, ctx.player())));
        // server 受信: 強化版ジュークボックスの設定適用。
        ServerPlayNetworking.registerGlobalReceiver(ConfigureJukeboxPayload.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> ModNetwork.handleConfigureJukebox(payload, ctx.player())));
        // server 受信: 強化版ジュークボックスのシークバー頭出し。
        ServerPlayNetworking.registerGlobalReceiver(SeekJukeboxPayload.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> ModNetwork.handleSeekJukebox(payload, ctx.player())));

        // jukebox 出し入れ / 破壊イベント。
        FabricJukeboxEvents.register();

        // server 停止で再生中状態を破棄 (シングルプレイのワールド退出含む)。
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ActiveDiscRegistry.clear());

        // 次元がアンロードされたらその次元のエントリを捨てる。次元が落ちると chunk はもう誰にも
        // watch されず、chunk 再入を待つ掃除には二度と届かない (server 停止まで残り続ける)。
        ServerWorldEvents.UNLOAD.register((server, world) -> ActiveDiscRegistry.clear(world.dimension()));

        MusicDiscMaker.LOGGER.info("Music Disc Maker (Fabric) initialized");
    }
}
