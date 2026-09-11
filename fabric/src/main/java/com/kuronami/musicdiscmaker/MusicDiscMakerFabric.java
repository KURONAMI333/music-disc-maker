package com.kuronami.musicdiscmaker;

import com.kuronami.musicdiscmaker.event.ActiveDiscRegistry;
import com.kuronami.musicdiscmaker.event.FabricJukeboxEvents;
//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.network.ModPayloadTypes;
//?} else {
/*import com.kuronami.musicdiscmaker.network.ConfigureJukeboxPayload;
*/
//?}
import com.kuronami.musicdiscmaker.network.ModNetwork;
import com.kuronami.musicdiscmaker.network.NavigateJukeboxPayload;
import com.kuronami.musicdiscmaker.network.ShuffleJukeboxPayload;
import com.kuronami.musicdiscmaker.network.ControlBoomboxPayload;
//? if >=26.1 {
import com.kuronami.musicdiscmaker.register.ModCreativeTab;
//?} elif >=1.21.2 {
/*import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModCreativeTab;
*/
//?} elif >=1.21 {
/*import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.ResolveUrlPayload;
import com.kuronami.musicdiscmaker.network.SeekJukeboxPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
*/
//?} else {
/*import com.kuronami.musicdiscmaker.network.ResolveUrlPayload;
import com.kuronami.musicdiscmaker.network.SeekJukeboxPayload;
*/
//?}
import com.kuronami.musicdiscmaker.register.ModRegistries;

