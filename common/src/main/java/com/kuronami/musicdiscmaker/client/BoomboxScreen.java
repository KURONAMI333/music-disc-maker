package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.network.ControlBoomboxPayload;
import com.kuronami.musicdiscmaker.platform.Services;

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
//? if >=1.21.2 {
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
//?} else {
/*import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * ブームボックスの 1 枠 GUI。媒体は menu の slot 0、再生状態は server の snapshot を正本として描画する。
 *
 * <p>ラジオの表示窓、操作キー、音量、在庫を並べる。携帯再生は範囲・
 * 指向性を持たないため、その操作だけを画面から外している。
 */
public class BoomboxScreen extends AbstractContainerScreen<BoomboxMenu> {

    // 再生記号は共通。筐体とキーの描画はBoombox専用。
    private static final String SPRITE_ROOT = "textures/gui/golden_jukebox/transport/";
    private static final String SPRITE_PLAY = "play.png";
    private static final String SPRITE_PAUSE = "pause.png";
    private static final String SPRITE_PREVIOUS = "prev.png";
    private static final String SPRITE_PREVIOUS_OFF = "prev_off.png";
    private static final String SPRITE_NEXT = "next.png";
    private static final String SPRITE_NEXT_OFF = "next_off.png";
    private static final String SPRITE_REPEAT_ON = "repeat_on.png";
    private static final String SPRITE_REPEAT_OFF = "repeat_off.png";
    private static final String SPRITE_SHUFFLE_ON = "shuffle_on.png";
    private static final String SPRITE_SHUFFLE_OFF = "shuffle_off.png";

    private static final int TEXT = 0xFFFFD58A;
    private static final int INVENTORY_TEXT = 0xFF404040;
    private static final int TIME_TEXT = 0xFFE5C99C;
    private static final int LIVE_TEXT = 0xFFD03030;
    private static final int CASE = 0xFF484848;
    private static final int CASE_EDGE = 0xFF242424;
    private static final int DISPLAY = 0xFF292723;
    private static final int LOOP_SPRITE = 16;

    private IconButton shuffleButton;
    private IconButton previousButton;
    private IconButton playPauseButton;
    private IconButton nextButton;
    private IconButton repeatButton;
    private VolumeSlider volumeSlider;
    private SeekBar seekBar;

    public BoomboxScreen(BoomboxMenu menu, Inventory playerInventory, Component title) {
        //? if >=26.1 {
        super(menu, playerInventory, title, BoomboxScreenLayout.PANEL_WIDTH, BoomboxScreenLayout.PANEL_HEIGHT);
        //?} else {
        /*super(menu, playerInventory, title);
        this.imageWidth = BoomboxScreenLayout.PANEL_WIDTH;
        this.imageHeight = BoomboxScreenLayout.PANEL_HEIGHT;
        *///?}
        this.inventoryLabelX = BoomboxScreenLayout.INVENTORY_X;
        this.inventoryLabelY = BoomboxScreenLayout.INVENTORY_LABEL_Y;
    }

    @Override
    protected void init() {
        super.init();
        this.shuffleButton = addRenderableWidget(button(GoldenJukeboxTransportLayout.SHUFFLE,
                "gui.music_disc_maker.golden_jukebox.shuffle_toggle",
                () -> sendControl(ControlBoomboxPayload.SHUFFLE, toggledShuffleValue())));
        this.previousButton = addRenderableWidget(button(GoldenJukeboxTransportLayout.PREVIOUS,
                "gui.music_disc_maker.golden_jukebox.previous",
                () -> sendControl(ControlBoomboxPayload.PREVIOUS, 0L)));
        this.playPauseButton = addRenderableWidget(button(GoldenJukeboxTransportLayout.PLAY_PAUSE,
                "gui.music_disc_maker.golden_jukebox.play", this::togglePlayPause));
        this.nextButton = addRenderableWidget(button(GoldenJukeboxTransportLayout.NEXT,
                "gui.music_disc_maker.golden_jukebox.next",
                () -> sendControl(ControlBoomboxPayload.NEXT, 0L)));
        this.repeatButton = addRenderableWidget(button(GoldenJukeboxTransportLayout.REPEAT,
                "gui.music_disc_maker.golden_jukebox.repeat_toggle",
                () -> sendControl(ControlBoomboxPayload.REPEAT, toggledRepeatValue())));
        this.seekBar = addRenderableWidget(new SeekBar(leftPos + BoomboxScreenLayout.TIME_X,
                topPos + BoomboxScreenLayout.SEEK_Y,
                BoomboxScreenLayout.TIME_RIGHT - BoomboxScreenLayout.TIME_X,
                BoomboxScreenLayout.SEEK_HEIGHT));
        this.volumeSlider = addRenderableWidget(new VolumeSlider(leftPos + BoomboxScreenLayout.VOLUME_X,
                topPos + BoomboxScreenLayout.VOLUME_Y, currentVolume()));
        refreshWidgets();
    }

