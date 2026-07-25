package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.ClientPlaybackManager;
import com.kuronami.musicdiscmaker.client.jacket.JacketClientTooltip;
import com.kuronami.musicdiscmaker.client.jacket.JacketTooltip;
import com.kuronami.musicdiscmaker.network.ModNetwork;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.TooltipComponentCallback;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperties;
import net.minecraft.resources.Identifier;

/**
 * Fabric client setup。dedicated server ではロードされないので client 専用コードを安全に参照できる。
 * 音声ストリーミングは client mixin ({@code MixinDiscSoundInstance}) が担うので、ここでは
 * getStream / sound-engine 系を一切扱わない。
 */
public class MusicDiscMakerFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // menu screen 登録。vanilla MenuScreens.register は private だが fabric-api の
        // transitive access widener で mod から呼べる。
        MenuScreens.register(ModMenus.MUSIC_DISC_MAKER.get(), MusicDiscMakerScreen::new);
        MenuScreens.register(ModMenus.GOLDEN_JUKEBOX.get(), GoldenJukeboxScreen::new);

        // ツールチップのジャケット画像: JacketTooltip → JacketClientTooltip に変換する。
        // fabric-rendering の TooltipComponentCallback#getComponent で TooltipComponent を
        // ClientTooltipComponent に変換する。
        TooltipComponentCallback.EVENT.register(
                data -> data instanceof JacketTooltip jt ? new JacketClientTooltip(jt) : null);

        // custom disc の texture variant (12 色) を曲名+アーティストから決定的に選ぶ range_dispatch プロパティ。
        // 旧 ItemProperties は無い。items モデル JSON の range_dispatch + カスタムプロパティで表現する。
        // NeoForge は RegisterRangeSelectItemModelPropertyEvent、Fabric は vanilla の LateBoundIdMapper
        // (RangeSelectItemModelProperties.ID_MAPPER・fabric-api の transitive access widener で可視) へ直接 put する。
        // client init は資源リロード (item model パース) より前に走るので put のタイミングは安全。
        RangeSelectItemModelProperties.ID_MAPPER.put(
                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "variant"),
                VariantItemModelProperty.MAP_CODEC);

        // client 受信: 再生制御 (main thread へ enqueue)。
        ClientPlayNetworking.registerGlobalReceiver(PlayDiscPayload.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handlePlay(payload)));
        ClientPlayNetworking.registerGlobalReceiver(StopDiscPayload.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleStop(payload)));

        // サーバ離脱時に全再生を止める。
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientPlaybackManager.get().stopAll());
    }
}