import net.fabricmc.api.ModInitializer;
//? if >=26.1 {
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
//?} elif >=1.21.2 {
/*import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
*/
//?} else {
//?}
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
//? if >=26.1 {
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
//?} elif >=1.21.2 {
/*import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
*/
//?} elif >=1.21 {
/*import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import com.kuronami.musicdiscmaker.network.ModPayloadTypes;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
*/
//?} else {
/*import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
*/
//?}
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
//? if >=1.21.2 {
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
//?} else {
//?}

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

        //? if >=26.1 {
        // payload 型を登録する。
        // 26.2 fabric-networking: playC2S/playS2C は serverboundPlay/clientboundPlay に rename。
        PayloadTypeRegistry.serverboundPlay().register(ModPayloadTypes.ResolveUrl.TYPE, ModPayloadTypes.ResolveUrl.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModPayloadTypes.ConfigureJukebox.TYPE, ModPayloadTypes.ConfigureJukebox.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModPayloadTypes.SeekJukebox.TYPE, ModPayloadTypes.SeekJukebox.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModPayloadTypes.NavigateJukebox.TYPE, ModPayloadTypes.NavigateJukebox.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModPayloadTypes.ShuffleJukebox.TYPE, ModPayloadTypes.ShuffleJukebox.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ModPayloadTypes.ControlBoombox.TYPE, ModPayloadTypes.ControlBoombox.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloadTypes.Play.TYPE, ModPayloadTypes.Play.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloadTypes.PlayVanilla.TYPE, ModPayloadTypes.PlayVanilla.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloadTypes.Stop.TYPE, ModPayloadTypes.Stop.STREAM_CODEC);
        // 受け口を付ける前に型を登録する。登録していない型に client が受け口を付けると
        // Fabric は entrypoint で IllegalArgumentException を投げ、ゲームが起動しない。
        PayloadTypeRegistry.clientboundPlay().register(ModPayloadTypes.Version.TYPE, ModPayloadTypes.Version.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloadTypes.BoomboxPlay.TYPE, ModPayloadTypes.BoomboxPlay.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloadTypes.BoomboxStop.TYPE, ModPayloadTypes.BoomboxStop.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloadTypes.BoomboxState.TYPE, ModPayloadTypes.BoomboxState.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ModPayloadTypes.SpeakerSet.TYPE, ModPayloadTypes.SpeakerSet.STREAM_CODEC);
        //?} elif >=1.21.2 {
        /*        // payload 型を登録する。
        PayloadTypeRegistry.playC2S().register(ModPayloadTypes.ResolveUrl.TYPE, ModPayloadTypes.ResolveUrl.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ModPayloadTypes.ConfigureJukebox.TYPE, ModPayloadTypes.ConfigureJukebox.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ModPayloadTypes.SeekJukebox.TYPE, ModPayloadTypes.SeekJukebox.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ModPayloadTypes.NavigateJukebox.TYPE, ModPayloadTypes.NavigateJukebox.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ModPayloadTypes.ShuffleJukebox.TYPE, ModPayloadTypes.ShuffleJukebox.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ModPayloadTypes.ControlBoombox.TYPE, ModPayloadTypes.ControlBoombox.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ModPayloadTypes.Play.TYPE, ModPayloadTypes.Play.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ModPayloadTypes.PlayVanilla.TYPE, ModPayloadTypes.PlayVanilla.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ModPayloadTypes.Stop.TYPE, ModPayloadTypes.Stop.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ModPayloadTypes.Version.TYPE, ModPayloadTypes.Version.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ModPayloadTypes.BoomboxPlay.TYPE, ModPayloadTypes.BoomboxPlay.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ModPayloadTypes.BoomboxStop.TYPE, ModPayloadTypes.BoomboxStop.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ModPayloadTypes.BoomboxState.TYPE, ModPayloadTypes.BoomboxState.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ModPayloadTypes.SpeakerSet.TYPE, ModPayloadTypes.SpeakerSet.STREAM_CODEC);
        */
        //?} elif >=1.21 {
        /*        // payload 型を登録する。
        // SB Fabric port の backpack jukebox 用 (port 非依存。port が無ければ送信されないだけ)。
        // server 受信: URL コミット (main thread へ enqueue して BlockEntity に保存)。
        // server 受信: 強化版ジュークボックスの設定適用。
        // Additional Additions 互換: ロードされていればアルバムを強化版ジュークボックスで再生できるようにする。
        // AA の型に触れるのはガード通過後の呼び出しだけ (AlbumProvider がこの時点で初めて class-load される)。
        PayloadTypeRegistry.playC2S().register(ModPayloadTypes.ResolveUrl.TYPE, ModPayloadTypes.ResolveUrl.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ModPayloadTypes.ConfigureJukebox.TYPE, ModPayloadTypes.ConfigureJukebox.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ModPayloadTypes.SeekJukebox.TYPE, ModPayloadTypes.SeekJukebox.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ModPayloadTypes.NavigateJukebox.TYPE, ModPayloadTypes.NavigateJukebox.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ModPayloadTypes.ShuffleJukebox.TYPE, ModPayloadTypes.ShuffleJukebox.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(ModPayloadTypes.ControlBoombox.TYPE, ModPayloadTypes.ControlBoombox.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ModPayloadTypes.Play.TYPE, ModPayloadTypes.Play.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ModPayloadTypes.PlayVanilla.TYPE, ModPayloadTypes.PlayVanilla.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ModPayloadTypes.Stop.TYPE, ModPayloadTypes.Stop.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ModPayloadTypes.Version.TYPE, ModPayloadTypes.Version.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ModPayloadTypes.BoomboxPlay.TYPE, ModPayloadTypes.BoomboxPlay.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ModPayloadTypes.BoomboxStop.TYPE, ModPayloadTypes.BoomboxStop.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ModPayloadTypes.BoomboxState.TYPE, ModPayloadTypes.BoomboxState.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(ModPayloadTypes.SpeakerSet.TYPE, ModPayloadTypes.SpeakerSet.STREAM_CODEC);
        com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompat.registerPayload();
        */
        //?} else {
/*        ServerPlayNetworking.registerGlobalReceiver(com.kuronami.musicdiscmaker.network.LegacyPayloadIds.of(ResolveUrlPayload.PATH),
                (server, player, handler, buf, responseSender) -> {
                    final ResolveUrlPayload payload = ResolveUrlPayload.read(buf);
                    server.execute(() -> ModNetwork.handleResolveUrl(payload, player));
                });
        */
        //?}

        //? if >=1.21.2 {
        // server 受信: URL コミット (main thread へ enqueue して BlockEntity に保存)。
        ServerPlayNetworking.registerGlobalReceiver(ModPayloadTypes.ResolveUrl.TYPE,
                (msg, ctx) -> ctx.server().execute(() -> ModNetwork.handleResolveUrl(msg.inner(), ctx.player())));
        // server 受信: 強化版ジュークボックスの設定適用。
        ServerPlayNetworking.registerGlobalReceiver(ModPayloadTypes.ConfigureJukebox.TYPE,
                (msg, ctx) -> ctx.server().execute(() -> ModNetwork.handleConfigureJukebox(msg.inner(), ctx.player())));
        //?} elif >=1.21 {