    private IconButton button(int index, String label, Runnable action) {
        return new IconButton(leftPos + GoldenJukeboxTransportLayout.buttonX(index),
                topPos + BoomboxScreenLayout.TRANSPORT_Y, Component.translatable(label), action);
    }

    private boolean hasMedia() {
        return menu.getSlot(0).hasItem();
    }

    private CustomTrackData currentTrack() {
        return menu.currentTrack();
    }

    private boolean isLive() {
        final CustomTrackData track = currentTrack();
        return track != null && track.radio();
    }

    private boolean isSeekable() {
        final CustomTrackData track = currentTrack();
        return track != null && !track.radio() && track.durationMs() > 0L;
    }

    private int currentVolume() {
        final var state = menu.playbackState();
        return state == null ? 100 : state.volumePercent();
    }

    private boolean isPlaying() {
        final var state = menu.playbackState();
        return state != null && state.cursor().state() == PlaybackCursor.State.PLAYING;
    }

    private long generation() {
        if (!menu.hasPlaybackState()) return -1L;
        final var state = menu.playbackState();
        return state == null ? -1L : state.cursor().generation();
    }

    private long toggledRepeatValue() {
        final var state = menu.playbackState();
        return state != null && !state.repeat() ? 1L : 0L;
    }

    private long toggledShuffleValue() {
        final var state = menu.playbackState();
        return state != null && !state.shuffle() ? 1L : 0L;
    }

    /** C2S は要求だけを送る。表示の切替は必ず server snapshot を待つ。 */
    private void sendControl(int action, long value) {
        if (ClientPlaybackHandler.isProtocolMismatch()) {
            return;
        }
        final long generation = generation();
        if (generation >= 0L) {
            Services.NETWORK.sendToServer(new ControlBoomboxPayload(menu.containerId, generation, action, value));
        }
    }

    private void togglePlayPause() {
        if (hasMedia()) {
            sendControl(isPlaying() ? ControlBoomboxPayload.PAUSE : ControlBoomboxPayload.PLAY, 0L);
        }
    }

    /** menu が受信時刻から補間した server snapshot をそのまま表示する。 */
    private long displayElapsedMs() {
        final var state = menu.playbackState();
        if (state == null) {
            return 0L;
        }
        long elapsed = Math.max(0L, state.elapsedMs());
        final CustomTrackData track = currentTrack();
        return track != null && !track.radio() && track.durationMs() > 0L
                ? Math.min(elapsed, track.durationMs()) : elapsed;
    }

