package com.kuronami.musicdiscmaker.client;

import java.util.function.IntConsumer;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.menu.GoldenJukeboxMenu;
import com.kuronami.musicdiscmaker.network.ConfigureJukeboxPayload;
import com.kuronami.musicdiscmaker.platform.Services;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 強化版ジュークボックスの設定 GUI。可聴範囲・音量スライダー、リピート・再生/停止トグル、
 * 曲名表示を持つ。変更は {@link ConfigureJukeboxPayload} で server の BE へ反映する。
 */
public class GoldenJukeboxScreen extends AbstractContainerScreen<GoldenJukeboxMenu> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(MusicDiscMaker.MODID, "textures/gui/golden_jukebox.png");
    private static final int TEXT = 0x404040;

    // 現在値 (BE から init で初期化、widget 操作で更新)。
    private int curRange = GoldenJukeboxBlockEntity.RANGE_DEFAULT;
    private int curVolume = GoldenJukeboxBlockEntity.VOLUME_DEFAULT;
    private boolean curRepeat;
    private boolean curPaused;

    private Button repeatButton;
    private Button playStopButton;

    public GoldenJukeboxScreen(GoldenJukeboxMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 200;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 108;
    }

    @Override
    protected void init() {
        super.init();
        final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
        this.curRange = be.getRangeBlocks();
        this.curVolume = be.getVolumePercent();
        this.curRepeat = be.isRepeat();
        this.curPaused = be.isPaused();

        addRenderableWidget(new SettingSlider(leftPos + 8, topPos + 40, 160, 20,
                GoldenJukeboxBlockEntity.RANGE_MIN, GoldenJukeboxBlockEntity.RANGE_MAX, curRange,
                "gui.music_disc_maker.golden_jukebox.range", v -> {
                    curRange = v;
                    sendConfig();
                }));
        addRenderableWidget(new SettingSlider(leftPos + 8, topPos + 62, 160, 20,
                GoldenJukeboxBlockEntity.VOLUME_MIN, GoldenJukeboxBlockEntity.VOLUME_MAX, curVolume,
                "gui.music_disc_maker.golden_jukebox.volume", v -> {
                    curVolume = v;
                    sendConfig();
                }));

        this.repeatButton = addRenderableWidget(Button.builder(repeatLabel(), b -> {
            curRepeat = !curRepeat;
            b.setMessage(repeatLabel());
            sendConfig();
        }).bounds(leftPos + 8, topPos + 84, 78, 20).build());
        this.repeatButton.active = !be.isLiveStream();

        this.playStopButton = addRenderableWidget(Button.builder(playStopLabel(), b -> {
            curPaused = !curPaused;
            b.setMessage(playStopLabel());
            sendConfig();
        }).bounds(leftPos + 90, topPos + 84, 78, 20).build());
    }

    private Component repeatLabel() {
        final Component state = curRepeat
                ? Component.translatable("gui.music_disc_maker.golden_jukebox.on")
                : Component.translatable("gui.music_disc_maker.golden_jukebox.off");
        return Component.translatable("gui.music_disc_maker.golden_jukebox.repeat", state);
    }

    private Component playStopLabel() {
        return curPaused
                ? Component.translatable("gui.music_disc_maker.golden_jukebox.play")
                : Component.translatable("gui.music_disc_maker.golden_jukebox.stop");
    }

    private void sendConfig() {
        Services.NETWORK.sendToServer(new ConfigureJukeboxPayload(
                menu.getBlockEntity().getBlockPos(), curRange, curVolume, curRepeat, curPaused));
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.blit(TEXTURE, leftPos, topPos, 0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        // 曲名 (ディスクスロット右)。無ければ "No disc"。
        final CustomTrackData track = menu.getBlockEntity().currentTrack();
        final Component label;
        if (track != null) {
            final String desc = (track.author() != null && !track.author().isBlank())
                    ? track.author() + " - " + track.title()
                    : track.title();
            label = Component.literal(font.plainSubstrByWidth(desc == null ? "" : desc, 130));
        } else if (menu.getBlockEntity().hasDisc()) {
            label = Component.translatable("gui.music_disc_maker.golden_jukebox.vanilla_disc");
        } else {
            label = Component.translatable("gui.music_disc_maker.golden_jukebox.no_track");
        }
        g.drawString(font, label, 36, 23, TEXT, false);
        // ラジオ (無限長ストリーム) は曲名の下に赤い「LIVE」を出す。
        if (menu.getBlockEntity().isLiveStream()) {
            g.drawString(font, Component.translatable("tooltip.music_disc_maker.live"),
                    36, 34, 0xD03030, false);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 別プレイヤー操作等で BE 状態が変わった時にトグルのラベルを追随させる。
        final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
        if (be.isPaused() != curPaused) {
            curPaused = be.isPaused();
            playStopButton.setMessage(playStopLabel());
        }
        if (repeatButton != null) {
            repeatButton.active = !be.isLiveStream();
        }
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
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
}
