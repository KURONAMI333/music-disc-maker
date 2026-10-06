package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.GoldenCartState;
import com.kuronami.musicdiscmaker.component.GoldenDeck;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.item.AlbumItem;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
//?} else {
//?}
import com.kuronami.musicdiscmaker.menu.GoldenJukeboxCartMenu;
import com.kuronami.musicdiscmaker.network.ControlGoldenCartPayload;
import com.kuronami.musicdiscmaker.platform.Services;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
//? if >=1.21.11 {
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.input.MouseButtonEvent;
//?} else {
//?}
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 金ジュークボックス付きトロッコの GUI。ブームボックスと同じ運輸 UI (再生/一時停止・
 * 前後・ループ・シャッフル・シーク) に、金ジュークの範囲/音量/指向コントロールを足す。
 * 表示はサーバーの {@link GoldenCartState} スナップショットを唯一の正とし、コントロールは
 * 全て {@link ControlGoldenCartPayload} (entityId+containerId+generation 照合) で送る。
 */
public class GoldenJukeboxCartScreen extends AbstractContainerScreen<GoldenJukeboxCartMenu> {

    //? if >=1.21.11 {
    private static final net.minecraft.resources.Identifier TEXTURE =
    //?} else {
    /*private static final net.minecraft.resources.ResourceLocation TEXTURE =
    *///?}
            //? if >=1.21.11 {
            net.minecraft.resources.Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/gui/golden_jukebox.png");
            //?} elif >=1.21 {
            /*net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/gui/golden_jukebox.png");
            *///?} else {
            /*new net.minecraft.resources.ResourceLocation(MusicDiscMaker.MODID, "textures/gui/golden_jukebox.png");
            *///?}

    private static final String SPRITE_ROOT = "textures/gui/golden_jukebox/transport/";
    private static final String BOOMBOX_SPRITE_ROOT = "textures/gui/boombox/transport/";
    private static final String CONTROLS_TEXTURE = "textures/gui/golden_jukebox/controls.png";

    private static final int TITLE_Y = 19;      // 曲名
    private static final int AUTHOR_Y = 31;     // 作者名
    private static final int TRACK_TEXT_X = 32; // 媒体スロットの右から曲情報を描く。
    private static final int TRACK_TEXT_WIDTH = 136;
    // 確定 B2 の transport 座標を共有する。ラベルをバーの上に描く既存 slider は
    // それぞれ独立した行へ置き、ボタン・時刻・inventory label と重ねない。
    private static final int VOLUME_Y = 94;
    private static final int RANGE_Y = 122;

    private IconButton playPauseButton;
    private IconButton previousButton;
    private IconButton nextButton;
    private IconButton repeatButton;
    private IconButton shuffleButton;
    private IconButton directionalButton;
    private VolumeSlider volumeSlider;
    private VolumeSlider rangeSlider;
    private SeekBar seekBar;

    private final CartPlaybackDisplayClock displayClock = new CartPlaybackDisplayClock();
    @Nullable
    private GoldenCartState lastRenderedState;
    private boolean lastTrackMissing;

    public GoldenJukeboxCartScreen(GoldenJukeboxCartMenu menu, Inventory inventory, Component title) {
        //? if >=26.1 {
        super(menu, inventory, title, 176, 242);
        //?} else {
        /*super(menu, inventory, title);
        this.imageHeight = 242;
        *///?}
        this.inventoryLabelY = this.imageHeight - 94; // 148
    }

