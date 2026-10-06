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
 */
//? if >=1.21.4 {
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
}
*///?}
