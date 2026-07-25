package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.ClientPlaybackManager;
import com.kuronami.musicdiscmaker.client.jacket.JacketClientTooltip;
import com.kuronami.musicdiscmaker.client.jacket.JacketTooltip;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.TrackKey;
import com.kuronami.musicdiscmaker.network.BoomboxPlayPayload;
import com.kuronami.musicdiscmaker.network.BoomboxStopPayload;
import com.kuronami.musicdiscmaker.network.ModNetwork;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.TooltipComponentCallback;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;

/**
 * Fabric client setup。dedicated server ではロードされないので client 専用コードを安全に参照できる。
 * 音声ストリーミングは common の Mixin が担うので、ここでは getStream / sound-engine 系を一切扱わない。
 */
public class MusicDiscMakerFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        MenuScreens.register(ModMenus.MUSIC_DISC_MAKER.get(), MusicDiscMakerScreen::new);
        MenuScreens.register(ModMenus.GOLDEN_JUKEBOX.get(), GoldenJukeboxScreen::new);
        MenuScreens.register(ModMenus.SPEAKER.get(), SpeakerScreen::new);

        // ツールチップのジャケット画像: JacketTooltip → JacketClientTooltip に変換する。
        TooltipComponentCallback.EVENT.register(
                data -> data instanceof JacketTooltip jt ? new JacketClientTooltip(jt) : null);

        // custom disc の texture variant を曲名+アーティストから決定的に選ぶ。
        // 返り値 (index+0.5)/VARIANTS が model override の threshold (i/VARIANTS) に対応。
        ItemProperties.register(
                ModItems.CUSTOM_MUSIC_DISC.get(),
                ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "variant"),
                (stack, level, entity, seed) -> {
                    final CustomTrackData track = stack.get(ModDataComponents.CUSTOM_TRACK.get());
                    return (TrackKey.variantIndex(track) + 0.5F) / TrackKey.VARIANTS;
                });

        // client 受信: 再生制御 (main thread へ enqueue)。
        ClientPlayNetworking.registerGlobalReceiver(PlayDiscPayload.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handlePlay(payload)));
        ClientPlayNetworking.registerGlobalReceiver(StopDiscPayload.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleStop(payload)));
        // client 受信: 有効スピーカー集合の更新。
        ClientPlayNetworking.registerGlobalReceiver(SpeakerSetPayload.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleSpeakerSet(payload)));
        // client 受信: 手持ちブームボックスの再生 (keep-alive 兼 late-join) と停止。
        ClientPlayNetworking.registerGlobalReceiver(BoomboxPlayPayload.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleBoomboxPlay(payload)));
        ClientPlayNetworking.registerGlobalReceiver(BoomboxStopPayload.TYPE,
                (payload, ctx) -> ctx.client().execute(() -> ModNetwork.handleBoomboxStop(payload)));
        // SB Fabric port の backpack jukebox 再生受信 (port 非依存)。
        com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompat.registerClientReceiver();

        // サーバ離脱時に全再生を止める。
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientPlaybackManager.get().stopAll());
    }
}
