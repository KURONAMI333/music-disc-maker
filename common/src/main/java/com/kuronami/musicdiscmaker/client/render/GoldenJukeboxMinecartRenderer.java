package com.kuronami.musicdiscmaker.client.render;

import com.kuronami.musicdiscmaker.entity.GoldenJukeboxMinecartEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.model.geom.ModelLayers;
//? if >=1.21.4 {
import net.minecraft.client.renderer.entity.AbstractMinecartRenderer;
import net.minecraft.client.renderer.entity.state.MinecartRenderState;
//?} else {
/*import net.minecraft.client.renderer.entity.MinecartRenderer;
*///?}

/**
 * 金のジュークボックス付きトロッコの renderer。
 * 中身はバニラのトロッコ描画 (display block = 金ジューク block state) で、
 * loader 非依存に固有型へ結ぶためだけの薄い子クラス。
 *
 * <p>Amendments が出ている版 (1.20.1・1.21.1・26.1.2) では、Amendments 導入時に、載せた金の
 * ジュークボックスの上にも設置したものと同じ盤を回す ({@code AmendmentsCompat})。pose は display block の
 * 0..1 空間 (バニラのトロッコ描画が縮小・位置合わせ済み)。
 */
//? if >=26.1 && <26.2 {
/*public class GoldenJukeboxMinecartRenderer
        extends AbstractMinecartRenderer<GoldenJukeboxMinecartEntity, GoldenJukeboxMinecartRenderer.State> {

    /^* 上に乗せる盤を足したトロッコの描画状態。 ^/
    public static class State extends MinecartRenderState {
        public net.minecraft.world.item.ItemStack disc = net.minecraft.world.item.ItemStack.EMPTY;
        public float discAngle;
    }

    public GoldenJukeboxMinecartRenderer(EntityRendererProvider.Context context) {
        super(context, ModelLayers.CHEST_MINECART);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(GoldenJukeboxMinecartEntity cart, State state, float partialTicks) {
        super.extractRenderState(cart, state, partialTicks);
        state.disc = com.kuronami.musicdiscmaker.compat.amendments.AmendmentsCompat.isActive()
                ? cart.shownDisc() : net.minecraft.world.item.ItemStack.EMPTY;
        if (!state.disc.isEmpty()) {
            state.discAngle = com.kuronami.musicdiscmaker.compat.amendments.AmendmentsCompat.spin(cart)
                    .angle(cart.level(), cart.isDiscSpinning(), partialTicks);
        }
    }

    @Override
    protected void submitMinecartContents(State state, net.minecraft.client.renderer.block.BlockModelRenderState blockModel,
            com.mojang.blaze3d.vertex.PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector,
            int lightCoords) {
        super.submitMinecartContents(state, blockModel, pose, collector, lightCoords);
        if (!state.disc.isEmpty()) {
            com.kuronami.musicdiscmaker.compat.amendments.AmendmentsDiscSubmitter.submitOnTop(state.disc,
                    state.discAngle, com.kuronami.musicdiscmaker.compat.amendments.AmendmentsCompat.GOLDEN_DISC_Y,
                    pose, collector, lightCoords);
        }
    }
}
*///?} elif >=1.21.4 {
public class GoldenJukeboxMinecartRenderer
        extends AbstractMinecartRenderer<GoldenJukeboxMinecartEntity, MinecartRenderState> {

    public GoldenJukeboxMinecartRenderer(EntityRendererProvider.Context context) {
        super(context, ModelLayers.CHEST_MINECART);
    }

    @Override
    public MinecartRenderState createRenderState() {
        return new MinecartRenderState();
    }
}
//?} else {
/*public class GoldenJukeboxMinecartRenderer extends MinecartRenderer<GoldenJukeboxMinecartEntity> {

    public GoldenJukeboxMinecartRenderer(EntityRendererProvider.Context context) {
        super(context, ModelLayers.CHEST_MINECART);
    }

    @Override
    protected void renderMinecartContents(GoldenJukeboxMinecartEntity cart, float partialTicks,
            net.minecraft.world.level.block.state.BlockState state, com.mojang.blaze3d.vertex.PoseStack pose,
            net.minecraft.client.renderer.MultiBufferSource buffers, int packedLight) {
        super.renderMinecartContents(cart, partialTicks, state, pose, buffers, packedLight);
        if (!com.kuronami.musicdiscmaker.compat.amendments.AmendmentsCompat.isActive()) {
            return;
        }
        final net.minecraft.world.item.ItemStack disc = cart.shownDisc();
        if (disc.isEmpty()) {
            return;
        }
        com.kuronami.musicdiscmaker.compat.amendments.AmendmentsJukeboxDisc.drawOnTop(disc,
                com.kuronami.musicdiscmaker.compat.amendments.AmendmentsCompat.spin(cart),
                cart.isDiscSpinning(), cart.level(), partialTicks,
                com.kuronami.musicdiscmaker.compat.amendments.AmendmentsCompat.GOLDEN_DISC_Y,
                pose, buffers, packedLight);
    }
}
*///?}
