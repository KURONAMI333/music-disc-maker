package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.ClientPlaybackManager;
import com.kuronami.musicdiscmaker.component.TrackKey;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.network.ModNetwork;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;

/**
 * Fabric client setup。dedicated server ではロードされないので client 専用コードを安全に参照できる。
 * 音声ストリーミングは {@code MixinDiscSoundInstance} が担うので、ここでは getStream 系を扱わない。
 */
public class MusicDiscMakerFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        MenuScreens.register(ModMenus.MUSIC_DISC_MAKER.get(), MusicDiscMakerScreen::new);

        // custom disc の texture variant を曲名+アーティストから決定的に選ぶ。
        // 返り値 (index+0.5)/VARIANTS が model override の threshold (i/VARIANTS) に対応。
        ItemProperties.register(
                ModItems.CUSTOM_MUSIC_DISC.get(),
                new ResourceLocation(MusicDiscMaker.MODID, "variant"),
                (stack, level, entity, seed) ->
                        (TrackKey.variantIndex(CustomMusicDiscItem.getTrack(stack)) + 0.5F) / TrackKey.VARIANTS);

        // client 受信: 再生制御 (netty thread で buf を読み、main thread へ enqueue)。
        ClientPlayNetworking.registerGlobalReceiver(PlayDiscPayload.ID,
                (client, handler, buf, responseSender) -> {
                    final PlayDiscPayload payload = PlayDiscPayload.read(buf);
                    client.execute(() -> ModNetwork.handlePlay(payload));
                });
        ClientPlayNetworking.registerGlobalReceiver(StopDiscPayload.ID,
                (client, handler, buf, responseSender) -> {
                    final StopDiscPayload payload = StopDiscPayload.read(buf);
                    client.execute(() -> ModNetwork.handleStop(payload));
                });

        // client 受信: Sophisticated Backpacks (非公式 Fabric port) の backpack 内 jukebox 再生。
        // server 側は JukeboxUpgradeWrapperMixin が送信する (port 非導入なら never-load)。
        ClientPlayNetworking.registerGlobalReceiver(
                com.kuronami.musicdiscmaker.compat.sophisticatedcore.BackpackPlayDiscPayload.ID,
                (client, handler, buf, responseSender) -> {
                    final var payload =
                            com.kuronami.musicdiscmaker.compat.sophisticatedcore.BackpackPlayDiscPayload.read(buf);
                    client.execute(() ->
                            com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompatClient.play(payload));
                });

        // サーバ離脱時に全再生を止める。
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientPlaybackManager.get().stopAll());
    }
}
