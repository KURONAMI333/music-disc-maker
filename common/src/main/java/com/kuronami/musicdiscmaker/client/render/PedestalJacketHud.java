package com.kuronami.musicdiscmaker.client.render;

import com.kuronami.musicdiscmaker.block.DiscPedestalBlockEntity;
import com.kuronami.musicdiscmaker.client.jacket.JacketCache;
import com.kuronami.musicdiscmaker.client.jacket.JacketUrls;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;

import net.minecraft.client.Minecraft;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
//? if >=1.21.11 {
import net.minecraft.client.renderer.RenderPipelines;
//?} elif >=1.21.4 {
/*import net.minecraft.client.renderer.RenderType;
*///?} else {
//?}
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 台座を見ている間、照準の右にその盤のジャケットを出す (HappyMeek の要望、2026-10-09 本人裁定
 * 「照準の横に出す」)。曲名と歌手は台座の正面に文字で出ているので、ここは絵だけ。ジャケットの無い盤
 * (バニラの盤・画像の取れない URL) では何も出さない。描くのは照準の描画の後 ({@code MixinPedestalJacketHud})。
 */
public final class PedestalJacketHud {

    /** 収める最大枠 (px)。照準の横なので、ツールチップ (96) より小さくする。 */
    private static final int MAX = 64;
    /** 照準の中心からの距離 (px)。 */
    private static final int GAP = 12;
    private static final int PAD = 2;
    /** ツールチップの背景に近い、半透明の暗い色。 */
    private static final int BACKGROUND = 0xC0100010;

    private PedestalJacketHud() {
    }

    //? if >=26.1 {
    public static void render(GuiGraphicsExtractor graphics) {
    //?} else {
    /*public static void render(GuiGraphics graphics) {
    *///?}
        final Minecraft mc = Minecraft.getInstance();
        // HUD を隠している時は照準ごと描かれないので、ここでは見ない。
        //? if >=26.2 {
        final boolean screenOpen = mc.gui.screen() != null;
        //?} else {
        /*final boolean screenOpen = mc.screen != null;
        *///?}
        if (mc.level == null || screenOpen || !mc.options.getCameraType().isFirstPerson()
                || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !(mc.level.getBlockEntity(hit.getBlockPos()) instanceof DiscPedestalBlockEntity pedestal)) {
            return;
        }
        final CustomTrackData track = CustomMusicDiscItem.getTrack(pedestal.getStored());
        final String url = JacketUrls.effectiveUrl(track);
        if (url.isBlank()) {
            return;
        }
        final JacketCache.Jacket jacket = JacketCache.get(url);
        if (jacket == null || jacket.width() <= 0 || jacket.height() <= 0) {
            return;
        }
        final double scale = Math.min((double) MAX / jacket.width(), (double) MAX / jacket.height());
        final int w = Math.max(1, (int) Math.round(jacket.width() * scale));
        final int h = Math.max(1, (int) Math.round(jacket.height() * scale));
        final int x = graphics.guiWidth() / 2 + GAP;
        final int y = graphics.guiHeight() / 2 - h / 2;
        graphics.fill(x - PAD, y - PAD, x + w + PAD, y + h + PAD, BACKGROUND);
        //? if >=1.21.11 {
        graphics.blit(RenderPipelines.GUI_TEXTURED, jacket.texture(), x, y, 0.0F, 0.0F,
                w, h, jacket.width(), jacket.height(), jacket.width(), jacket.height());
        //?} elif >=1.21.4 {
        /*graphics.blit(RenderType::guiTextured, jacket.texture(), x, y,
                0.0F, 0.0F, w, h, jacket.width(), jacket.height(), jacket.width(), jacket.height());
        *///?} else {
        /*graphics.blit(jacket.texture(), x, y, w, h, 0.0F, 0.0F, jacket.width(), jacket.height(),
                jacket.width(), jacket.height());
        *///?}
    }
}
