package com.kuronami.musicdiscmaker.client.jacket;

import net.minecraft.client.gui.Font;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
//? if >=1.21.2 {
import net.minecraft.client.renderer.RenderPipelines;
//?} else {
//?}

/**
 * ツールチップにジャケット画像を描く {@link ClientTooltipComponent}。
 * {@link JacketCache} から準備できた texture を取り、アスペクト比を保って固定枠に収める。
 * 準備前・失敗時は 0 サイズ (領域を確保しない = 空箱が残らない fail-soft)。
 *
 * <p>26.1.2: 描画は {@link #extractImage} (旧 {@code renderImage} は廃止) で {@link GuiGraphicsExtractor}
 * に対して行う。サイズ確定は {@link #getWidth}/{@link #getHeight} (どちらも Font を取る)。
 */
public class JacketClientTooltip implements ClientTooltipComponent {

    /** 収める最大枠 (px)。 */
    private static final int MAX = 64;
    /** テキストとの上下余白。 */
    private static final int PAD = 2;

    private final String url;

    public JacketClientTooltip(JacketTooltip tooltip) {
        this.url = tooltip.url();
    }

    private JacketCache.Jacket jacket() {
        return JacketCache.get(url);
    }

    /** 元サイズを MAX×MAX に収めた表示サイズ [w, h]。ジャケット未準備なら null。 */
    private int[] fittedSize() {
        final JacketCache.Jacket j = jacket();
        if (j == null || j.width() <= 0 || j.height() <= 0) {
            return null;
        }
        final double scale = Math.min((double) MAX / j.width(), (double) MAX / j.height());
        final int w = Math.max(1, (int) Math.round(j.width() * scale));
        final int h = Math.max(1, (int) Math.round(j.height() * scale));
        return new int[] {w, h};
    }

    @Override
    //? if >=1.21.2 {
    public int getHeight(Font font) {
    //?} else {
    /*public int getHeight() {
    *///?}
        final int[] fit = fittedSize();
        return fit == null ? 0 : fit[1] + PAD * 2;
    }

    @Override
    public int getWidth(Font font) {
        final int[] fit = fittedSize();
        return fit == null ? 0 : fit[0];
    }

    @Override
    //? if >=26.1 {
    public void extractImage(Font font, int x, int y, int w, int h, GuiGraphicsExtractor graphics) {
    //?} elif >=1.21.2 {
    /*public void renderImage(Font font, int x, int y, int w, int h, GuiGraphics graphics) {
    *///?} else {
    /*public void renderImage(Font font, int x, int y, GuiGraphics g) {
    *///?}
        final JacketCache.Jacket j = jacket();
        final int[] fit = fittedSize();
        if (j == null || fit == null) {
            return;
        }
        //? if >=1.21.2 {
        // 全体をアスペクト比維持で fit[0]×fit[1] の枠に縮小描画する (srcWidth/Height = 元寸法)。
        graphics.blit(RenderPipelines.GUI_TEXTURED, j.texture(), x, y + PAD, 0.0F, 0.0F,
                fit[0], fit[1], j.width(), j.height(), j.width(), j.height());
        //?} else {
        /*g.blit(j.texture(), x, y + PAD, fit[0], fit[1], 0.0F, 0.0F, j.width(), j.height(), j.width(), j.height());
        *///?}
    }
}