    private void refreshWidgets() {
        final var state = menu.playbackState();
        final boolean ready = menu.hasPlaybackState();
        final boolean media = ready && hasMedia();
        final boolean navigable = ready && menu.navigableTrackCount() > 1;
        final boolean live = isLive();
        if (shuffleButton != null) {
            shuffleButton.active = navigable;
            shuffleButton.setSprite(state != null && state.shuffle() ? SPRITE_SHUFFLE_ON : SPRITE_SHUFFLE_OFF,
                    LOOP_SPRITE);
        }
        if (previousButton != null) {
            previousButton.active = navigable;
            previousButton.setSprite(navigable ? SPRITE_PREVIOUS : SPRITE_PREVIOUS_OFF, LOOP_SPRITE);
        }
        if (playPauseButton != null) {
            playPauseButton.active = media;
            playPauseButton.setSprite(isPlaying() ? SPRITE_PAUSE : SPRITE_PLAY, 20);
        }
        if (nextButton != null) {
            nextButton.active = navigable;
            nextButton.setSprite(navigable ? SPRITE_NEXT : SPRITE_NEXT_OFF, LOOP_SPRITE);
        }
        if (repeatButton != null) {
            repeatButton.active = media && !live;
            repeatButton.setSprite(state != null && state.repeat() && !live ? SPRITE_REPEAT_ON : SPRITE_REPEAT_OFF,
                    LOOP_SPRITE);
        }
        if (seekBar != null) {
            seekBar.active = ready && isSeekable();
        }
        if (volumeSlider != null) volumeSlider.active = ready;
        if (volumeSlider != null && ready && !volumeSlider.isEditing()) {
            volumeSlider.setFromServer(currentVolume());
        }
    }