/*        ServerPlayNetworking.registerGlobalReceiver(ModPayloadTypes.ResolveUrl.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> ModNetwork.handleResolveUrl(payload.inner(), ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(ModPayloadTypes.ConfigureJukebox.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> ModNetwork.handleConfigureJukebox(payload.inner(), ctx.player())));
        */
        //?} else {
        /*        // server 受信: URL コミット (main thread へ enqueue して BlockEntity に保存)。
        // server 受信: 強化版ジュークボックスの設定適用。
        ServerPlayNetworking.registerGlobalReceiver(com.kuronami.musicdiscmaker.network.LegacyPayloadIds.of(ConfigureJukeboxPayload.PATH),
                (server, player, handler, buf, responseSender) -> {
                    final ConfigureJukeboxPayload payload = ConfigureJukeboxPayload.read(buf);
                    server.execute(() -> ModNetwork.handleConfigureJukebox(payload, player));
                });
        */
        //?}
        // server 受信: 強化版ジュークボックスのシークバー頭出し。
        //? if >=26.1 {
        ServerPlayNetworking.registerGlobalReceiver(ModPayloadTypes.SeekJukebox.TYPE,
                (msg, ctx) -> ctx.server().execute(() -> ModNetwork.handleSeekJukebox(msg.inner(), ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(ModPayloadTypes.NavigateJukebox.TYPE,
                (msg, ctx) -> ctx.server().execute(() -> ModNetwork.handleNavigateJukebox(msg.inner(), ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(ModPayloadTypes.ShuffleJukebox.TYPE,
                (msg, ctx) -> ctx.server().execute(() -> ModNetwork.handleShuffleJukebox(msg.inner(), ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(ModPayloadTypes.ControlBoombox.TYPE,
                (msg, ctx) -> ctx.server().execute(() -> ModNetwork.handleControlBoombox(msg.inner(), ctx.player())));

        // creative タブ内容: common は tab の identity のみ作る (26.2 の Output が protected)。
        // 26.2 fabric-api は旧 ItemGroupEvents を廃し CreativeModeTabEvents に統合。表示順で TAB_ITEMS を流す。
        CreativeModeTabEvents.modifyOutputEvent(
                        ResourceKey.create(Registries.CREATIVE_MODE_TAB, ModCreativeTab.MAIN.getId()))
                .register(output -> ModCreativeTab.TAB_ITEMS.forEach(holder -> output.accept(new ItemStack(holder.get()))));

        // Additional Additions 互換: ロードされていればアルバムを強化版ジュークボックスで再生できるようにする。
        // AA の型に触れるのはガード通過後の呼び出しだけ (AlbumProvider がこの時点で初めて class-load される)。
        if (com.kuronami.musicdiscmaker.platform.Services.PLATFORM.isModLoaded("additionaladditions")) {
            com.kuronami.musicdiscmaker.compat.additionaladditions.AlbumProvider.install();
        }
        //?} elif >=1.21.2 {
        /*        // creative タブ内容: common は tab の identity のみ作る。fabric-item-group の
        // ItemGroupEvents.modifyEntriesEvent で表示順に TAB_ITEMS を流す。
        // Additional Additions 互換: ロードされていればアルバムを強化版ジュークボックスで再生できるようにする。
        // AA の型に触れるのはガード通過後の呼び出しだけ (AlbumProvider がこの時点で初めて class-load される)。
        ServerPlayNetworking.registerGlobalReceiver(ModPayloadTypes.SeekJukebox.TYPE,
                (msg, ctx) -> ctx.server().execute(() -> ModNetwork.handleSeekJukebox(msg.inner(), ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(ModPayloadTypes.NavigateJukebox.TYPE,
                (msg, ctx) -> ctx.server().execute(() -> ModNetwork.handleNavigateJukebox(msg.inner(), ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(ModPayloadTypes.ShuffleJukebox.TYPE,
                (msg, ctx) -> ctx.server().execute(() -> ModNetwork.handleShuffleJukebox(msg.inner(), ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(ModPayloadTypes.ControlBoombox.TYPE,
                (msg, ctx) -> ctx.server().execute(() -> ModNetwork.handleControlBoombox(msg.inner(), ctx.player())));

        ItemGroupEvents.modifyEntriesEvent(
                        ResourceKey.create(Registries.CREATIVE_MODE_TAB, ModCreativeTab.MAIN.getId()))
                .register(output -> ModCreativeTab.TAB_ITEMS.forEach(holder -> output.accept(new ItemStack(holder.get()))));

        if (Services.PLATFORM.isModLoaded("additionaladditions")) {
            com.kuronami.musicdiscmaker.compat.additionaladditions.AlbumProvider.install();
        }
        */
        //?} elif >=1.21 {
/*        ServerPlayNetworking.registerGlobalReceiver(ModPayloadTypes.SeekJukebox.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> ModNetwork.handleSeekJukebox(payload.inner(), ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(ModPayloadTypes.NavigateJukebox.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> ModNetwork.handleNavigateJukebox(payload.inner(), ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(ModPayloadTypes.ShuffleJukebox.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> ModNetwork.handleShuffleJukebox(payload.inner(), ctx.player())));
        ServerPlayNetworking.registerGlobalReceiver(ModPayloadTypes.ControlBoombox.TYPE,
                (payload, ctx) -> ctx.server().execute(() -> ModNetwork.handleControlBoombox(payload.inner(), ctx.player())));

        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("additionaladditions")) {
            com.kuronami.musicdiscmaker.compat.additionaladditions.AlbumProvider.install();
        }
        */
        //?} else {
/*        ServerPlayNetworking.registerGlobalReceiver(com.kuronami.musicdiscmaker.network.LegacyPayloadIds.of(SeekJukeboxPayload.PATH),
                (server, player, handler, buf, responseSender) -> {
                    final SeekJukeboxPayload payload = SeekJukeboxPayload.read(buf);
                    server.execute(() -> ModNetwork.handleSeekJukebox(payload, player));
                });
        ServerPlayNetworking.registerGlobalReceiver(com.kuronami.musicdiscmaker.network.LegacyPayloadIds.of(NavigateJukeboxPayload.PATH),
                (server, player, handler, buf, responseSender) -> {
                    final NavigateJukeboxPayload payload = NavigateJukeboxPayload.read(buf);
                    server.execute(() -> ModNetwork.handleNavigateJukebox(payload, player));
                });
        ServerPlayNetworking.registerGlobalReceiver(com.kuronami.musicdiscmaker.network.LegacyPayloadIds.of(ShuffleJukeboxPayload.PATH),
                (server, player, handler, buf, responseSender) -> {
                    final ShuffleJukeboxPayload payload = ShuffleJukeboxPayload.read(buf);
                    server.execute(() -> ModNetwork.handleShuffleJukebox(payload, player));
                });
        ServerPlayNetworking.registerGlobalReceiver(com.kuronami.musicdiscmaker.network.LegacyPayloadIds.of(ControlBoomboxPayload.PATH),
                (server, player, handler, buf, responseSender) -> {
                    final ControlBoomboxPayload payload = ControlBoomboxPayload.read(buf);
                    server.execute(() -> ModNetwork.handleControlBoombox(payload, player));
                });
        */
        //?}

        // jukebox 出し入れ / 破壊イベント。
        FabricJukeboxEvents.register();

        // B5/C12: fabric 版ゲート (参加直後に wire 版を送る・canSend で盲送り回避)。詳細は FabricProtocolHandshake。
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                com.kuronami.musicdiscmaker.network.FabricProtocolHandshake.sendTo(handler.getPlayer()));

        // server 停止で再生中状態を破棄 (シングルプレイのワールド退出含む)。
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            ActiveDiscRegistry.clear();
            com.kuronami.musicdiscmaker.event.SpeakerPlayback.clear(server);
            com.kuronami.musicdiscmaker.event.SpeakerNetwork.clear(server);
        });

        // 携帯ブームボックスの走査。END (tick の後半) で呼ぶ — 設置してある機体の打刻は
        // BlockEntity の ticker が行うので、先に呼ぶとその tick の打刻を見ずに掃除してしまう。
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                com.kuronami.musicdiscmaker.event.BoomboxPlayback::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(
                server -> com.kuronami.musicdiscmaker.event.BoomboxPlayback.clear());

        //? if >=26.1 {
        // 次元がアンロードされたらその次元のエントリを捨てる。次元が落ちると chunk はもう誰にも
        // watch されず、chunk 再入を待つ掃除には二度と届かない (server 停止まで残り続ける)。
        // 26.2 fabric-api: ServerWorldEvents は ServerLevelEvents に rename。
        ServerLevelEvents.UNLOAD.register((server, level) -> ActiveDiscRegistry.clear(level.dimension()));
        //?} else {
        /*        // 次元がアンロードされたらその次元のエントリを捨てる。次元が落ちると chunk はもう誰にも
        // watch されず、chunk 再入を待つ掃除には二度と届かない (server 停止まで残り続ける)。
        ServerWorldEvents.UNLOAD.register((server, world) -> ActiveDiscRegistry.clear(world.dimension()));
        */
        //?}

        MusicDiscMaker.LOGGER.info("Music Disc Maker (Fabric) initialized");
    }
}



