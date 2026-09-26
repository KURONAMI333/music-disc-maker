package com.kuronami.musicdiscmaker.client.render;

import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Function;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.RandomSource;
//? if >=26.1 {
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?} elif >=1.21.2 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
*///?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
*///?}

/**
 * Loader-neutral access to the separately baked device-part models.
 *
 * <p>The provider is asked on every render. It must query the current model manager rather than
 * retaining a baked model, so F3+T can replace models without leaving a stale reference in a BER.
 */
public final class DevicePartModels {

    public enum Part {
        SPEAKER_HORN_FLOOR("block/speaker_horn_floor"),
        SPEAKER_HORN_WALL("block/speaker_horn_wall"),
        BOOMBOX_HANDLE("block/boombox_handle");

        private final String path;

        Part(String path) {
            this.path = path;
        }

        public String path() {
            return path;
        }
    }

    private static volatile Function<Part, Object> provider = part -> null;
    private static volatile boolean warnedUnavailable;

    private DevicePartModels() {
    }

    public static void installProvider(Function<Part, Object> provider) {
        DevicePartModels.provider = Objects.requireNonNull(provider, "provider");
        warnedUnavailable = false;
    }

    //? if >=26.1 {
    public static void submit(Part part, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        final Object loaded = provider.apply(part);
        if (!(loaded instanceof BlockStateModel model)) {
            warnUnavailable(part);
            return;
        }
        final var parts = new ArrayList<BlockStateModelPart>();
        model.collectParts(RandomSource.create(42L), parts);
        collector.submitBlockModel(poseStack, RenderTypes.solidMovingBlock(), parts,
                new int[0], light, OverlayTexture.NO_OVERLAY, 0);
    }
    //?} elif >=1.21.2 {
    /*public static void submit(Part part, PoseStack poseStack, SubmitNodeCollector collector, int light) {
        final Object loaded = provider.apply(part);
        if (!(loaded instanceof BlockStateModel model)) {
            warnUnavailable(part);
            return;
        }
        collector.submitBlockModel(poseStack, RenderTypes.solidMovingBlock(), model,
                1.0F, 1.0F, 1.0F, light, OverlayTexture.NO_OVERLAY, 0);
    }
    *///?} else {
    /*public static void render(Part part, PoseStack poseStack, MultiBufferSource buffers, int light) {
        final Object loaded = provider.apply(part);
        if (!(loaded instanceof BakedModel model)) {
            warnUnavailable(part);
            return;
        }
        Minecraft.getInstance().getBlockRenderer().getModelRenderer().renderModel(
                poseStack.last(), buffers.getBuffer(RenderType.solid()), null, model,
                1.0F, 1.0F, 1.0F, light, OverlayTexture.NO_OVERLAY);
    }
    *///?}

    private static void warnUnavailable(Part part) {
        if (!warnedUnavailable) {
            warnedUnavailable = true;
            MusicDiscMaker.LOGGER.warn("Device part model {} is unavailable; skipping dynamic part rendering",
                    part.path());
        }
    }
}
