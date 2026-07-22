package com.kuronami.musicdiscmaker;

import com.kuronami.musicdiscmaker.event.ActiveDiscRegistry;
import com.kuronami.musicdiscmaker.event.FabricJukeboxEvents;
import com.kuronami.musicdiscmaker.network.ConfigureJukeboxPayload;
import com.kuronami.musicdiscmaker.network.ModNetwork;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.ResolveUrlPayload;
import com.kuronami.musicdiscmaker.network.SeekJukeboxPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.register.ModCreativeTab;
import com.kuronami.musicdiscmaker.register.ModRegistries;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

/**
 * Fabric entry。common ホルダ ({@code Mod*}) を {@code ModRegistries.init()} で touch して即時登録し
 * (onInitialize 中はレジストリが開いている)、payload 型の登録・受信配線・creative タブ内容・jukebox
 * イベント・server 停止フックを設定する。
 */
public class MusicDiscMakerFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // common ホルダの static 初期化を依存順に touch → Registry.register で即時登録される。
        ModRegistries.init();

        // payload 型を登録する。
        // 26.2 fabric-networking: playC2S/playS2C は serverboundPlay/clientboundPlay に rename。
        PayloadTypeRegistry.serverboundPlay().register(ResolveUrlPayload.TYPE, ResolveUrlPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ConfigureJukeboxPayload.TYPE, ConfigureJukeboxPayload.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SeekJukeboxPayload.TYPE, SeekJukeboxPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(PlayDiscPayload.TYPE, PlayDiscPayload.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StopDiscPayload.TYPE, StopDiscPayload.STREAM_CODEC);

        // server 受信: URL コミット (main thread へ enqueue して BlockEntity に保存)。
        ServerPlayNetworking.registerGlobalReceiver(ResolveUrlPayload.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> ModNetwork.handleResolveUrl(payload, ctx.player())));
        // server 受信: 強化版ジュークボックスの設定適用。
        ServerPlayNetworking.registerGlobalReceiver(ConfigureJukeboxPayload.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> ModNetwork.handleConfigureJukebox(payload, ctx.player())));
        // server 受信: 強化版ジュークボックスのシークバー頭出し。
        ServerPlayNetworking.registerGlobalReceiver(SeekJukeboxPayload.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> ModNetwork.handleSeekJukebox(payload, ctx.player())));

        // creative タブ内容: common は tab の identity のみ作る (26.2 の Output が protected)。
        // 26.2 fabric-api は旧 ItemGroupEvents を廃し CreativeModeTabEvents に統合。表示順で TAB_ITEMS を流す。
        CreativeModeTabEvents.modifyOutputEvent(
                        ResourceKey.create(Registries.CREATIVE_MODE_TAB, ModCreativeTab.MAIN.getId()))
                .register(output -> ModCreativeTab.TAB_ITEMS.forEach(holder -> output.accept(new ItemStack(holder.get()))));

        // jukebox 出し入れ / 破壊イベント。
        FabricJukeboxEvents.register();

        // server 停止で再生中状態を破棄 (シングルプレイのワールド退出含む)。
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ActiveDiscRegistry.clear());

        MusicDiscMaker.LOGGER.info("Music Disc Maker (Fabric) initialized");
    }
}