    @Override
    //? if >=26.1 {
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        drawRadioPanel(graphics);
    //?} elif >=1.21.2 {
    /*public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        drawRadioPanel(graphics);
    *///?} else {
    /*protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        drawRadioPanel(graphics);
    *///?}
    }

    //? if >=26.1 {
    private void drawRadioPanel(GuiGraphicsExtractor graphics) {
    //?} else {
    /*private void drawRadioPanel(GuiGraphics graphics) {
    *///?}
        final int x = leftPos;
        final int y = topPos;
        graphics.fill(x, y + 2, x + 176, y + 128, CASE_EDGE);
        graphics.fill(x + 2, y, x + 174, y + 126, CASE_EDGE);
        graphics.fill(x + 3, y + 3, x + 173, y + 125, CASE);
        graphics.fill(x + 3, y + 3, x + 173, y + 5, 0xFF858585);
        // The media well and track display form one recessed deck.
        graphics.fill(x + 6, y + 12, x + 170, y + 45, CASE_EDGE);
        graphics.fill(x + 28, y + 14, x + 168, y + 43, DISPLAY);
        final int discX = x + BoomboxScreenLayout.DISC_X;
        final int discY = y + BoomboxScreenLayout.DISC_Y;
        graphics.fill(discX - 1, discY - 1, discX + 17, discY + 17, 0xFF373737);
        graphics.fill(discX, discY, discX + 17, discY + 17, 0xFFC6C6C6);
        graphics.fill(discX, discY, discX + 16, discY + 16, 0xFF8B8B8B);
        graphics.fill(x + 6, y + 49, x + 170, y + 68, DISPLAY);
        // Speaker grille slots flank the transport keys.
        for (int row = 0; row < 6; row++) {
            graphics.fill(x + 8, y + 78 + row * 4, x + 23, y + 80 + row * 4, CASE_EDGE);
            graphics.fill(x + 153, y + 78 + row * 4, x + 168, y + 80 + row * 4, CASE_EDGE);
        }
        VanillaPlayerInventoryBackground.draw(graphics, leftPos,
                topPos + BoomboxScreenLayout.INVENTORY_Y);
    }

    @Override
    //? if >=26.1 {
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
    //?} else {
    /*protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
    *///?}
        //? if >=26.1 {
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, INVENTORY_TEXT, false);
        //?} else {
        /*graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, INVENTORY_TEXT, false);
        *///?}
        renderTrackLabels(graphics);
    }

    //? if >=26.1 {
    private void renderTrackLabels(GuiGraphicsExtractor graphics) {
    //?} else {
    /*private void renderTrackLabels(GuiGraphics graphics) {
    *///?}
        final CustomTrackData track = currentTrack();
        final int tracks = menu.navigableTrackCount();
        final var state = menu.playbackState();
        final String counter = state != null && tracks > 1 && state.cursor().trackIndex() >= 0
                ? (state.cursor().trackIndex() + 1) + "/" + tracks : null;
        final int titleWidth = counter == null ? BoomboxScreenLayout.TRACK_TEXT_WIDTH
                : BoomboxScreenLayout.TRACK_TEXT_WIDTH - font.width(counter) - 4;
        if (counter != null) {
            //? if >=26.1 {
            graphics.text(font, counter,
            //?} else {
            /*graphics.drawString(font, counter,
            *///?}
                    BoomboxScreenLayout.TRACK_TEXT_X + BoomboxScreenLayout.TRACK_TEXT_WIDTH - font.width(counter),
                    BoomboxScreenLayout.TITLE_Y, TIME_TEXT, false);
        }
        if (track != null) {
            //? if >=26.1 {
            graphics.text(font, font.plainSubstrByWidth(track.title(), titleWidth),
            //?} else {
            /*graphics.drawString(font, font.plainSubstrByWidth(track.title(), titleWidth),
            *///?}
                    BoomboxScreenLayout.TRACK_TEXT_X, BoomboxScreenLayout.TITLE_Y, TEXT, false);
            if (!track.author().isBlank()) {
                //? if >=26.1 {
                graphics.text(font, font.plainSubstrByWidth(track.author(), BoomboxScreenLayout.TRACK_TEXT_WIDTH),
                //?} else {
                /*graphics.drawString(font, font.plainSubstrByWidth(track.author(), BoomboxScreenLayout.TRACK_TEXT_WIDTH),
                *///?}
                        BoomboxScreenLayout.TRACK_TEXT_X, BoomboxScreenLayout.AUTHOR_Y, TIME_TEXT, false);
            }
        } else if (hasMedia()) {
            final ItemStack stack = menu.getSlot(0).getItem();
            //? if >=26.1 {
            graphics.text(font, font.plainSubstrByWidth(stack.getHoverName().getString(), titleWidth),
            //?} else {
            /*graphics.drawString(font, font.plainSubstrByWidth(stack.getHoverName().getString(), titleWidth),
            *///?}
                    BoomboxScreenLayout.TRACK_TEXT_X, BoomboxScreenLayout.TITLE_Y, TEXT, false);
        } else {
            //? if >=26.1 {
            graphics.text(font, Component.translatable("gui.music_disc_maker.golden_jukebox.no_track"),
            //?} else {
            /*graphics.drawString(font, Component.translatable("gui.music_disc_maker.golden_jukebox.no_track"),
            *///?}
                    BoomboxScreenLayout.TRACK_TEXT_X, BoomboxScreenLayout.TITLE_Y, TEXT, false);
        }
        if (track == null) {
            return;
        }
        final String elapsed = formatMs(displayElapsedMs());
        //? if >=26.1 {
        graphics.text(font, elapsed, BoomboxScreenLayout.TIME_X, BoomboxScreenLayout.TIME_Y, TIME_TEXT, false);
        //?} else {
        /*graphics.drawString(font, elapsed, BoomboxScreenLayout.TIME_X, BoomboxScreenLayout.TIME_Y, TIME_TEXT, false);
        *///?}
        if (track.radio()) {
            final Component live = Component.translatable("tooltip.music_disc_maker.live");
            //? if >=26.1 {
            graphics.text(font, live, BoomboxScreenLayout.TIME_RIGHT - font.width(live),
            //?} else {
            /*graphics.drawString(font, live, BoomboxScreenLayout.TIME_RIGHT - font.width(live),
            *///?}
                    BoomboxScreenLayout.TIME_Y, LIVE_TEXT, false);
        } else if (track.durationMs() > 0L) {
            final String total = formatMs(track.durationMs());
            //? if >=26.1 {
            graphics.text(font, total, BoomboxScreenLayout.TIME_RIGHT - font.width(total),
            //?} else {
            /*graphics.drawString(font, total, BoomboxScreenLayout.TIME_RIGHT - font.width(total),
            *///?}
                    BoomboxScreenLayout.TIME_Y, TIME_TEXT, false);
        }
    }

    private static String formatMs(long milliseconds) {
        final long seconds = Math.max(0L, milliseconds) / 1000L;
        return String.format("%d:%02d", seconds / 60L, seconds % 60L);
    }

    @Override
    //? if >=1.21.2 {
    protected void containerTick() {
        super.containerTick();
        refreshWidgets();
    //?} else {
    /*public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        refreshWidgets();
        renderTooltip(graphics, mouseX, mouseY);
    *///?}
    }

    //? if >=1.21.2 {
    //?} else {
    /*@Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        final GuiEventListener focused = getFocused();
        if (button == 0 && isDragging() && focused != null
                && focused.mouseDragged(mouseX, mouseY, button, dragX, dragY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        final GuiEventListener focused = getFocused();
        final boolean handled = button == 0 && isDragging() && focused != null
                && focused.mouseReleased(mouseX, mouseY, button);
        setDragging(false);
        return handled || super.mouseReleased(mouseX, mouseY, button);
    }
    *///?}

    /** 共通の再生記号を、ラジオの押しボタン上に描く。 */
    private static final class IconButton extends AbstractButton {

        private final Runnable action;
        private String sprite = SPRITE_PLAY;
        private int spriteSize = 20;
        private boolean mouseFocused;

        IconButton(int x, int y, Component narration, Runnable action) {
            super(x, y, GoldenJukeboxTransportLayout.BUTTON_SIZE, GoldenJukeboxTransportLayout.BUTTON_SIZE, narration);
            this.action = action;
        }

        void setSprite(String sprite, int spriteSize) {
            this.sprite = sprite;
            this.spriteSize = spriteSize;
        }

        @Override
        //? if >=1.21.2 {
        public void onPress(InputWithModifiers input) {
            action.run();
        //?} else {
        /*public void onPress() {
            action.run();
        *///?}
        }

        @Override
        //? if >=1.21.2 {
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            final boolean handled = super.mouseClicked(event, doubleClick);
            if (handled && event.button() == 0) mouseFocused = true;
            return handled;
        }
        //?} else {
        /*public boolean mouseClicked(double mouseX, double mouseY, int button) {
            final boolean handled = super.mouseClicked(mouseX, mouseY, button);
            if (handled && button == 0) mouseFocused = true;
            return handled;
        }
        *///?}

        @Override
        public void setFocused(boolean focused) {
            super.setFocused(focused);
            if (!focused) mouseFocused = false;
        }

        private boolean hasVisibleKeyboardFocus() {
            return isFocused() && !mouseFocused;
        }

        @Override
        //? if >=26.1 {
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        //?} elif >=1.21.2 {
        /*protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        *///?} else {
        /*protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        *///?}
            final int keyColor = !active ? 0xFF8B8B8B
                    : isHovered() || hasVisibleKeyboardFocus() ? 0xFFF0E9DC : 0xFFC6C6C6;
            graphics.fill(getX() - 1, getY() - 1, getX() + width + 1, getY() + height + 2,
                    hasVisibleKeyboardFocus() ? 0xFFFFD58A : CASE_EDGE);
            graphics.fill(getX(), getY(), getX() + width, getY() + height, keyColor);
            graphics.fill(getX(), getY() + height - 2, getX() + width, getY() + height, 0xFF747474);
            // Center the mark on the lit key face; the bottom two pixels are its shadow.
            final int faceHeight = height - 2;
            if (SPRITE_PLAY.equals(sprite) || SPRITE_PAUSE.equals(sprite)) {
                final int markY = getY() + (faceHeight - 12) / 2;
                final boolean paused = SPRITE_PAUSE.equals(sprite);
                // Match the neighboring transport sprites: light from above-left, dark lower edge.
                for (int row = 0; row < 12; row++) {
                    for (int column = 0; column < 11; column++) {
                        if (!markPixel(paused, column, row)) continue;
                        final boolean lowerEdge = !markPixel(paused, column, row + 1);
                        final boolean litEdge = !markPixel(paused, column, row - 1)
                                || !markPixel(paused, column - 1, row);
                        final int color = active
                                ? (lowerEdge ? 0xFF907327 : litEdge ? 0xFFE8C86E : 0xFFCEA844)
                                : (lowerEdge ? 0xFF505050 : litEdge ? 0xFF929292 : 0xFF676767);
                        final int pixelX = getX() + (paused ? 5 : 6) + column;
                        graphics.fill(pixelX, markY + row, pixelX + 1, markY + row + 1, color);
                    }
                }
                return;
            }
            final int iconX = getX() + (width - spriteSize) / 2;
            final int iconY = getY() + (faceHeight - spriteSize) / 2;
            //? if >=1.21.2 {
            final Identifier texture = Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, SPRITE_ROOT + sprite);
            graphics.blit(RenderPipelines.GUI_TEXTURED, texture, iconX, iconY,
                    0.0F, 0.0F, spriteSize, spriteSize, spriteSize, spriteSize);
            //?} elif >=1.21 {
            /*final ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, SPRITE_ROOT + sprite);
            graphics.blit(texture, iconX, iconY, 0.0F, 0.0F, spriteSize, spriteSize, spriteSize, spriteSize);
            *///?} else {
            /*final ResourceLocation texture = new ResourceLocation(MusicDiscMaker.MODID, SPRITE_ROOT + sprite);
            graphics.blit(texture, iconX, iconY, 0.0F, 0.0F, spriteSize, spriteSize, spriteSize, spriteSize);
            *///?}
        }

        private static boolean markPixel(boolean paused, int column, int row) {
            if (row < 0 || row >= 12 || column < 0) return false;
            return paused ? column < 3 || (column >= 7 && column < 10)
                    : column < 1 + Math.min(row, 11 - row) * 2;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** 音量は server state を正本とし、整数値が変わった時だけ C2S を送る。 */
    private final class VolumeSlider extends AbstractSliderButton {

        private int current;
        private boolean editing;

        VolumeSlider(int x, int y, int initial) {
            super(x, y, BoomboxScreenLayout.VOLUME_WIDTH, BoomboxScreenLayout.SLIDER_HEIGHT,
                    CommonComponents.EMPTY, Mth.clamp(initial, 0, 100) / 100.0D);
            this.current = Mth.clamp(initial, 0, 100);
            updateMessage();
        }

        void setFromServer(int volume) {
            final int clamped = Mth.clamp(volume, 0, 100);
            if (current != clamped) {
                current = clamped;
                value = clamped / 100.0D;
                updateMessage();
            }
        }

        boolean isEditing() {
            return editing;
        }

        private int computed() {
            return Mth.clamp((int) Math.round(value * 100.0D), 0, 100);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("gui.music_disc_maker.golden_jukebox.volume", computed()));
        }

        @Override
        protected void applyValue() {
            final int volume = computed();
            if (volume != current) {
                current = volume;
                sendControl(ControlBoomboxPayload.VOLUME, volume);
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
        /*public boolean mouseClicked(double mouseX, double mouseY, int button) {
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
        *///?}

        @Override
        //? if >=26.1 {
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        //?} else {
        /*public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        *///?}
            final int x = getX();
            final int y = getY();
            final int width = this.width;
            final int height = this.height;
            graphics.fill(x, y, x + width, y + height, 0xFF161616);
            graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF2C2C2C);
            final int fill = (int) Math.round(value * (width - 2));
            if (fill > 0) graphics.fill(x + 1, y + 1, x + 1 + fill, y + height - 1, 0xFF464646);
            graphics.fill(x + 1, y + 1, x + width - 1, y + 2, 0xFF383838);
            graphics.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, 0xFF121212);
            final int knobX = x + (int) Math.round(value * (width - 4));
            graphics.fill(knobX, y, knobX + 4, y + height, 0xFF121212);
            graphics.fill(knobX + 1, y + 1, knobX + 3, y + height - 1,
                    isHoveredOrFocused() ? 0xFFA6A6A6 : 0xFF848484);
            final var minecraftFont = net.minecraft.client.Minecraft.getInstance().font;
            //? if >=26.1 {
            graphics.text(minecraftFont, getMessage(), x + (width - minecraftFont.width(getMessage())) / 2,
            //?} else {
            /*graphics.drawString(minecraftFont, getMessage(), x + (width - minecraftFont.width(getMessage())) / 2,
            *///?}
                    y + (height - 8) / 2, 0xFFD8D8D8, true);
        }

        @Override
        //? if >=1.21.2 {
        public boolean keyPressed(KeyEvent event) {
            final int direction = event.isRight() ? 1 : event.isLeft() ? -1 : 0;
        //?} else {
        /*public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            final int direction = keyCode == GLFW.GLFW_KEY_RIGHT ? 1 : keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 0;
        *///?}
            if (direction != 0) {
                final int volume = Mth.clamp(current + direction, 0, 100);
                if (volume != current) {
                    value = volume / 100.0D;
                    applyValue();
                    updateMessage();
                }
                return true;
            }
            //? if >=1.21.2 {
            return super.keyPressed(event);
            //?} else {
            /*return super.keyPressed(keyCode, scanCode, modifiers);
            *///?}
        }
    }

    /** 有限尺の媒体だけで動く seek bar。LIVE は満たした表示だけにして入力を受けない。 */
    private final class SeekBar extends AbstractWidget {

        private boolean scrubbing;
        private double scrubFraction;

        SeekBar(int x, int y, int width, int height) {
            super(x, y, width, height, CommonComponents.EMPTY);
        }

        private void updateBounds() {
            final CustomTrackData track = currentTrack();
            final String elapsed = formatMs(displayElapsedMs());
            final int rightWidth = track != null && track.radio()
                    ? font.width(Component.translatable("tooltip.music_disc_maker.live"))
                    : track == null ? 0 : font.width(formatMs(track.durationMs()));
            final GoldenJukeboxTransportLayout.SeekBounds bounds =
                    GoldenJukeboxTransportLayout.seekBounds(font.width(elapsed), rightWidth);
            setX(leftPos + bounds.x());
            width = bounds.width();
        }

        private double displayFraction() {
            final CustomTrackData track = currentTrack();
            return track == null || track.durationMs() <= 0L ? 0.0D
                    : Mth.clamp(displayElapsedMs() / (double) track.durationMs(), 0.0D, 1.0D);
        }

        private void setFromMouse(double mouseX) {
            if (width > 0) scrubFraction = Mth.clamp((mouseX - getX()) / (double) width, 0.0D, 1.0D);
        }

        @Override
        //? if >=26.1 {
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        //?} else {
        /*protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        *///?}
            updateBounds();
            if (!hasMedia() || width <= 0) return;
            final int x = getX();
            final int y = getY() + (height - 6) / 2;
            final boolean live = isLive();
            final int filled = (int) Math.round((live ? 1.0D : (scrubbing ? scrubFraction : displayFraction())) * width);
            graphics.fill(x - 1, y - 1, x + width + 1, y + 7, 0xFF3A3A3A);
            graphics.fill(x, y, x + width, y + 6, 0xFF555555);
            if (filled > 0) graphics.fill(x, y, x + filled, y + 6, live ? 0xFFE5C99C : 0xFFB88B49);
            if (isSeekable()) {
                final int knob = x + filled;
                graphics.fill(knob - 2, y - 3, knob + 3, y + 9, 0xFF2A2A2A);
                graphics.fill(knob - 1, y - 2, knob + 2, y + 8, 0xFFFFD58A);
            }
        }

        @Override
        //? if >=1.21.2 {
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 0 && active && visible && isMouseOver(event.x(), event.y()) && isSeekable()) {
                scrubbing = true;
                setFromMouse(event.x());
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
            if (!scrubbing) return false;
            setFromMouse(event.x());
            return true;
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            if (scrubbing && event.button() == 0) {
                scrubbing = false;
                final CustomTrackData track = currentTrack();
                if (track != null) sendControl(ControlBoomboxPayload.SEEK, Math.round(scrubFraction * track.durationMs()));
                return true;
            }
            return false;
        }
        //?} else {
        /*public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && active && visible && isMouseOver(mouseX, mouseY) && isSeekable()) {
                scrubbing = true;
                setFromMouse(mouseX);
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            if (!scrubbing) return false;
            setFromMouse(mouseX);
            return true;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (scrubbing && button == 0) {
                scrubbing = false;
                final CustomTrackData track = currentTrack();
                if (track != null) sendControl(ControlBoomboxPayload.SEEK, Math.round(scrubFraction * track.durationMs()));
                return true;
            }
            return false;
        }
        *///?}

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }
}
