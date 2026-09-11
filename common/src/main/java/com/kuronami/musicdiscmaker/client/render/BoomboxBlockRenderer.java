package com.kuronami.musicdiscmaker.client.render;

import com.kuronami.musicdiscmaker.block.BoomboxBlockEntity;
import com.kuronami.musicdiscmaker.client.audio.BoomboxClientPlayback;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
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

/**
 * 設置されたブームボックスの取っ手を、再生中なら倒して描く BlockEntityRenderer。
 *
 * <p>本体はブロックの模型 ({@code models/block/boombox.json}) が描き、<b>ここが描くのは取っ手だけ</b>。
 * 判定源は {@code BoomboxClientPlayback#isPlayingAt} で、server の再生状態ではなく
 * <b>client で実際に音が出ているか</b>を見る。
 *
 * <h2>帯で描き方の骨格が違う</h2>
 * <ul>
 *   <li>1.21.11 以上 — {@code createRenderState} / {@code extractRenderState} / {@code submit} の
 *       3 段。抽出と描画が分かれたので、BlockEntity に触れるのは抽出の段だけ</li>
 *   <li>1.21.1 以下 — {@code render} 1 本。{@code MultiBufferSource} から直接 buffer を取る</li>
 * </ul>
 * 取っ手の形と姿勢の計算は {@link BoomboxHandle} が 1 つ持ち、この 2 系統はその呼び方だけが違う。
 */
//? if >=1.21.2 {
public class BoomboxBlockRenderer
        implements BlockEntityRenderer<BoomboxBlockEntity, BoomboxBlockRenderer.State> {

    /** 抽出した描画状態。段を跨いで運ぶのはこの 2 つだけ。 */
    public static class State extends BlockEntityRenderState {
        /** blockstate の {@code y} 回転 (度)。 */
        public float yaw;
        /** 0 = 取っ手が立つ / 1 = 倒れる。 */
        public float fall;
    }

    public BoomboxBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(BoomboxBlockEntity blockEntity, State state, float partialTicks,
            Vec3 cameraPosition, ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        state.yaw = BoomboxHandle.yawOf(blockEntity.getBlockState());
        state.fall = blockEntity.advanceHandleFall(
                BoomboxClientPlayback.isPlayingAt(blockEntity.getBlockPos()));
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera) {
        final int light = state.lightCoords;
        poseStack.pushPose();
        BoomboxHandle.pose(poseStack, state.yaw, state.fall);
        submitNodeCollector.submitCustomGeometry(poseStack, BoomboxHandle.renderType(),
                (pose, buffer) -> BoomboxHandle.emit(pose, buffer, light, OverlayTexture.NO_OVERLAY));
        poseStack.popPose();
    }
}
//?} else {
/*public class BoomboxBlockRenderer implements BlockEntityRenderer<BoomboxBlockEntity> {

    public BoomboxBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(BoomboxBlockEntity blockEntity, float partialTicks, PoseStack poseStack,
            MultiBufferSource buffers, int light, int overlay) {
        final float fall = blockEntity.advanceHandleFall(
                BoomboxClientPlayback.isPlayingAt(blockEntity.getBlockPos()));
        poseStack.pushPose();
        BoomboxHandle.pose(poseStack, BoomboxHandle.yawOf(blockEntity.getBlockState()), fall);
        BoomboxHandle.emit(poseStack.last(), buffers.getBuffer(BoomboxHandle.renderType()),
                light, OverlayTexture.NO_OVERLAY);
        poseStack.popPose();
    }
}
*///?}
