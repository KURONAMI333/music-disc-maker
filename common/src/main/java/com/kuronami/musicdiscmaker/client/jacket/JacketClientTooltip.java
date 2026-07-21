package com.kuronami.musicdiscmaker.client.jacket;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;

/**
 * ツールチップにジャケット画像を描く {@link ClientTooltipComponent}。
 * {@link JacketCache} から準備できた texture を取り、アスペクト比を保って固定枠に収める。
 * 準備前・失敗時は 0 サイズ (領域を確保しない = 空箱が残らない fail-soft)。
 */
public class JacketClientTooltip implements ClientTooltipComponent {

    /** 収める最大枠 (px)。 */
    private static final int MAX = 96;
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
    public int getHeight() {
        final int[] fit = fittedSize();
        return fit == null ? 0 : fit[1] + PAD * 2;
    }

    @Override
    public int getWidth(Font font) {
        final int[] fit = fittedSize();
        return fit == null ? 0 : fit[0];
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics g) {
        final JacketCache.Jacket j = jacket();
        final int[] fit = fittedSize();
        if (j == null || fit == null) {
            return;
        }
        g.blit(j.texture(), x, y + PAD, fit[0], fit[1], 0.0F, 0.0F, j.width(), j.height(), j.width(), j.height());
    }
}
