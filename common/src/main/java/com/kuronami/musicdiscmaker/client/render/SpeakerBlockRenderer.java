package com.kuronami.musicdiscmaker.client.render;

import com.kuronami.musicdiscmaker.block.SpeakerBlock;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
//? if >=1.21.2 {
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.phys.Vec3;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
*///?}
//? if >=26.1 {
import net.minecraft.client.renderer.state.level.CameraRenderState;
//?} elif >=1.21.2 {
/*import net.minecraft.client.renderer.state.CameraRenderState;
*///?}

/** Renders the horn for the two diagonal states; centered horns stay in the static block model. */
//? if >=1.21.2 {
public final class SpeakerBlockRenderer
        implements BlockEntityRenderer<SpeakerBlockEntity, SpeakerBlockRenderer.State> {

    public static final class State extends BlockEntityRenderState {
        public AttachFace face = AttachFace.WALL;
        public Direction facing = Direction.NORTH;
        public SpeakerBlock.HornTurn turn = SpeakerBlock.HornTurn.CENTER;
    }

    public SpeakerBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(SpeakerBlockEntity blockEntity, State state, float partialTicks,
            Vec3 cameraPosition, ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        final BlockState blockState = blockEntity.getBlockState();
        state.face = blockState.getValue(SpeakerBlock.FACE);
        state.facing = blockState.getValue(SpeakerBlock.FACING);
        state.turn = blockState.getValue(SpeakerBlock.HORN_TURN);
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.turn == SpeakerBlock.HornTurn.CENTER) {
            return;
        }
        final int light = state.lightCoords;
        poseStack.pushPose();
        SpeakerHorn.poseBlock(poseStack, state.face, state.facing, state.turn);
        for (SpeakerHornGeometry.Element element : SpeakerHorn.elements(state.face)) {
            poseStack.pushPose();
            SpeakerHorn.poseElement(poseStack, element);
            collector.submitCustomGeometry(poseStack, SpeakerHorn.renderType(),
                    (pose, buffer) -> SpeakerHorn.emitElement(
                            element, pose, buffer, light, OverlayTexture.NO_OVERLAY));
            poseStack.popPose();
        }
        poseStack.popPose();
    }
}
//?} else {
/*public final class SpeakerBlockRenderer implements BlockEntityRenderer<SpeakerBlockEntity> {

    public SpeakerBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(SpeakerBlockEntity blockEntity, float partialTicks, PoseStack poseStack,
            MultiBufferSource buffers, int light, int overlay) {
        final BlockState state = blockEntity.getBlockState();
        final SpeakerBlock.HornTurn turn = state.getValue(SpeakerBlock.HORN_TURN);
        if (turn == SpeakerBlock.HornTurn.CENTER) {
            return;
        }
        final AttachFace face = state.getValue(SpeakerBlock.FACE);
        poseStack.pushPose();
        SpeakerHorn.poseBlock(poseStack, face, state.getValue(SpeakerBlock.FACING), turn);
        for (SpeakerHornGeometry.Element element : SpeakerHorn.elements(face)) {
            poseStack.pushPose();
            SpeakerHorn.poseElement(poseStack, element);
            SpeakerHorn.emitElement(element, poseStack.last(), buffers.getBuffer(SpeakerHorn.renderType()),
                    light, OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
        }
        poseStack.popPose();
    }
}
*///?}
