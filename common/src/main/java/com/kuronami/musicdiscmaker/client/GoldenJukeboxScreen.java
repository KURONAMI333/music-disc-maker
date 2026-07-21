package com.kuronami.musicdiscmaker.client;

import java.util.function.IntConsumer;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.menu.GoldenJukeboxMenu;
import com.kuronami.musicdiscmaker.network.ConfigureJukeboxPayload;
import com.kuronami.musicdiscmaker.network.SeekJukeboxPayload;
import com.kuronami.musicdiscmaker.platform.Services;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

/**
 * 強化版ジュークボックスの設定 GUI。可聴範囲・音量スライダー（バニラ式ドラッグ）と、音楽プレイヤー風の
 * 操作列（再生/一時停止トグル・リピートトグル・シークバー＋経過/総時間）を持つ。
 *
 * <p>設定変更は {@link ConfigureJukeboxPayload}、シーク頭出しは {@link SeekJukeboxPayload} で
 * server の BE へ反映する。進捗表示は BE が同期する playbackStartGameTime から算出する
 * （{@link GoldenJukeboxBlockEntity#currentElapsedMs()}）。
 */
public class GoldenJukeboxScreen extends AbstractContainerScreen<GoldenJukeboxMenu> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/gui/golden_jukebox.png");
    private static final int TEXT = 0x404040;
    private static final int TIME_TEXT = 0x606060;

    // transport スプライトの uv (TEXTURE 内)。play/pause は 20x20 の丸ボタン、loop は 16x16 のフラット glyph。
    private static final int ICON_PLAY_U = 176;
    private static final int ICON_PAUSE_U = 196;
    private static final int ICON_LOOP_ON_U = 216;
    private static final int ICON_LOOP_OFF_U = 232;
    private static final int ICON_V = 0;
    private static final int PLAY_SPRITE = 20;
    private static final int LOOP_SPRITE = 16;

    private static final int ACCENT = 0xFFCEA844;   // Golden Jukebox のアクセント (fill/knob)
    private static final int LIVE_FILL = 0xFF9AA0A6; // ラジオ (LIVE) の不定進捗

    // シークバー幾何 (leftPos/topPos 相対)。
    private static final int SEEK_X = 32;
    private static final int SEEK_Y = 90;
    private static final int SEEK_W = 112;
    private static final int SEEK_H = 16;
    private static final int TIME_Y = 108;

    // 現在値 (BE から init で初期化、widget 操作で更新)。
    private int curRange = GoldenJukeboxBlockEntity.RANGE_DEFAULT;
    private int curVolume = GoldenJukeboxBlockEntity.VOLUME_DEFAULT;
    private boolean curRepeat;
    private boolean curPaused;

    private IconButton repeatButton;
    private IconButton playPauseButton;

    public GoldenJukeboxScreen(GoldenJukeboxMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 216;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 120;
    }

    @Override
    protected void init() {
        super.init();
        final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
        this.curRange = be.getRangeBlocks();
        this.curVolume = be.getVolumePercent();
        this.curRepeat = be.isRepeat();
        this.curPaused = be.isPaused();

        addRenderableWidget(new SettingSlider(leftPos + 8, topPos + 42, 160, 20,
                GoldenJukeboxBlockEntity.RANGE_MIN, GoldenJukeboxBlockEntity.RANGE_MAX, curRange,
                "gui.music_disc_maker.golden_jukebox.range", v -> {
                    curRange = v;
                    sendConfig();
                }));
        addRenderableWidget(new SettingSlider(leftPos + 8, topPos + 64, 160, 20,
                GoldenJukeboxBlockEntity.VOLUME_MIN, GoldenJukeboxBlockEntity.VOLUME_MAX, curVolume,
                "gui.music_disc_maker.golden_jukebox.volume", v -> {
                    curVolume = v;
                    sendConfig();
                }));

        // 再生/一時停止トグル (左・丸ボタン)。自然終了後は頭出し再生でリスタートする。
        this.playPauseButton = addRenderableWidget(new IconButton(leftPos + 8, topPos + 88, 20, PLAY_SPRITE,
                Component.translatable("gui.music_disc_maker.golden_jukebox.play"), () -> {
                    if (isEnded()) {
                        Services.NETWORK.sendToServer(
                                new SeekJukeboxPayload(menu.getBlockEntity().getBlockPos(), 0L));
                    } else {
                        curPaused = !curPaused;
                        sendConfig();
                    }
                }));
        // シークバー (中央)。
        addRenderableWidget(new SeekBar(leftPos + SEEK_X, topPos + SEEK_Y, SEEK_W, SEEK_H));
        // リピートトグル (右・フラットアイコン)。
        this.repeatButton = addRenderableWidget(new IconButton(leftPos + 148, topPos + 88, 20, LOOP_SPRITE,
                Component.translatable("gui.music_disc_maker.golden_jukebox.repeat_toggle"), () -> {
                    curRepeat = !curRepeat;
                    sendConfig();
                }));

        refreshTransportSprites();
    }

    /** 有限尺の非リピート custom disc が総尺まで達した (自然終了した) か。 */
    private boolean isEnded() {
        final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
        return !curPaused && be.isSeekable() && !be.isRepeat()
                && be.currentElapsedMs() >= be.trackDurationMs();
    }

    /** BE 状態からトグルのスプライト・活性を更新する。 */
    private void refreshTransportSprites() {
        final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
        if (playPauseButton != null) {
            playPauseButton.active = be.hasDisc();
            // 一時停止中・自然終了後は「再生」アイコン、再生中は「一時停止」アイコン。
            playPauseButton.setSprite((curPaused || isEnded()) ? ICON_PLAY_U : ICON_PAUSE_U, ICON_V);
        }
        if (repeatButton != null) {
            repeatButton.active = !be.isLiveStream();
            repeatButton.setSprite(curRepeat && !be.isLiveStream() ? ICON_LOOP_ON_U : ICON_LOOP_OFF_U, ICON_V);
        }
    }

    private void sendConfig() {
        Services.NETWORK.sendToServer(new ConfigureJukeboxPayload(
                menu.getBlockEntity().getBlockPos(), curRange, curVolume, curRepeat, curPaused));
    }

    private static String formatMs(long ms) {
        final long totalSeconds = Math.max(0L, ms) / 1000L;
        return String.format("%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.blit(TEXTURE, leftPos, topPos, 0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
        // 曲名 (ディスクスロット右)。無ければ "No disc"。
        final CustomTrackData track = be.currentTrack();
        final Component label;
        if (track != null) {
            final String desc = (track.author() != null && !track.author().isBlank())
                    ? track.author() + " - " + track.title()
                    : track.title();
            label = Component.literal(font.plainSubstrByWidth(desc == null ? "" : desc, 130));
        } else if (be.hasDisc()) {
            label = Component.translatable("gui.music_disc_maker.golden_jukebox.vanilla_disc");
        } else {
            label = Component.translatable("gui.music_disc_maker.golden_jukebox.no_track");
        }
        g.drawString(font, label, 36, 23, TEXT, false);

        // 経過 / 総時間 (シークバー下)。ラジオは経過 + LIVE。
        final long elapsed = be.currentElapsedMs();
        final String elapsedStr = formatMs(elapsed);
        if (be.isLiveStream()) {
            g.drawString(font, elapsedStr, SEEK_X, TIME_Y, TIME_TEXT, false);
            final Component live = Component.translatable("tooltip.music_disc_maker.live");
            final int lw = font.width(live);
            g.drawString(font, live, SEEK_X + SEEK_W - lw, TIME_Y, 0xD03030, false);
        } else if (be.hasDisc() && be.trackDurationMs() > 0L) {
            g.drawString(font, elapsedStr, SEEK_X, TIME_Y, TIME_TEXT, false);
            final String total = formatMs(be.trackDurationMs());
            g.drawString(font, total, SEEK_X + SEEK_W - font.width(total), TIME_Y, TIME_TEXT, false);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 別プレイヤー操作等で BE 状態が変わったらトグルを追随させる。
        final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
        if (be.isPaused() != curPaused) {
            curPaused = be.isPaused();
        }
        if (be.isRepeat() != curRepeat) {
            curRepeat = be.isRepeat();
        }
        refreshTransportSprites();
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    // ── AbstractContainerScreen(1.21.1) は mouseDragged / mouseReleased を子ウィジェットへ
    //    委譲しない (quick-craft 処理で super を呼ばず握りつぶす)。そのため container 画面では
    //    スライダー・シークバーのドラッグ追従が効かない。focus 中のウィジェットへ自前で転送して
    //    バニラの非 container 設定画面と同じ操作感を復元する。isDragging() でクリック起点の
    //    ドラッグ列だけに限定し、release 後の空白ドラッグで前回のスライダーが動くのを防ぐ。

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

    /** 整数値スライダー。ドラッグ/キーで値が変わったら {@code onChange} を呼ぶ (同一整数は dedup)。 */
    private static final class SettingSlider extends AbstractSliderButton {

        private final int min;
        private final int max;
        private final String labelKey;
        private final IntConsumer onChange;
        private int current;

        SettingSlider(int x, int y, int width, int height, int min, int max, int initial,
                String labelKey, IntConsumer onChange) {
            super(x, y, width, height, CommonComponents.EMPTY, (initial - min) / (double) (max - min));
            this.min = min;
            this.max = max;
            this.labelKey = labelKey;
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
                onChange.accept(v);
            }
        }
    }

    /** アイコンのみのフラットボタン (音楽プレイヤー風)。sprite は TEXTURE 内 16x16。 */
    private static final class IconButton extends AbstractButton {

        private int u;
        private int v;
        private final int spriteSize;
        private final Runnable onPress;

        IconButton(int x, int y, int size, int spriteSize, Component narration, Runnable onPress) {
            super(x, y, size, size, narration);
            this.spriteSize = spriteSize;
            this.onPress = onPress;
        }

        void setSprite(int u, int v) {
            this.u = u;
            this.v = v;
        }

        @Override
        public void onPress() {
            onPress.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            if (this.active && isHoveredOrFocused()) {
                g.fill(getX(), getY(), getX() + width, getY() + height, 0x33FFFFFF);
            }
            final int ix = getX() + (width - spriteSize) / 2;
            final int iy = getY() + (height - spriteSize) / 2;
            g.blit(TEXTURE, ix, iy, (float) u, (float) v, spriteSize, spriteSize, 256, 256);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }
    }

    /**
     * シークバー。溝・進捗 fill・つまみをプログラム描画し、有限尺 (非ラジオ) の custom disc の時だけ
     * クリック/ドラッグで頭出しできる。ラジオは LIVE 用のグレー fill を満たして表示だけする。
     */
    private final class SeekBar extends AbstractWidget {

        private boolean scrubbing;
        private double scrubFraction;

        SeekBar(int x, int y, int width, int height) {
            super(x, y, width, height, CommonComponents.EMPTY);
        }

        private double liveFraction() {
            final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
            final long dur = be.trackDurationMs();
            if (dur <= 0L) {
                return 0.0;
            }
            return Mth.clamp(be.currentElapsedMs() / (double) dur, 0.0, 1.0);
        }

        private void setFromMouse(double mouseX) {
            scrubFraction = Mth.clamp((mouseX - getX()) / (double) width, 0.0, 1.0);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
            final int gx = getX();
            final int gw = width;
            final int gy = getY() + (height - 6) / 2;
            // 溝 (recessed)。
            g.fill(gx - 1, gy - 1, gx + gw + 1, gy + 7, 0xFF3A3A3A);
            g.fill(gx, gy, gx + gw, gy + 6, 0xFF555555);
            final boolean live = be.isLiveStream();
            final double f = live ? 1.0 : (scrubbing ? scrubFraction : liveFraction());
            final int fillW = (int) Math.round(f * gw);
            if (fillW > 0) {
                g.fill(gx, gy, gx + fillW, gy + 6, live ? LIVE_FILL : ACCENT);
            }
            // つまみ (頭出し可能な時のみ)。
            if (be.isSeekable()) {
                final int kx = gx + fillW;
                g.fill(kx - 2, gy - 3, kx + 3, gy + 9, 0xFF2A2A2A);
                g.fill(kx - 1, gy - 2, kx + 2, gy + 8, ACCENT);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && this.active && this.visible && isMouseOver(mouseX, mouseY)
                    && menu.getBlockEntity().isSeekable()) {
                scrubbing = true;
                setFromMouse(mouseX);
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            if (scrubbing) {
                setFromMouse(mouseX);
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (scrubbing && button == 0) {
                scrubbing = false;
                final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
                final long offset = Math.round(scrubFraction * be.trackDurationMs());
                Services.NETWORK.sendToServer(new SeekJukeboxPayload(be.getBlockPos(), offset));
                return true;
            }
            return false;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
        }
    }
}
