package com.kuronami.musicdiscmaker;

//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.client.BoomboxPlayingItemModelProperty;
import com.kuronami.musicdiscmaker.client.BoomboxScreen;
import com.kuronami.musicdiscmaker.client.AlbumScreen;
import com.kuronami.musicdiscmaker.client.SpeakerScreen;
import com.kuronami.musicdiscmaker.client.DiscDyeingTableScreen;
import com.kuronami.musicdiscmaker.client.GoldenJukeboxScreen;
import com.kuronami.musicdiscmaker.client.MusicDiscMakerScreen;
import com.kuronami.musicdiscmaker.client.DiscDyeTintSource;
import com.kuronami.musicdiscmaker.client.VariantItemModelProperty;
import com.kuronami.musicdiscmaker.client.audio.ClientPlaybackManager;
import com.kuronami.musicdiscmaker.client.jacket.JacketClientTooltip;
import com.kuronami.musicdiscmaker.client.jacket.JacketTooltip;
import com.kuronami.musicdiscmaker.client.render.BoomboxBlockRenderer;
import com.kuronami.musicdiscmaker.client.render.DiscPedestalRenderer;
import com.kuronami.musicdiscmaker.client.render.SpeakerBlockRenderer;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = MusicDiscMaker.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = MusicDiscMaker.MODID, value = Dist.CLIENT)
public class MusicDiscMakerClient {
    public MusicDiscMakerClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.MUSIC_DISC_MAKER.get(), MusicDiscMakerScreen::new);
        event.register(ModMenus.GOLDEN_JUKEBOX.get(), GoldenJukeboxScreen::new);
        event.register(ModMenus.DISC_DYEING_TABLE.get(), DiscDyeingTableScreen::new);
        event.register(ModMenus.BOOMBOX.get(), BoomboxScreen::new);
        event.register(ModMenus.ALBUM.get(), AlbumScreen::new);
        event.register(ModMenus.SPEAKER.get(), SpeakerScreen::new);
    }

    /** ディスクのツールチップにジャケット画像を出すための factory (JacketTooltip→JacketClientTooltip)。 */
    @SubscribeEvent
    static void onRegisterTooltipFactories(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(JacketTooltip.class, JacketClientTooltip::new);
    }

    /**
     * custom disc の texture variant (12 色) を曲名+アーティストから決定的に選ぶ range_dispatch プロパティを登録する。
     * 26.1: 旧 {@code ItemProperties} は廃止され、items モデル JSON の range_dispatch +
     * カスタムプロパティ ({@link VariantItemModelProperty}) で表現する。
     */
    @SubscribeEvent
    static void onRegisterRangeProperties(RegisterRangeSelectItemModelPropertyEvent event) {
        event.register(Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "variant"),
                VariantItemModelProperty.MAP_CODEC);
        // ブームボックスの ON (肩に担ぐ・取っ手が倒れる) / OFF (提げる・取っ手が立つ) の出し分け。
        event.register(Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "playing"),
                BoomboxPlayingItemModelProperty.MAP_CODEC);
    }

    /**
     * 染色ディスクの tint 源。{@code items} モデル JSON の {@code tints} がこの id を引く。
     * <b>登録し忘れると色が出ないだけで、例外もログも出ない</b> (未登録の tintindex は
     * 白として扱われ、未染色と同じ見た目になる)。
     */
    @SubscribeEvent
    static void onRegisterItemTintSources(RegisterColorHandlersEvent.ItemTintSources event) {
        event.register(Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "disc_dye"),
                DiscDyeTintSource.MAP_CODEC);
        event.register(Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "album_dye"),
                com.kuronami.musicdiscmaker.client.AlbumDyeTintSource.MAP_CODEC);
    }

    /**
     * 設置されたブームボックスの取っ手を描く BER。<b>ここを登録し忘れると取っ手が消える</b>
     * (ブロックの模型は取っ手を持たない) が、例外もログも出ない。
     */
    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.BOOMBOX.get(), BoomboxBlockRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.DISC_PEDESTAL.get(), DiscPedestalRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.SPEAKER.get(), SpeakerBlockRenderer::new);
    }

    /**
     * B5 (混在アルバムの先読み): MDM 再生が無い区間 (vanilla / 他 MOD ディスクの再生中) でも
     * 先読み判定を回すための client tick 源。先読みを持たない帯では口が no-op になる。
     */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        ClientPlaybackManager.mixedAlbumTick();
    }
}

//?} else {
//?}
