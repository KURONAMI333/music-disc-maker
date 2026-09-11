package com.kuronami.musicdiscmaker.client;

//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.ClientPlaybackManager;
import com.kuronami.musicdiscmaker.client.render.SpeakerLinkOutlineRenderer;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
//? if >=26.1 {
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
//?} else {
/*import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
*///?}

/** client game-bus events。サーバ離脱時に全再生を止める。 */
@EventBusSubscriber(modid = MusicDiscMaker.MODID, value = Dist.CLIENT)
public final class ClientGameEvents {

    private ClientGameEvents() {
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientPlaybackManager.get().stopAll();
    }

    @SubscribeEvent
    //? if >=26.1 {
    public static void onSubmitSpeakerLink(SubmitCustomGeometryEvent event) {
        SpeakerLinkOutlineRenderer.submitModern(event.getPoseStack(), event.getSubmitNodeCollector(),
                event.getLevelRenderState().cameraRenderState.pos);
    }
    //?} elif >=1.21.11 {
    /*public static void onSubmitSpeakerLink(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        SpeakerLinkOutlineRenderer.renderIntermediateWithSharedBuffer(event.getPoseStack(),
                event.getLevelRenderState().cameraRenderState.pos);
    }
    */
    //?} else {
    /*public static void onSubmitSpeakerLink(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        SpeakerLinkOutlineRenderer.renderLegacy(event.getPoseStack());
    }
    *///?}

}

//?} else {
//?}
