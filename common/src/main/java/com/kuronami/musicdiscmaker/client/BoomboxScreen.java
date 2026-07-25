package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.network.BoomboxConfigPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * ブームボックス専用の設定 GUI。部品は 3 つだけ — ディスクスロット / 音量スライダー / 指向性トグル。
 *
 * <p>強化版ジュークボックスの画面は流用しない (kura 裁定)。あちらの transport 一式・シークバー・
 * 可聴範囲スライダーは携帯プレイヤーには要らない。曲の頭出しやリピートを持たないぶん、
 * 「入れる・音量・鳴り方」だけの画面になる。
 *
 * <p>変更は {@link BoomboxConfigPayload} で server の component へ反映され、鳴っていれば
 * <b>鳴らし直さずに</b> keep-alive 経由で client の {@code BoomboxAnchor} へ届く。
 */
public class BoomboxScreen extends AbstractContainerScreen<BoomboxMenu> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/gui/boombox.png");
    private static final int TEXT = 0x404040;
    private static final int SUB_TEXT = 0x606060;

    // 指向性スプライト (TEXTURE 内 16x16)。ON = 片側だけに広がる波 / OFF = 左右対称。
    private static final int ICON_DIR_ON_U = 176;
    private static final int ICON_DIR_OFF_U = 192;
    private static final int ICON_DIR_V = 0;
    private static final int ICON_SPRITE = 16;

    // レイアウト幾何 (leftPos/topPos 相対)。値は branding/gen_boombox_gui.py (レイアウトの正本) と一致。
    private static final int TEXT_X = 30;
    private static final int TEXT_Y = 23;
    private static final int TEXT_W = 176 - 30 - 8;
    private static final int VOLUME_X = 8;
    private static final int VOLUME_Y = 42;
    private static final int VOLUME_W = 140;
    private static final int SLIDER_H = 15;
    private static final int DIR_X = 152;
    private static final int DIR_Y = 41;
    private static final int DIR_SIZE = 16;

    private int curVolume = BoomboxContents.VOLUME_DEFAULT;
    private boolean curDirectional;
    private IconButton directionalButton;

    public BoomboxScreen(BoomboxMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = 72;
    }

    @Override
    protected void init() {
        super.init();
        final BoomboxContents contents = menu.contents();
        this.curVolume = contents.volumePercent();
        this.curDirectional = contents.directional();

        // 音量はライブ反映されるのでドラッグ中も逐次送る (スピーカーと違い集合の再配布を伴わない)。
        addRenderableWidget(new SettingSlider(leftPos + VOLUME_X, topPos + VOLUME_Y, VOLUME_W, SLIDER_H,
                BoomboxContents.VOLUME_MIN, BoomboxContents.VOLUME_MAX, curVolume,
                "gui.music_disc_maker.boombox.volume", false, v -> {
                    curVolume = v;
                    sendConfig();
                }));
        this.directionalButton = addRenderableWidget(new IconButton(TEXTURE,
                leftPos + DIR_X, topPos + DIR_Y, DIR_SIZE, DIR_SIZE, ICON_SPRITE, true,
                Component.translatable("gui.music_disc_maker.boombox.directional"), () -> {
                    curDirectional = !curDirectional;
                    sendConfig();
                    refreshDirectionalSprite();
                }));
        refreshDirectionalSprite();
    }

    private void refreshDirectionalSprite() {
        if (directionalButton == null) {
            return;
        }
        directionalButton.setSprite(curDirectional ? ICON_DIR_ON_U : ICON_DIR_OFF_U, ICON_DIR_V);
        directionalButton.setTooltip(Tooltip.create(Component.translatable(curDirectional
                ? "gui.music_disc_maker.boombox.directional.on"
                : "gui.music_disc_maker.boombox.directional.off")));
    }

    private void sendConfig() {
        Services.NETWORK.sendToServer(
                new BoomboxConfigPayload(menu.getBoomboxId(), curVolume, curDirectional));
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.blit(TEXTURE, leftPos, topPos, 0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);

        final ItemStack disc = menu.contents().disc();
        final Component line;
        if (disc.isEmpty()) {
            line = Component.translatable("gui.music_disc_maker.boombox.empty");
        } else {
            final CustomTrackData track = disc.get(ModDataComponents.CUSTOM_TRACK.get());
            line = (track != null && !track.isEmpty() && !track.title().isBlank())
                    ? Component.literal(track.title())
                    : disc.getHoverName();
        }
        g.drawString(font, font.plainSubstrByWidth(line.getString(), TEXT_W),
                TEXT_X, TEXT_Y, disc.isEmpty() ? SUB_TEXT : TEXT, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    // AbstractContainerScreen(1.21.1) は mouseDragged / mouseReleased を子ウィジェットへ委譲しない
    // (quick-craft 処理で super を呼ばず握りつぶす)。GoldenJukeboxScreen と同じ理由で自前転送する。

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        final GuiEventListener focused = getFocused();
        if (button == 0 && isDragging() && focused != null
                && focused.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        final GuiEventListener focused = getFocused();
        boolean widgetHandled = false;
        if (button == 0 && isDragging() && focused != null) {
            widgetHandled = focused.mouseReleased(mouseX, mouseY, button);
        }
        setDragging(false);
        final boolean containerHandled = super.mouseReleased(mouseX, mouseY, button);
        return widgetHandled || containerHandled;
    }
}
