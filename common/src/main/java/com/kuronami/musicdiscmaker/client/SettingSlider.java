package com.kuronami.musicdiscmaker.client;

import java.util.function.IntConsumer;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * 整数値スライダー。ドラッグ/キーで値が変わったら {@code onChange} を呼ぶ (同一整数は dedup)。
 * 強化版ジュークボックスとスピーカーの設定 GUI で共有する。
 *
 * <p>脇役スライダーとして、暗く低コントラストな見た目のまま、ラベルがバー内に読める太さ (溝を全高) で
 * 自前描画する。バニラの明るい widget は使わない。
 */
public final class SettingSlider extends AbstractSliderButton {

    private final int min;
    private final int max;
    private final String labelKey;
    private final IntConsumer onChange;
    /** true = ドラッグ中は onChange を送らず、リリース/キー操作の確定時に一度だけ送る。 */
    private final boolean commitOnRelease;
    private int current;
    /** commitOnRelease 時、未送信の変更があるか。 */
    private boolean pendingCommit;

    public SettingSlider(int x, int y, int width, int height, int min, int max, int initial,
            String labelKey, boolean commitOnRelease, IntConsumer onChange) {
        super(x, y, width, height, CommonComponents.EMPTY, (initial - min) / (double) (max - min));
        this.min = min;
        this.max = max;
        this.labelKey = labelKey;
        this.commitOnRelease = commitOnRelease;
        this.onChange = onChange;
        this.current = initial;
        updateMessage();
    }

    private int compute() {
        return min + (int) Math.round(this.value * (max - min));
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.translatable(labelKey, compute()));
    }

    @Override
    protected void applyValue() {
        final int v = compute();
        if (v != current) {
            current = v;
            if (commitOnRelease) {
                pendingCommit = true; // ドラッグ中は送らない。ラベルだけ追従させる。
            } else {
                onChange.accept(v);
            }
        }
    }

    /** 保留中の変更を確定 (送信) する。 */
    private void commitPending() {
        if (pendingCommit) {
            pendingCommit = false;
            onChange.accept(current);
        }
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        final boolean handled = super.mouseReleased(mouseX, mouseY, button);
        commitPending(); // ドラッグ/クリックの確定
        return handled;
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        final int x = getX();
        final int y = getY();
        final int w = width;
        final int h = height;
        // 凹んだ溝 (全高)。外周 1px 暗縁 + 内側の暗い地。
        g.fill(x, y, x + w, y + h, 0xFF161616);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF2C2C2C);
        // 進捗 (控えめなグレー)。
        final int fillW = (int) Math.round(this.value * (w - 2));
        if (fillW > 0) {
            g.fill(x + 1, y + 1, x + 1 + fillW, y + h - 1, 0xFF464646);
        }
        // 上辺 1px ハイライト / 下辺 1px シャドウ (わずかな奥行き)。
        g.fill(x + 1, y + 1, x + w - 1, y + 2, 0xFF383838);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, 0xFF121212);
        // つまみ (中立グレー・全高ハンドル)。
        final int knobX = x + (int) Math.round(this.value * (w - 4));
        final int knob = isHoveredOrFocused() ? 0xFFA6A6A6 : 0xFF848484;
        g.fill(knobX, y, knobX + 4, y + h, 0xFF121212);
        g.fill(knobX + 1, y + 1, knobX + 3, y + h - 1, knob);
        // ラベル (バー内に読めるよう影付き・中央)。
        final var font = net.minecraft.client.Minecraft.getInstance().font;
        final int tw = font.width(getMessage());
        g.drawString(font, getMessage(), x + (w - tw) / 2, y + (h - 8) / 2, 0xFFD8D8D8, true);
    }

    // 矢印キー左右で整数値を正確に ±1 する。AbstractSliderButton の既定は fraction を
    // 微小量ずらすため丸め誤差で ±1/±2 が混ざる。整数側で離散ステップして fraction を
    // 再計算することで常に 1 ずつ動かす。
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        final int dir = keyCode == GLFW.GLFW_KEY_RIGHT ? 1
                : keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 0;
        if (dir != 0) {
            final int nv = Mth.clamp(current + dir, min, max);
            if (nv != current) {
                this.value = (nv - min) / (double) (max - min);
                applyValue();     // current 更新 (+ 非 defer なら onChange 発火)
                commitPending();  // 矢印は離散操作なので即確定
                updateMessage();
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
