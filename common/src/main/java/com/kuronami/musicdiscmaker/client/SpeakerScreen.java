package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.menu.SpeakerMenu;
import com.kuronami.musicdiscmaker.network.SpeakerConfigPayload;
import com.kuronami.musicdiscmaker.platform.Services;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * スピーカーの設定 GUI。音量・可聴範囲のスライダー 2 本と、リンク先の表示だけを持つ小さな画面。
 * リンク操作は載せない (リンクはブロックアイテムのシフト右クリックで行う)。
 */
public class SpeakerScreen extends AbstractContainerScreen<SpeakerMenu> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/gui/speaker.png");
    private static final int TEXT = 0x404040;
    private static final int SUB_TEXT = 0x606060;

    // レイアウト幾何 (leftPos/topPos 相対)。値は branding/gen_speaker_gui.py (レイアウトの正本) と一致させる。
    private static final int VOLUME_Y = 20;
    private static final int RANGE_Y = 38;
    private static final int SLIDER_X = 8;
    private static final int SLIDER_W = 160;
    private static final int SLIDER_H = 15;
    private static final int STATUS_Y = 58;

    private int curVolume = SpeakerBlockEntity.VOLUME_DEFAULT;
    private int curRange = SpeakerBlockEntity.RANGE_DEFAULT;

    public SpeakerScreen(SpeakerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 76;
    }

    @Override
    protected void init() {
        super.init();
        final SpeakerBlockEntity be = menu.getBlockEntity();
        this.curVolume = be.getVolumePercent();
        this.curRange = be.getRangeBlocks();

        addRenderableWidget(new SettingSlider(leftPos + SLIDER_X, topPos + VOLUME_Y, SLIDER_W, SLIDER_H,
                SpeakerBlockEntity.VOLUME_MIN, SpeakerBlockEntity.VOLUME_MAX, curVolume,
                "gui.music_disc_maker.speaker.volume", false, v -> {
                    curVolume = v;
                    sendConfig();
                }));
        addRenderableWidget(new SettingSlider(leftPos + SLIDER_X, topPos + RANGE_Y, SLIDER_W, SLIDER_H,
                SpeakerBlockEntity.RANGE_MIN, SpeakerBlockEntity.RANGE_MAX, curRange,
                "gui.music_disc_maker.speaker.range", true, v -> {
                    curRange = v;
                    sendConfig();
                }));
    }

    private void sendConfig() {
        Services.NETWORK.sendToServer(new SpeakerConfigPayload(
                menu.getBlockEntity().getBlockPos(), curVolume, curRange));
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.blit(TEXTURE, leftPos, topPos, 0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, this.title, titleLabelX, titleLabelY, TEXT, false);

        final SpeakerBlockEntity be = menu.getBlockEntity();
        final BlockPos source = be.getSourcePos();
        final Component status;
        if (source == null) {
            status = Component.translatable("gui.music_disc_maker.speaker.unlinked");
        } else if (be.isMuted()) {
            status = Component.translatable("gui.music_disc_maker.speaker.muted");
        } else {
            status = Component.translatable("gui.music_disc_maker.speaker.linked",
                    source.getX(), source.getY(), source.getZ());
        }
        g.drawString(font, font.plainSubstrByWidth(status.getString(), imageWidth - 16),
                SLIDER_X, STATUS_Y, SUB_TEXT, false);
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
