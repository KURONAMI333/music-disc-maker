package com.kuronami.musicdiscmaker.client.render;

import org.jetbrains.annotations.Nullable;
import java.util.List;

import com.kuronami.musicdiscmaker.block.DiscPedestalBlockEntity;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
//? if >=1.21.2 {
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.phys.Vec3;
//?} else {
/*import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
*///?}
//? if >=26.1 {
import net.minecraft.client.renderer.state.level.CameraRenderState;
//?} elif >=1.21.2 {
/*import net.minecraft.client.renderer.state.CameraRenderState;
*///?}

/**
 * ディスクを飾る台座の BlockEntity レンダラ。<b>盤 (アイテム) と曲名 (文字)</b> を描く。
 *
 * <p>台そのものはブロックの模型が描く。盤を模型に焼かないのは、盤面が
 * {@code custom_music_disc_0..11} の 12 通りから曲名+作者で決まり、さらに染色の色が乗るため。
 * <b>アイテムとして描けばその両方がそのまま出る</b>。
 *
 * <h2>帯で骨格が 2 系統に割れる</h2>
 * <ul>
 *   <li>1.21.11 以上 — {@code createRenderState} / {@code extractRenderState} / {@code submit} の 3 段。
 *       BlockEntity にも camera にも触れるのは抽出の段だけなので、<b>曲名を出すかどうかの距離判定も
 *       抽出の段で済ませて状態に載せる</b>。アイテムは {@code ItemModelResolver} で状態へ焼く</li>
 *   <li>1.21.1 以下 — {@code render} 1 本。アイテムは {@code ItemRenderer#renderStatic}、
 *       文字は {@code Font#drawInBatch}。camera は {@code Minecraft} から自分で引く</li>
 * </ul>
 * 置き場所と姿勢は {@link DiscPedestalLayout} が 1 つ持ち、この 2 系統はその呼び方だけが違う。
 *
 * <p><b>可視距離を縮めていない</b>ことに注意。{@code getViewDistance} を縮めるとレンダラごと
 * 消えて盤まで出なくなる。曲名だけを近くで出したいので、判定は毎フレームの距離で行う。
 */
//? if >=1.21.2 {
public class DiscPedestalRenderer
        implements BlockEntityRenderer<DiscPedestalBlockEntity, DiscPedestalRenderer.State> {

    /** 抽出した描画状態。段を跨いで運ぶのはこの 3 つだけ。 */
    public static class State extends BlockEntityRenderState {
        /** blockstate の {@code y} 回転 (度)。 */
        public float yaw;
        /** 飾ってある盤。空なら {@code isEmpty()} が true。 */
        public final ItemStackRenderState disc = new ItemStackRenderState();
        /** 台の下に出す曲名とアーティスト名。出さない時は {@code null} (遠い / 曲メタ無し)。 */
        @Nullable
        public List<FormattedCharSequence> title;
    }

    private final ItemModelResolver itemModelResolver;
    private final Font font;

    public DiscPedestalRenderer(BlockEntityRendererProvider.Context context) {
        this.itemModelResolver = context.itemModelResolver();
        this.font = context.font();
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(DiscPedestalBlockEntity blockEntity, State state, float partialTicks,
            Vec3 cameraPosition, ModelFeatureRenderer.CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(blockEntity, state, partialTicks, cameraPosition, breakProgress);
        state.yaw = DiscPedestalLayout.yawOf(blockEntity.getBlockState());
        final ItemStack shown = blockEntity.getStored();
        // updateForTopItem は先頭で clear するので、状態を使い回してよい (空スタックなら空のまま)。
        itemModelResolver.updateForTopItem(state.disc, shown, ItemDisplayContext.FIXED, blockEntity.getLevel(),
                null, (int) blockEntity.getBlockPos().asLong());
        final String title = blockEntity.storedTitle();
        final String author = blockEntity.storedAuthor();
        state.title = !title.isEmpty()
                && DiscPedestalLayout.withinLabelRange(blockEntity.getBlockPos(), cameraPosition)
                        ? DiscPedestalLayout.label(font, title, author)
                        : null;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
            CameraRenderState camera) {
        if (!state.disc.isEmpty()) {
            poseStack.pushPose();
            DiscPedestalLayout.poseDisc(poseStack, state.yaw);
            state.disc.submit(poseStack, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            poseStack.popPose();
        }
        final List<FormattedCharSequence> title = state.title;
        if (title != null) {
            poseStack.pushPose();
            DiscPedestalLayout.poseLabel(poseStack, state.yaw);
            for (int i = 0; i < title.size(); i++) {
                final FormattedCharSequence line = title.get(i);
                submitNodeCollector.submitText(poseStack, -font.width(line) / 2.0F,
                        i * DiscPedestalLayout.LABEL_LINE_HEIGHT, line, false,
                        Font.DisplayMode.NORMAL, state.lightCoords, DiscPedestalLayout.LABEL_COLOR,
                        DiscPedestalLayout.LABEL_BACKGROUND, 0);
            }
            poseStack.popPose();
        }
    }
}
//?} else {
/*public class DiscPedestalRenderer implements BlockEntityRenderer<DiscPedestalBlockEntity> {

    private final ItemRenderer itemRenderer;
    private final Font font;

    public DiscPedestalRenderer(BlockEntityRendererProvider.Context context) {
        this.itemRenderer = context.getItemRenderer();
        this.font = context.getFont();
    }

    @Override
    public void render(DiscPedestalBlockEntity blockEntity, float partialTicks, PoseStack poseStack,
            MultiBufferSource buffers, int light, int overlay) {
        final float yaw = DiscPedestalLayout.yawOf(blockEntity.getBlockState());
        final ItemStack shown = blockEntity.getStored();
        if (!shown.isEmpty()) {
            poseStack.pushPose();
            DiscPedestalLayout.poseDisc(poseStack, yaw);
            itemRenderer.renderStatic(shown, ItemDisplayContext.FIXED, light, overlay, poseStack, buffers,
                    blockEntity.getLevel(), (int) blockEntity.getBlockPos().asLong());
            poseStack.popPose();
        }
        final String title = blockEntity.storedTitle();
        if (title.isEmpty()
                || !DiscPedestalLayout.withinLabelRange(blockEntity.getBlockPos())) {
            return;
        }
        final List<FormattedCharSequence> label = DiscPedestalLayout.label(font, title, blockEntity.storedAuthor());
        poseStack.pushPose();
        DiscPedestalLayout.poseLabel(poseStack, yaw);
        for (int i = 0; i < label.size(); i++) {
            final FormattedCharSequence line = label.get(i);
            font.drawInBatch(line, -font.width(line) / 2.0F, i * DiscPedestalLayout.LABEL_LINE_HEIGHT,
                    DiscPedestalLayout.LABEL_COLOR, false, poseStack.last().pose(), buffers,
                    Font.DisplayMode.NORMAL, DiscPedestalLayout.LABEL_BACKGROUND, light);
        }
        poseStack.popPose();
    }
}
*///?}
