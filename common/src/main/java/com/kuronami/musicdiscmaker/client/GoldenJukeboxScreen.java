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

    // レイアウト幾何 (leftPos/topPos 相対)。値は branding/gen_golden_jukebox_gui.py
    // (レイアウトの正本) の widget 矩形と一致させる。上から: ヘッダ(disc+2行) →
    // transport(メイン: 再生/シーク/リピート) → 音量 → 範囲 → インベントリ。
    private static final int TRACK_TEXT_X = 32;     // ヘッダ テキスト x (disc スロット右)
    private static final int TITLE_Y = 19;          // 曲名 (1 行目)
    private static final int AUTHOR_Y = 31;         // 作者名 (2 行目)
    private static final int TRACK_TEXT_W = 136;    // ヘッダ テキストの折り返し幅 (176-32-8)
    private static final int PLAY_X = 8;            // 再生/一時停止 ボタン x
    private static final int REPEAT_X = 148;        // リピート ボタン x
    private static final int TRANSPORT_Y = 46;      // transport ボタン行 y (20px, center 56)
    private static final int SEEK_X = 32;
    private static final int SEEK_Y = 48;           // シークバー y (h16, center 56)
    private static final int SEEK_W = 112;
    private static final int SEEK_H = 16;
    private static final int TIME_Y = 68;           // 経過/総時間
    private static final int VOLUME_Y = 84;         // 音量スライダー
    private static final int RANGE_Y = 102;         // 範囲スライダー
    private static final int SLIDER_H = 13;         // スライダー高さ (細身・脇役)
    private static final long SEEK_SYNC_TOL_MS = 800L; // シーク後、BE 同期が追いついたと見なす許容

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
        this.imageHeight = 224;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 130;
    }

    @Override
    protected void init() {
        super.init();
        final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
        this.curRange = be.getRangeBlocks();
        this.curVolume = be.getVolumePercent();
        this.curRepeat = be.isRepeat();
        this.curPaused = be.isPaused();

        // ── transport (メインコントロール・最上段)。再生/一時停止 左・シーク 中央・リピート 右。
        // 再生/一時停止トグル。自然終了後は頭出し再生でリスタートする。
        this.playPauseButton = addRenderableWidget(new IconButton(leftPos + PLAY_X, topPos + TRANSPORT_Y,
                20, PLAY_SPRITE, Component.translatable("gui.music_disc_maker.golden_jukebox.play"), () -> {
                    if (isEnded()) {
                        Services.NETWORK.sendToServer(
                                new SeekJukeboxPayload(menu.getBlockEntity().getBlockPos(), 0L));
                    } else {
                        curPaused = !curPaused;
                        sendConfig();
                    }
                }));
        addRenderableWidget(new SeekBar(leftPos + SEEK_X, topPos + SEEK_Y, SEEK_W, SEEK_H));
        this.repeatButton = addRenderableWidget(new IconButton(leftPos + REPEAT_X, topPos + TRANSPORT_Y,
                20, LOOP_SPRITE, Component.translatable("gui.music_disc_maker.golden_jukebox.repeat_toggle"), () -> {
                    curRepeat = !curRepeat;
                    sendConfig();
                }));

        // ── 音量スライダー (細身・脇役)。
        addRenderableWidget(new SettingSlider(leftPos + 8, topPos + VOLUME_Y, 160, SLIDER_H,
                GoldenJukeboxBlockEntity.VOLUME_MIN, GoldenJukeboxBlockEntity.VOLUME_MAX, curVolume,
                "gui.music_disc_maker.golden_jukebox.volume", v -> {
                    curVolume = v;
                    sendConfig();
                }));
        // ── 範囲スライダー (細身・脇役)。
        addRenderableWidget(new SettingSlider(leftPos + 8, topPos + RANGE_Y, 160, SLIDER_H,
                GoldenJukeboxBlockEntity.RANGE_MIN, GoldenJukeboxBlockEntity.RANGE_MAX, curRange,
                "gui.music_disc_maker.golden_jukebox.range", v -> {
                    curRange = v;
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
        // ブロック名タイトルは出さず、ヘッダはディスク + 2 行のトラック情報に専念する。
        // インベントリラベルだけ自前で描く。
        g.drawString(font, this.playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT, false);

        final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
        // ヘッダ: 曲名 (1 行目) / 作者名 (2 行目)。各行を個別に折り返してパネル幅超過を防ぐ。
        final CustomTrackData track = be.currentTrack();
        if (track != null) {
            final String title = track.title() == null ? "" : track.title();
            g.drawString(font, font.plainSubstrByWidth(title, TRACK_TEXT_W),
                    TRACK_TEXT_X, TITLE_Y, TEXT, false);
            final String author = track.author();
            if (author != null && !author.isBlank()) {
                g.drawString(font, font.plainSubstrByWidth(author, TRACK_TEXT_W),
                        TRACK_TEXT_X, AUTHOR_Y, TIME_TEXT, false);
            }
        } else {
            final Component label = be.hasDisc()
                    ? Component.translatable("gui.music_disc_maker.golden_jukebox.vanilla_disc")
                    : Component.translatable("gui.music_disc_maker.golden_jukebox.no_track");
            g.drawString(font, label, TRACK_TEXT_X, TITLE_Y, TEXT, false);
        }

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

        // 脇役スライダー: 金の transport に主張で負けるよう、暗く低コントラストな溝 +
        // 中立グレーのつまみ + 淡色ラベルで細身に自前描画する (バニラの明るい widget を使わない)。
        @Override
        public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            final int x = getX();
            final int y = getY();
            final int w = width;
            final int h = height;
            final int cy = y + h / 2;
            // 溝 (暗・低コントラスト)。
            g.fill(x, cy - 2, x + w, cy + 2, 0xFF242424);
            g.fill(x, cy - 1, x + w, cy + 1, 0xFF333333);
            // 進捗 (控えめなグレー)。
            final int fillW = (int) Math.round(this.value * (w - 2));
            if (fillW > 0) {
                g.fill(x + 1, cy - 1, x + 1 + fillW, cy + 1, 0xFF555555);
            }
            // つまみ (中立グレー・小さめ)。
            final int knobX = x + (int) Math.round(this.value * (w - 4));
            final int knob = isHoveredOrFocused() ? 0xFF9A9A9A : 0xFF787878;
            g.fill(knobX, y + 1, knobX + 4, y + h - 1, 0xFF1C1C1C);
            g.fill(knobX + 1, y + 2, knobX + 3, y + h - 2, knob);
            // ラベル (淡色・中央・影なし)。
            final var font = net.minecraft.client.Minecraft.getInstance().font;
            final int tw = font.width(getMessage());
            g.drawString(font, getMessage(), x + (w - tw) / 2, cy - 4, 0xFFB2B2B2, false);
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
        // シーク送信後、server の新 anchor が BE 同期で届くまで scrub 位置を保持する
        // (それまでは client の進捗描画が旧 anchor に基づき一瞬旧位置へ飛ぶため)。
        private boolean pendingSeek;
        private long pendingSeekMs;
        private long pendingDeadlineMs;

        SeekBar(int x, int y, int width, int height) {
            super(x, y, width, height, CommonComponents.EMPTY);
        }

        /**
         * 表示位置の分数。シーク直後は要求位置を保持し、BE 同期が要求位置±許容に追いつくか
         * 上限時間を過ぎたら live 追従へ戻す (リリース直後のバー飛びを防ぐ)。
         */
        private double displayFraction(GoldenJukeboxBlockEntity be) {
            if (pendingSeek) {
                final long dur = be.trackDurationMs();
                final boolean synced = dur > 0L
                        && Math.abs(be.currentElapsedMs() - pendingSeekMs) <= SEEK_SYNC_TOL_MS;
                if (synced || System.currentTimeMillis() > pendingDeadlineMs) {
                    pendingSeek = false;
                } else {
                    return dur > 0L ? Mth.clamp(pendingSeekMs / (double) dur, 0.0, 1.0) : 0.0;
                }
            }
            return liveFraction();
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
            final double f = live ? 1.0 : (scrubbing ? scrubFraction : displayFraction(be));
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
                // BE 同期が届くまで scrub 位置を保持する (リリース直後のバー飛び防止)。
                pendingSeek = true;
                pendingSeekMs = offset;
                pendingDeadlineMs = System.currentTimeMillis() + 1000L;
                return true;
            }
            return false;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
        }
    }
}
