package com.kuronami.musicdiscmaker.client.render;

//? if >=26.1 && <26.2 {
/*import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.compat.amendments.AmendmentsCompat;
import com.kuronami.musicdiscmaker.compat.amendments.AmendmentsDiscSubmitter;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/^*
 * Amendments 導入時だけ登録する、金のジュークボックスの上で回る盤 (26.1.2)。
 * Amendments がバニラのジュークボックスに出す盤と同じ見た目・同じ回り方 ({@link AmendmentsCompat})。
 * アルバムは今鳴っている (次に鳴る) 盤を出す。BE に触れるのは抽出の段だけ。
 ^/
public class GoldenJukeboxDiscRenderer
        implements BlockEntityRenderer<GoldenJukeboxBlockEntity, GoldenJukeboxDiscRenderer.State> {

    /^* 抽出した描画状態。 ^/
    public static class State extends BlockEntityRenderState {
        public ItemStack disc = ItemStack.EMPTY;
        public float angle;
        public int upLight;
    }

    public GoldenJukeboxDiscRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(GoldenJukeboxBlockEntity jukebox, State state, float partialTicks,
            Vec3 cameraPosition, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(jukebox, state, partialTicks, cameraPosition, breakProgress);
        final Level level = jukebox.getLevel();
        state.disc = level == null ? ItemStack.EMPTY : jukebox.shownDisc();
        if (state.disc.isEmpty()) {
            return;
        }
        state.angle = AmendmentsCompat.spin(jukebox).angle(level, jukebox.isPlayingState(), partialTicks);
        state.upLight = LevelRenderer.getLightCoords(level, jukebox.getBlockPos().above());
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.disc.isEmpty()) {
            return;
        }
        AmendmentsDiscSubmitter.submitOnTop(state.disc, state.angle, AmendmentsCompat.GOLDEN_DISC_Y, pose,
                collector, state.upLight);
    }
}
*///?} elif <1.21.2 {
/*import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.compat.amendments.AmendmentsCompat;
import com.kuronami.musicdiscmaker.compat.amendments.AmendmentsJukeboxDisc;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/^*
 * Amendments 導入時だけ登録する、金のジュークボックスの上で回る盤 (1.20.1 / 1.21.1)。
 * Amendments がバニラのジュークボックスに出す盤と同じ見た目・同じ回り方 ({@link AmendmentsCompat})。
 * アルバムは今鳴っている (次に鳴る) 盤を出す。
 ^/
public class GoldenJukeboxDiscRenderer implements BlockEntityRenderer<GoldenJukeboxBlockEntity> {

    public GoldenJukeboxDiscRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(GoldenJukeboxBlockEntity jukebox, float partialTick, PoseStack pose,
            MultiBufferSource buffers, int packedLight, int packedOverlay) {
        final Level level = jukebox.getLevel();
        if (level == null) {
            return;
        }
        final ItemStack disc = jukebox.shownDisc();
        if (disc.isEmpty()) {
            return;
        }
        final int light = LevelRenderer.getLightColor(level, jukebox.getBlockPos().above());
        AmendmentsJukeboxDisc.drawOnTop(disc, AmendmentsCompat.spin(jukebox), jukebox.isPlayingState(),
                level, partialTick, AmendmentsCompat.GOLDEN_DISC_Y, pose, buffers, light);
    }
}
*///?}