    @Override
    protected void init() {
        super.init();
        //? if <1.21.2 {
        /*clearWidgets();
        *///?}
        // 既存 transport sprite と controls atlas を使い、確定 B2 の操作列へ揃える。
        playPauseButton = addRenderableWidget(new IconButton(
                leftPos + GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.PLAY_PAUSE),
                topPos + GoldenJukeboxTransportLayout.BUTTON_Y,
                20, 20, Component.translatable("gui.music_disc_maker.golden_jukebox.play"),
                button -> sendControl(stateOrDefault().cursor().state() == PlaybackCursor.State.PLAYING
                        ? ControlGoldenCartPayload.PAUSE : ControlGoldenCartPayload.PLAY, 0)));
        playPauseButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("gui.music_disc_maker.golden_jukebox.play")));

        previousButton = addRenderableWidget(new IconButton(
                leftPos + GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.PREVIOUS),
                topPos + GoldenJukeboxTransportLayout.BUTTON_Y,
                20, 20, Component.translatable("gui.music_disc_maker.golden_jukebox.previous"),
                button -> sendControl(ControlGoldenCartPayload.PREVIOUS, 0)));
        nextButton = addRenderableWidget(new IconButton(
                leftPos + GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.NEXT),
                topPos + GoldenJukeboxTransportLayout.BUTTON_Y,
                20, 20, Component.translatable("gui.music_disc_maker.golden_jukebox.next"),
                button -> sendControl(ControlGoldenCartPayload.NEXT, 0)));
        repeatButton = addRenderableWidget(new IconButton(
                leftPos + GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.REPEAT),
                topPos + GoldenJukeboxTransportLayout.BUTTON_Y,
                20, 20, Component.translatable("gui.music_disc_maker.golden_jukebox.repeat_toggle"),
                button -> sendControl(ControlGoldenCartPayload.REPEAT, stateOrDefault().repeat() ? 0 : 1)));
        shuffleButton = addRenderableWidget(new IconButton(
                leftPos + GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.SHUFFLE),
                topPos + GoldenJukeboxTransportLayout.BUTTON_Y,
                20, 20, Component.translatable("gui.music_disc_maker.golden_jukebox.shuffle_toggle"),
                button -> sendControl(ControlGoldenCartPayload.SHUFFLE, stateOrDefault().shuffle() ? 0 : 1)));
        directionalButton = addRenderableWidget(new IconButton(
                leftPos + 152, topPos + RANGE_Y - 1,
                16, 16, Component.translatable("gui.music_disc_maker.golden_jukebox.directional"),
                button -> sendControl(ControlGoldenCartPayload.DIRECTIONAL, stateOrDefault().directional() ? 0 : 1)));
        directionalButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("gui.music_disc_maker.golden_jukebox.directional")));

        final GoldenCartState initial = stateOrDefault();
        volumeSlider = addRenderableWidget(new VolumeSlider(
                leftPos + 8, topPos + VOLUME_Y, 160,
                "gui.music_disc_maker.golden_jukebox.volume",
                GoldenDeck.VOLUME_MIN, GoldenDeck.VOLUME_MAX, initial.volumePercent()) {
                    @Override void commit() { sendControl(ControlGoldenCartPayload.VOLUME, absolute()); }
                });
        rangeSlider = addRenderableWidget(new VolumeSlider(
                leftPos + 8, topPos + RANGE_Y, 140,
                "gui.music_disc_maker.golden_jukebox.range",
                GoldenDeck.RANGE_MIN, GoldenDeck.RANGE_MAX, initial.rangeBlocks()) {
                    @Override void commit() { sendControl(ControlGoldenCartPayload.RANGE, absolute()); }
                });
        seekBar = addRenderableWidget(new SeekBar(
                leftPos + GoldenJukeboxTransportLayout.TIME_X,
                topPos + GoldenJukeboxTransportLayout.SEEK_Y, 0,
                GoldenJukeboxTransportLayout.SEEK_HEIGHT));

        refreshWidgets();
        lastRenderedState = menu.playbackState();
        lastTrackMissing = !hasMedia();
        observePlaybackSnapshot();
    }

    /** client は初回 snapshot 到着まで状態を持たない。未同期時は STOPPED 扱いで widget を組む。 */
    private GoldenCartState stateOrDefault() {
        final GoldenCartState state = menu.playbackState();
        return state != null ? state
                : new GoldenCartState(PlaybackCursor.initial(), 0L, false, false,
                        GoldenDeck.VOLUME_DEFAULT, GoldenDeck.RANGE_DEFAULT, false);
    }

    private void sendControl(int action, long value) {
        if (!menu.hasPlaybackState()) return;
        Services.NETWORK.sendToServer(new ControlGoldenCartPayload(
                menu.cartEntityId(), menu.containerId,
                menu.playbackState().cursor().generation(), action, value));
    }

    private boolean hasMedia() {
        return !menu.medium().isEmpty();
    }

    private boolean isPlaying() {
        return stateOrDefault().cursor().state() == PlaybackCursor.State.PLAYING;
    }

    private boolean isLive() {
        final CustomTrackData track = menu.currentTrack();
        return track != null && track.radio();
    }

    private boolean isSeekable() {
        final CustomTrackData track = menu.currentTrack();
        return track != null && track.durationMs() > 0L && !track.radio();
    }

    private void observePlaybackSnapshot() {
        final GoldenCartState state = stateOrDefault();
        displayClock.observe(state.elapsedMs(), state.cursor().state(),
                state.cursor().generation(), System.currentTimeMillis());
    }

    // 経過ms: snapshot の elapsedMs が基準で、PLAYING は受信時点からの経過を足す。
    // server 時計ではなく menu 開設からの経過で補正する (client/server 時計差を避ける)。
    private long displayElapsedMs() {
        return displayClock.displayElapsedMs(System.currentTimeMillis());
    }

    private String currentTrackTitle() {
        final ItemStack stack = menu.medium();
        if (stack.isEmpty()) return "";
        if (stack.getItem() instanceof AlbumItem) {
            final CustomTrackData track = menu.currentTrack();
            if (track != null && track.title() != null && !track.title().isBlank()) {
                return track.title();
            }
        }
        return stack.getHoverName().getString();
    }

    private String currentTrackAuthor() {
        final ItemStack stack = menu.medium();
        if (stack.isEmpty()) return "";
        final CustomTrackData track = menu.currentTrack();
        if (track != null && track.author() != null && !track.author().isBlank()) {
            return track.author();
        }
        //? if >=1.21 {
        return CustomMusicDiscItem.getTrack(stack).author();
        //?} else {
        /*return "";
        *///?}
    }

    private void refreshWidgets() {
        final GoldenCartState state = stateOrDefault();
        final boolean synchronizedState = menu.hasPlaybackState();
        final boolean media = hasMedia();
        final boolean playing = state.cursor().state() == PlaybackCursor.State.PLAYING;
        final int navigable = menu.navigableTrackCount();
        // Until the first server snapshot arrives, stateOrDefault() is only a visual fallback;
        // do not present controls as usable when sendControl() would have to drop their action.
        playPauseButton.active = synchronizedState && media;
        playPauseButton.icon = BOOMBOX_SPRITE_ROOT + (playing ? "pause" : "play");
        playPauseButton.setMessage(Component.translatable(playing
                ? "gui.music_disc_maker.golden_jukebox.pause"
                : "gui.music_disc_maker.golden_jukebox.play"));
        playPauseButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable(playing
                        ? "gui.music_disc_maker.golden_jukebox.pause"
                        : "gui.music_disc_maker.golden_jukebox.play")));
        previousButton.active = synchronizedState && media && navigable > 1;
        previousButton.icon = SPRITE_ROOT + (navigable > 1 ? "prev" : "prev_off");
        nextButton.active = synchronizedState && media && navigable > 1;
        nextButton.icon = SPRITE_ROOT + (navigable > 1 ? "next" : "next_off");
        repeatButton.active = synchronizedState && media;
        repeatButton.icon = SPRITE_ROOT + (state.repeat() ? "repeat_on" : "repeat_off");
        shuffleButton.active = synchronizedState && media && navigable > 1;
        shuffleButton.icon = SPRITE_ROOT + (state.shuffle() ? "shuffle_on" : "shuffle_off");
        directionalButton.active = synchronizedState && media;
        directionalButton.setAtlasIcon(state.directional() ? 176 : 192, 24);
        directionalButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable(state.directional()
                        ? "gui.music_disc_maker.golden_jukebox.directional.on"
                        : "gui.music_disc_maker.golden_jukebox.directional.off")));
        volumeSlider.setServerValue(state.volumePercent());
        rangeSlider.setServerValue(state.rangeBlocks());
        volumeSlider.active = synchronizedState;
        rangeSlider.active = synchronizedState;
        seekBar.active = synchronizedState && media && isSeekable();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        observePlaybackSnapshot();
        final GoldenCartState synchronizedState = menu.playbackState();
        final boolean playbackChanged = !java.util.Objects.equals(lastRenderedState, synchronizedState);
        final boolean trackMissing = !hasMedia();
        final long now = System.currentTimeMillis();
        if (playbackChanged || trackMissing != lastTrackMissing
                || volumeSlider.pendingValueExpired(now) || rangeSlider.pendingValueExpired(now)) {
            lastRenderedState = synchronizedState;
            lastTrackMissing = trackMissing;
            refreshWidgets();
        }
    }

    //? if >=1.21.11 {
    //?} else {
    /*
    // Fabric 1.21.1 の AbstractContainerScreen は子 slider へ release/drag を委譲しない。
    // focus 中の widget へ転送し、container の quick-craft 処理も従来どおり super に渡す。
    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        final net.minecraft.client.gui.components.events.GuiEventListener focused = getFocused();
        if (button == 0 && isDragging() && focused != null
                && focused.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        final net.minecraft.client.gui.components.events.GuiEventListener focused = getFocused();
        final boolean widgetHandled = button == 0 && isDragging() && focused != null
                && focused.mouseReleased(mouseX, mouseY, button);
        setDragging(false);
        final boolean containerHandled = super.mouseReleased(mouseX, mouseY, button);
        return widgetHandled || containerHandled;
    }
    *///?}

    @Override
    //? if >=26.1 {
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, leftPos, topPos,
                0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
    //?} elif >=1.21.11 {
    /*protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, leftPos, topPos,
                0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
    *///?} else {
    /*protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        GuiTextureBlit.blit(graphics, TEXTURE, leftPos, topPos, 0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
    *///?}
    }

    @Override
    //? if >=26.1 {
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    //?} else {
    /*public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    *///?}
        if (ClientPlaybackHandler.isProtocolMismatch()) {
            drawCenteredText(graphics, Component.translatable("music_disc_maker.protocol_mismatch"),
                    leftPos + this.imageWidth / 2, topPos + 6, 0xFF5555);
        }

        renderTrackInfo(graphics);
        renderProgress(graphics);
    }

    private void renderTrackInfo(
            //? if >=26.1 {
            GuiGraphicsExtractor graphics
            //?} else {
            /*GuiGraphics graphics
            *///?}
    ) {
        if (!hasMedia()) return;
        final String title = currentTrackTitle();
        final String author = currentTrackAuthor();
        if (!title.isEmpty()) {
            drawText(graphics, trimToWidth(title, TRACK_TEXT_WIDTH), leftPos + TRACK_TEXT_X, topPos + TITLE_Y, 0xFFE5C99C);
        }
        if (!author.isEmpty()) {
            drawText(graphics, trimToWidth(author, TRACK_TEXT_WIDTH), leftPos + TRACK_TEXT_X, topPos + AUTHOR_Y, 0xFF9B8F7A);
        }
    }

    private String trimToWidth(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, maxWidth - font.width("...")) + "...";
    }

    private void renderProgress(
            //? if >=26.1 {
            GuiGraphicsExtractor graphics
            //?} else {
            /*GuiGraphics graphics
            *///?}
    ) {
        if (!hasMedia()) return;
        final String elapsed = formatMs(displayElapsedMs());
        final CustomTrackData track = menu.currentTrack();
        drawText(graphics, elapsed, leftPos + GoldenJukeboxTransportLayout.TIME_X,
                topPos + GoldenJukeboxTransportLayout.TIME_Y, 0xFFA0A0A0);
        if (track != null && track.radio()) {
            final Component live = Component.translatable("tooltip.music_disc_maker.live");
            drawText(graphics, live,
                    leftPos + GoldenJukeboxTransportLayout.TIME_RIGHT - font.width(live),
                    topPos + GoldenJukeboxTransportLayout.TIME_Y, 0xFFE5C99C);
        } else if (track != null) {
            final String total = formatMs(track.durationMs());
            drawText(graphics, total,
                    leftPos + GoldenJukeboxTransportLayout.TIME_RIGHT - font.width(total),
                    topPos + GoldenJukeboxTransportLayout.TIME_Y, 0xFFA0A0A0);
        }
    }

    private void drawText(
            //? if >=26.1 {
            GuiGraphicsExtractor graphics,
            //?} else {
            /*GuiGraphics graphics,
            *///?}
            String text, int x, int y, int color) {
        //? if >=26.1 {
        graphics.text(font, text, x, y, color, false);
        //?} else {
        /*graphics.drawString(font, text, x, y, color, false);
        *///?}
    }

    private void drawText(
            //? if >=26.1 {
            GuiGraphicsExtractor graphics,
            //?} else {
            /*GuiGraphics graphics,
            *///?}
            Component text, int x, int y, int color) {
        //? if >=26.1 {
        graphics.text(font, text, x, y, color, false);
        //?} else {
        /*graphics.drawString(font, text, x, y, color, false);
        *///?}
    }

    private void drawCenteredText(
            //? if >=26.1 {
            GuiGraphicsExtractor graphics,
            //?} else {
            /*GuiGraphics graphics,
            *///?}
            Component text, int x, int y, int color) {
        //? if >=26.1 {
        graphics.centeredText(font, text, x, y, color);
        //?} else {
        /*graphics.drawCenteredString(font, text, x, y, color);
        *///?}
    }

    private static String formatMs(long ms) {
        final long totalSeconds = Math.max(0L, ms / 1000L);
        return String.format("%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    // ------ widgets ------

    private static final class IconButton extends Button {
        private String icon;
        private int atlasU = -1;
        private int atlasV = -1;

        IconButton(int x, int y, int width, int height, Component message, Button.OnPress onPress) {
            super(x, y, width, height, message, onPress, Button.DEFAULT_NARRATION);
        }

        void setAtlasIcon(int u, int v) {
            icon = null;
            atlasU = u;
            atlasV = v;
        }

        @Override
        //? if >=26.1 {
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        //?} elif >=1.21.11 {
        /*protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        *///?} else {
        /*public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        *///?}
            final int x = getX();
            final int y = getY();
            graphics.fill(x, y, x + width, y + height, isActive() ? 0xFF232323 : 0xFF1A1A1A);
            graphics.fill(x, y, x + width, y + 1, 0xFF0F0F0F);
            graphics.fill(x, y + height - 1, x + width, y + height, 0xFF0F0F0F);
            graphics.fill(x, y, x + 1, y + height, 0xFF0F0F0F);
            graphics.fill(x + width - 1, y, x + width, y + height, 0xFF0F0F0F);
            final int ix = x + (width - 16) / 2;
            final int iy = y + (height - 16) / 2;
            if (atlasU >= 0) {
                //? if >=1.21.11 {
                graphics.blit(RenderPipelines.GUI_TEXTURED,
                        net.minecraft.resources.Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, CONTROLS_TEXTURE),
                        ix, iy, (float) atlasU, (float) atlasV, 16, 16, 256, 64);
                //?} elif >=1.21 {
                /*GuiTextureBlit.blit(graphics, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, CONTROLS_TEXTURE),
                        ix, iy, (float) atlasU, (float) atlasV, 16, 16, 256, 64);
                *///?} else {
                /*GuiTextureBlit.blit(graphics, new net.minecraft.resources.ResourceLocation(MusicDiscMaker.MODID, CONTROLS_TEXTURE),
                        ix, iy, (float) atlasU, (float) atlasV, 16, 16, 256, 64);
                *///?}
            } else if (icon != null) {
                //? if >=1.21.11 {
                graphics.blit(RenderPipelines.GUI_TEXTURED,
                        net.minecraft.resources.Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, icon + ".png"),
                        ix, iy, 0.0F, 0.0F, 16, 16, 16, 16);
                //?} elif >=1.21 {
                /*GuiTextureBlit.blit(graphics, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, icon + ".png"),
                        ix, iy, 0.0F, 0.0F, 16, 16, 16, 16);
                *///?} else {
                /*GuiTextureBlit.blit(graphics, new net.minecraft.resources.ResourceLocation(MusicDiscMaker.MODID, icon + ".png"),
                        ix, iy, 0.0F, 0.0F, 16, 16, 16, 16);
                *///?}
            }
            if (isActive() && isMouseOver(mouseX, mouseY)) {
                graphics.fill(x, y, x + width, y + height, 0x40FFFFFF);
            }
        }
    }

    private abstract class VolumeSlider extends AbstractSliderButton {
        private final int min;
        private final int max;
        private final String translationKey;
        private boolean dragging;
        private int pendingServerValue = -1;
        private long pendingServerValueUntilMs;

        VolumeSlider(int x, int y, int width, String translationKey, int min, int max, int value) {
            super(x, y, width, 16, CommonComponents.EMPTY, 0.0D);
            this.min = min;
            this.max = max;
            this.translationKey = translationKey;
            setValue(value);
        }

        void setValue(int absolute) {
            this.value = Mth.clamp((absolute - min) / (double) (max - min), 0.0D, 1.0D);
            updateMessage();
        }

        int absolute() {
            return min + (int) Math.round(value * (max - min));
        }

        void setServerValue(int absolute) {
            if (dragging) return;
            if (pendingServerValue >= 0) {
                if (absolute == pendingServerValue) {
                    pendingServerValue = -1;
                } else if (System.currentTimeMillis() < pendingServerValueUntilMs) {
                    return;
                } else {
                    pendingServerValue = -1;
                }
            }
            setValue(absolute);
        }

        boolean pendingValueExpired(long nowMillis) {
            return !dragging && pendingServerValue >= 0 && nowMillis >= pendingServerValueUntilMs;
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(translationKey, absolute()));
        }

        @Override
        protected void applyValue() {
            pendingServerValue = absolute();
            pendingServerValueUntilMs = System.currentTimeMillis() + 1_500L;
            commit();
        }

        abstract void commit();

        @Override
        // applyValue() が値の変更時だけ呼ばれるため、click 前に pending を消さない。
        // 同じ値の再クリックは既存の応答待ちを保ち、新しい値なら applyValue() が上書きする。
        //? if >=1.21.11 {
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            final boolean handled = super.mouseClicked(event, doubleClick);
            if (handled) {
                dragging = true;
            }
            return handled;
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            final boolean handled = super.mouseReleased(event);
            if (event.button() == 0) dragging = false;
            return handled;
        }
        //?} else {
        /*public boolean mouseClicked(double mouseX, double mouseY, int button) {
            final boolean handled = super.mouseClicked(mouseX, mouseY, button);
            if (handled) {
                dragging = true;
            }
            return handled;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            final boolean handled = super.mouseReleased(mouseX, mouseY, button);
            if (button == 0) dragging = false;
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
            graphics.fill(x, y, x + width, y + height, 0xFF232323);
            final int fill = (int) Math.round(value * width);
            if (fill > 0) graphics.fill(x, y, x + fill, y + height, 0xFF7A5A2A);
            final int knob = x + (int) Math.round(value * (width - 4));
            graphics.fill(knob, y, knob + 4, y + height, 0xFFFFD58A);
            drawText(graphics, getMessage(), x, y - font.lineHeight - 1, 0xFFA0A0A0);
        }
    }

    private final class SeekBar extends AbstractWidget {
        private boolean scrubbing;
        private double scrubFraction;

        SeekBar(int x, int y, int width, int height) {
            super(x, y, width, height, CommonComponents.EMPTY);
        }

        private void updateBounds() {
            final String elapsed = formatMs(displayElapsedMs());
            final CustomTrackData track = menu.currentTrack();
            final int rightWidth = track != null && track.radio()
                    ? font.width(Component.translatable("tooltip.music_disc_maker.live"))
                    : track == null ? 0 : font.width(formatMs(track.durationMs()));
            final GoldenJukeboxTransportLayout.SeekBounds bounds =
                    GoldenJukeboxTransportLayout.seekBounds(font.width(elapsed), rightWidth);
            setX(leftPos + bounds.x());
            width = bounds.width();
        }

        private double displayFraction() {
            final CustomTrackData track = menu.currentTrack();
            return track == null || track.durationMs() <= 0L ? 0.0D
                    : Mth.clamp(displayElapsedMs() / (double) track.durationMs(), 0.0D, 1.0D);
        }

        private void setFromMouse(double mouseX) {
            if (width > 0) scrubFraction = Mth.clamp((mouseX - getX()) / (double) width, 0.0D, 1.0D);
        }

        @Override
        //? if >=26.1 {
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        //?} else {
        /*public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
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
        //? if >=1.21.11 {
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
                final CustomTrackData track = menu.currentTrack();
                if (track != null) sendControl(ControlGoldenCartPayload.SEEK,
                        Math.round(scrubFraction * track.durationMs()));
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
                final CustomTrackData track = menu.currentTrack();
                if (track != null) sendControl(ControlGoldenCartPayload.SEEK,
                        Math.round(scrubFraction * track.durationMs()));
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
