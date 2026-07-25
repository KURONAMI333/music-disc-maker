package com.kuronami.musicdiscmaker.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * アイコンのみのフラットボタン (音楽プレイヤー風)。強化版ジュークボックスとブームボックスの
 * GUI で共有する ({@link SettingSlider} と同じ「画面をまたぐ widget は共有クラスへ」の扱い)。
 *
 * <p>スプライトは呼び出し側が渡す blit シート内の uv。シートは 256x256 前提。
 */
public final class IconButton extends AbstractButton {

    // 枠つきトグルの凹み (バニラのスロットと同じ 3 値。sheet の draw_slot と揃える)
    private static final int FRAME_FILL = 0xFF8B8B8B;
    private static final int FRAME_SH = 0xFF373737;
    private static final int FRAME_HL = 0xFFFFFFFF;

    private final ResourceLocation sheet;
    private final int spriteSize;
    private final boolean framed;
    private final Runnable onPress;
    private int u;
    private int v;

    public IconButton(ResourceLocation sheet, int x, int y, int size, int spriteSize, Component narration,
            Runnable onPress) {
        this(sheet, x, y, size, size, spriteSize, false, narration, onPress);
    }

    public IconButton(ResourceLocation sheet, int x, int y, int width, int height, int spriteSize,
            boolean framed, Component narration, Runnable onPress) {
        super(x, y, width, height, narration);
        this.sheet = sheet;
        this.spriteSize = spriteSize;
        this.framed = framed;
        this.onPress = onPress;
    }

    public void setSprite(int u, int v) {
        this.u = u;
        this.v = v;
    }

    @Override
    public void onPress() {
        onPress.run();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 枠つきは「押せる四角」であることを見せる (スプライトだけだとパネルに浮いた飾りに
        // 見え、状態も読み取りにくい)。枠はバニラのスロットと同じ凹みの文法 —
        // 上/左が暗・下/右が明・内側が中間グレー。
        if (framed) {
            g.fill(getX(), getY(), getX() + width, getY() + height, FRAME_FILL);
            g.fill(getX(), getY(), getX() + width, getY() + 1, FRAME_SH);
            g.fill(getX(), getY(), getX() + 1, getY() + height, FRAME_SH);
            g.fill(getX(), getY() + height - 1, getX() + width, getY() + height, FRAME_HL);
            g.fill(getX() + width - 1, getY(), getX() + width, getY() + height, FRAME_HL);
        }
        // スプライト。focus/hover 背景は出さない (renderWidget を super 無しで全上書き
        // しているのでバニラ AbstractWidget の背景も元々描かれない)。
        final int ix = getX() + (width - spriteSize) / 2;
        final int iy = getY() + (height - spriteSize) / 2;
        g.blit(sheet, ix, iy, (float) u, (float) v, spriteSize, spriteSize, 256, 256);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput out) {
        defaultButtonNarrationText(out);
    }
}
