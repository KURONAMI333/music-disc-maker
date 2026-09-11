package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.menu.SpeakerMenu;

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
//? if >=1.21.2 {
import net.minecraft.client.input.MouseButtonEvent;
//?} else {
//?}
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

/** 固定speakerの音量を調整する画面。 */
public final class SpeakerScreen extends AbstractContainerScreen<SpeakerMenu> {

    private VolumeSlider volumeSlider;

    public SpeakerScreen(SpeakerMenu menu, Inventory inventory, Component title) {
        //? if >=26.1 {
        super(menu, inventory, title, SpeakerScreenLayout.WIDTH, SpeakerScreenLayout.HEIGHT);
        //?} else {
/*        super(menu, inventory, title);
        this.imageWidth = SpeakerScreenLayout.WIDTH;
        this.imageHeight = SpeakerScreenLayout.HEIGHT;
*/
        //?}
    }

    @Override
    protected void init() {
        super.init();
        volumeSlider = addRenderableWidget(new VolumeSlider(leftPos + SpeakerScreenLayout.SLIDER_X,
                topPos + SpeakerScreenLayout.SLIDER_Y, menu.getVolumePercent()));
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(leftPos + SpeakerScreenLayout.CLOSE_X, topPos + SpeakerScreenLayout.CLOSE_Y,
                        SpeakerScreenLayout.CLOSE_WIDTH, 20).build());
    }

    @Override public void containerTick() {
        super.containerTick();
        if (volumeSlider != null && !volumeSlider.editing) volumeSlider.setFromServer(menu.getVolumePercent());
    }

    //? if <1.21.2 {
    /*@Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        final var focused = getFocused();
        if (button == 0 && isDragging() && focused != null
                && focused.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        final var focused = getFocused();
        final boolean handled = button == 0 && isDragging() && focused != null
                && focused.mouseReleased(mouseX, mouseY, button);
        setDragging(false);
        return handled || super.mouseReleased(mouseX, mouseY, button);
    }
    *///?}

    @Override
    //? if >=26.1 {
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        drawPanel(graphics);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, title, 14, 14, 0xFFFFFFFF, false);
    //?} else {
/*    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        drawPanel(graphics);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 14, 14, 0xFFFFFF, false);
*/
    //?}
    }

    //? if >=26.1 {
    private void drawPanel(GuiGraphicsExtractor graphics) {
    //?} else {
    /*private void drawPanel(GuiGraphics graphics) {
    *///?}
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF242424);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xFF484848);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + 4, 0xFF858585);
    }

    private final class VolumeSlider extends AbstractSliderButton {
        private int current;
        private boolean editing;
        private Integer pendingVolume;
        private int pendingTicks;

        VolumeSlider(int x, int y, int initial) {
            super(x, y, SpeakerScreenLayout.SLIDER_WIDTH, 20, Component.empty(), Mth.clamp(initial, 0, 200) / 200.0D);
            current = Mth.clamp(initial, 0, 200);
            updateMessage();
        }

        void setFromServer(int next) {
            // A newer edit by another player may replace our value before it is echoed.
            if (pendingVolume != null && next != pendingVolume && pendingTicks-- > 0) return;
            pendingVolume = null;
            current = Mth.clamp(next, 0, 200);
            value = current / 200.0D;
            updateMessage();
        }

        private int computed() {
            return Mth.clamp((int) Math.round(value * 100.0D), 0, 100) * 2;
        }

        @Override protected void updateMessage() {
            setMessage(Component.translatable("gui.music_disc_maker.golden_jukebox.volume", current));
        }

        @Override protected void applyValue() {
            final int next = computed();
            if (next != current) {
                current = next;
                value = next / 200.0D;
                pendingVolume = next;
                pendingTicks = 40;
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, next / 2);
                updateMessage();
            }
        }

        @Override
        //? if >=1.21.2 {
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            final boolean handled = super.mouseClicked(event, doubleClick);
            editing = handled && event.button() == 0;
            return handled;
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            final boolean handled = super.mouseReleased(event);
            if (event.button() == 0) editing = false;
            return handled;
        }
        //?} else {
/*        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            final boolean handled = super.mouseClicked(mouseX, mouseY, button);
            editing = handled && button == 0;
            return handled;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            final boolean handled = super.mouseReleased(mouseX, mouseY, button);
            if (button == 0) editing = false;
            return handled;
        }
*/
        //?}
    }
}
