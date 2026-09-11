package com.kuronami.musicdiscmaker.client;

import java.util.List;
import java.util.function.IntConsumer;
//? if >=1.21.2 {
//?} else {

/*import org.lwjgl.glfw.GLFW;
*///?}

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.client.audio.GoldenJukeboxFailures;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailure;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.menu.GoldenJukeboxMenu;
import com.kuronami.musicdiscmaker.network.ConfigureJukeboxPayload;
import com.kuronami.musicdiscmaker.network.NavigateJukeboxPayload;
import com.kuronami.musicdiscmaker.network.SeekJukeboxPayload;
import com.kuronami.musicdiscmaker.network.ShuffleJukeboxPayload;
//? if >=1.21.2 {
//?} else {
/*import com.kuronami.musicdiscmaker.platform.Services;
*///?}

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
//? if >=26.2 {
import net.minecraft.client.gui.components.Tooltip;
//?} elif >=26.1 {
//?} elif >=1.21.2 {
/*import net.minecraft.client.gui.components.Tooltip;
*///?} else {
/*import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
*///?}
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
//? if >=1.21.2 {
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
//?} else {
//?}
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
//? if >=1.21.2 {
import net.minecraft.resources.Identifier;
//?} elif >=1.21 {
/*import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
*///?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
//? if >=1.21.2 {
import net.minecraft.world.item.ItemStack;
import com.kuronami.musicdiscmaker.platform.Services;
//?} else {
//?}

/**
 * 強化版ジュークボックスの設定 GUI。可聴範囲・音量スライダー（バニラ式ドラッグ）と、音楽プレイヤー風の
 * 操作列（再生/一時停止トグル・リピートトグル・シークバー＋経過/総時間）を持つ。
 *
 * <p>設定変更は {@link ConfigureJukeboxPayload}、シーク頭出しは {@link SeekJukeboxPayload} で
 * server の BE へ反映する。進捗表示は BE が同期する playbackStartGameTime から算出する
 * （{@link GoldenJukeboxBlockEntity#currentElapsedMs()}）。
 *
 * <p>26.1.2: 描画は {@link GuiGraphicsExtractor} の extract モデル（{@code extractBackground} /
 * {@code extractLabels} / widget の {@code extractWidgetRenderState} / button の {@code extractContents}）。
 * hover tooltip は {@code AbstractContainerScreen} が自動描画するので render override は持たない。
 * widget ドラッグの focus 転送も {@code ContainerEventHandler} 既定が担うため自前転送は不要。
 */
public class GoldenJukeboxScreen extends AbstractContainerScreen<GoldenJukeboxMenu> {

    //? if >=1.21.2 {
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/gui/golden_jukebox.png");
    private static final int TEXT = 0xFF404040;
    private static final int TIME_TEXT = 0xFF606060;
    /** 失敗の文の色 (濃い赤 + 影)。板の上で読める最小の彩度。 */
    private static final int ERROR = 0xFFB02020;
    //?} elif >=1.21 {
    /*private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/gui/golden_jukebox.png");
    private static final int TEXT = 0x404040;
    private static final int TIME_TEXT = 0x606060;
    // 失敗ラベル。制作機 (MusicDiscMakerScreen) と同じ形に揃える —
    // 板なし・濃い赤 + shadow (出所: branding/b1-legibility「配置B・色4」)。
    private static final int ERROR = 0xB02020;
    *///?} else {
    /*private static final ResourceLocation TEXTURE =
            new ResourceLocation(MusicDiscMaker.MODID, "textures/gui/golden_jukebox.png");
    private static final int TEXT = 0x404040;
    private static final int TIME_TEXT = 0x606060;
    /^* 失敗の文の色 (濃い赤 + 影)。板の上で読める最小の彩度。 ^/
    private static final int ERROR = 0xB02020;
    *///?}

    // 承認済み transport sprite は個別resourceとして保持する。atlasへ再描画・再圧縮しない。
    private static final String SPRITE_ROOT = "textures/gui/golden_jukebox/transport/";
    private static final String CONTROLS = "textures/gui/golden_jukebox/controls.png";

    // The atlas is 256x64. Horizontal center regions are cropped; end caps retain one pixel.
    //? if >=26.1 {
    private static void control(GuiGraphicsExtractor g, int x, int y, int u, int v, int w, int h) {
    //?} else {
    /*private static void control(GuiGraphics g, int x, int y, int u, int v, int w, int h) {
    *///?}
        if (w <= 0 || h <= 0) return;
        //? if >=1.21.2 {
        g.blit(RenderPipelines.GUI_TEXTURED, Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, CONTROLS),
                x, y, (float) u, (float) v, w, h, 256, 64);
        //?} elif >=1.21 {
        /*g.blit(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, CONTROLS),
                x, y, (float) u, (float) v, w, h, 256, 64);
        *///?} else {
        /*g.blit(new ResourceLocation(MusicDiscMaker.MODID, CONTROLS),
                x, y, (float) u, (float) v, w, h, 256, 64);
        *///?}
    }

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
    private static final int LOOP_SPRITE = 16;
    // 指向性トグル (16x16, v=24 の段)。OFF = 枠の中心にある点 (どの壁からも等距離)、
    // ON = 同じ点が隅に寄る。形そのものが state を表すので無彩色・枠なし。
    private static final int ICON_DIR_ON_U = 176;
    private static final int ICON_DIR_OFF_U = 192;
    private static final int ICON_DIR_V = 24;


    // B2: 曲情報 → 進捗 → 中央5枠transport → 音量 → 範囲 → インベントリ。
    private static final int PLAY_X = GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.PLAY_PAUSE);
    private static final int SHUFFLE_X = GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.SHUFFLE);
    private static final int PREVIOUS_X = GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.PREVIOUS);
    private static final int NEXT_X = GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.NEXT);
    private static final int REPEAT_X = GoldenJukeboxTransportLayout.buttonX(GoldenJukeboxTransportLayout.REPEAT);
    private static final int TRANSPORT_Y = GoldenJukeboxTransportLayout.BUTTON_Y;
    private static final int SEEK_X = GoldenJukeboxTransportLayout.TIME_X;
    private static final int SEEK_Y = GoldenJukeboxTransportLayout.SEEK_Y;
    private static final int SEEK_W = GoldenJukeboxTransportLayout.TIME_RIGHT - SEEK_X;
    private static final int SEEK_H = GoldenJukeboxTransportLayout.SEEK_HEIGHT;
    private static final int TIME_X = GoldenJukeboxTransportLayout.TIME_X;
    private static final int TIME_RIGHT = GoldenJukeboxTransportLayout.TIME_RIGHT;
    private static final int TIME_Y = GoldenJukeboxTransportLayout.TIME_Y;
    private static final int VOLUME_Y = 88;
    private static final int RANGE_Y = 106;
    private static final int DIR_X = 152;
    private static final int DIR_Y = RANGE_Y - 1;
    private static final int DIR_W = 16;
    //? if >=1.21 {
    private static final int TRACK_TEXT_X = 32;     // ヘッダ テキスト x (disc スロット右)
    private static final int TITLE_Y = 19;          // 曲名 (1 行目)
    private static final int AUTHOR_Y = 31;         // 作者名 (2 行目)
    private static final int TRACK_TEXT_W = 136;    // ヘッダ テキストの折り返し幅 (176-32-8)
    private static final int RANGE_W = 140;         // 範囲スライダー幅 (右端に指向性トグルを置くぶん短い)
    private static final int SLIDER_H = 15;         // スライダー高さ (ラベルがバー内に読める太さ)
    // 失敗の文の帯。1 行目 y は範囲スライダーの下端 (102+15 = 116 行目まで) から 2px 下。
    // ここより上は KURONAMI333 が合格と裁定した面なので、行数が増えても上へは伸ばさない。
    private static final int FAIL_Y = 122;
    private static final int FAIL_X = 8;            // 左端はスライダー・インベントリラベルと同じ列
    private static final int FAIL_W = 160;          // 折り返し幅 (imageWidth 176 - 左右余白 8)
    /**
     * 帯に常時確保する行数。パネル高さ ({@code imageHeight}) はこれで決まるので、
     * <b>文を書き換えたら実測し直す</b> — 出荷している 14 ロケールの中で 1 本でもこれを超えると、
     * はみ出した行がインベントリのラベルに重なる。
     *
     * <p>3 行なのは英語が 3 行だから。実測は正リポ mod-047 の
     * {@code branding/b1-final/sweep.py} (vanilla の ascii.png 実測幅 +
     * {@code StringSplitter.LineBreakFinder} 相当の折り返し)。文言は 5 リポで共有している。
     */
    private static final int FAIL_LINES = 3;
    //?} else {
    /*private static final int TRACK_TEXT_X = GoldenJukeboxLayout.TRACK_TEXT_X; // ヘッダ テキスト x (disc スロット右)
    private static final int TITLE_Y = GoldenJukeboxLayout.TITLE_Y;          // 曲名 (1 行目)
    private static final int AUTHOR_Y = GoldenJukeboxLayout.AUTHOR_Y;        // 作者名 (2 行目)
    // ヘッダ テキストの折り返し幅 (パネル右端まで、右余白 8)。
    private static final int TRACK_TEXT_W = GoldenJukeboxLayout.IMAGE_W - GoldenJukeboxLayout.TRACK_TEXT_X - 8;
    private static final int SLIDER_X = GoldenJukeboxLayout.INV_X;   // スライダー x (インベントリ左端に揃える)
    private static final int SLIDER_W = GoldenJukeboxLayout.SLIDER_W; // スライダー幅
    private static final int RANGE_W = GoldenJukeboxLayout.RANGE_W;  // 範囲スライダー幅 (右端に指向性トグルを置くぶん短い)
    private static final int SLIDER_H = GoldenJukeboxLayout.SLIDER_H; // スライダー高さ (ラベルがバー内に読める太さ)
    // 失敗の文の帯 (単一座標源 = branding/gen_golden_jukebox_gui.py の FAIL)。
    private static final int FAIL_X = GoldenJukeboxLayout.FAIL_X;
    private static final int FAIL_Y = 122;
    private static final int FAIL_W = GoldenJukeboxLayout.FAIL_W;
    /^*
     * 帯に常時確保する行数。パネル高さ ({@code imageHeight}) はこれで決まるので、
     * <b>文を書き換えたら実測し直す</b> — 出荷している 14 ロケールの中で 1 本でもこれを超えると、
     * はみ出した行がインベントリのラベルに重なる。
     *
     * <p>3 行なのは英語が 3 行だから。実測は正リポ mod-047 の
     * {@code branding/b1-final/sweep.py}。文言は 5 リポで共有している。
     ^/
    private static final int FAIL_LINES = GoldenJukeboxLayout.FAIL_LINES;
    *///?}
    private static final long SEEK_SYNC_TOL_MS = 800L; // シーク後、BE 同期が追いついたと見なす許容

    // 現在値 (BE から init で初期化、widget 操作で更新)。
    private int curRange = GoldenJukeboxBlockEntity.RANGE_DEFAULT;
    private int curVolume = GoldenJukeboxBlockEntity.VOLUME_DEFAULT;
    private boolean curRepeat;
    private boolean curShuffle;
    private boolean curPaused;
    private boolean curDirectional = GoldenJukeboxBlockEntity.DIRECTIONAL_DEFAULT;

    private IconButton repeatButton;
    private IconButton shuffleButton;
    private IconButton playPauseButton;
    private IconButton previousButton;
    private IconButton nextButton;
    private IconButton directionalButton;
    private SeekBar seekBar;

    public GoldenJukeboxScreen(GoldenJukeboxMenu menu, Inventory playerInventory, Component title) {
        //? if >=26.1 {
        //?} else {
        /*super(menu, playerInventory, title);
        this.imageWidth = 176;
        *///?}
        // 失敗の帯が FAIL_LINES 行 (119 / 128 / 137 行目) を占め、末尾 144 の 3px 下から
        // インベントリラベルが始まる。以下 3 つはバニラ標準式のまま (H-94 / H-82 / H-24)。
        // 在庫スロットとホットバーは GoldenJukeboxMenu 側の addSlot と必ず一致させる。
        //? if >=26.1 {
        super(menu, playerInventory, title, 176, 242);
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 148;
        //?} elif >=1.21.2 {
        /*this.imageHeight = 242;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = 148;
        *///?} elif >=1.21 {
        /*this.imageHeight = 242;
        this.inventoryLabelX = 8;
        this.inventoryLabelY = this.imageHeight - 94; // 148
        *///?} else {
        /*this.imageHeight = GoldenJukeboxLayout.IMAGE_H;
        this.inventoryLabelX = GoldenJukeboxLayout.INV_LABEL_X;
        this.inventoryLabelY = GoldenJukeboxLayout.INV_LABEL_Y;
        *///?}
    }

    @Override
    protected void init() {
        super.init();
        final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
        this.curRange = be.getRangeBlocks();
        this.curVolume = be.getVolumePercent();
        this.curRepeat = be.isRepeat();
        this.curShuffle = be.isShuffle();
        this.curPaused = be.isPaused();
        this.curDirectional = be.isDirectional();

        // ── transport。5枠は shuffle / previous / play / next / repeat のB2配置。
        this.shuffleButton = addRenderableWidget(new IconButton(leftPos + SHUFFLE_X, topPos + TRANSPORT_Y,
                20, LOOP_SPRITE,
                Component.translatable("gui.music_disc_maker.golden_jukebox.shuffle_toggle"), () -> {
                    curShuffle = !curShuffle;
                    sendShuffle();
                }));
        this.previousButton = addRenderableWidget(new IconButton(leftPos + PREVIOUS_X, topPos + TRANSPORT_Y,
                20, LOOP_SPRITE,
                Component.translatable("gui.music_disc_maker.golden_jukebox.previous"), () -> sendNavigation(true)));
        // 再生/一時停止トグル。自然終了後は頭出し再生でリスタートする。
        this.playPauseButton = addRenderableWidget(new IconButton(leftPos + PLAY_X, topPos + TRANSPORT_Y,
                20, 20, Component.translatable("gui.music_disc_maker.golden_jukebox.play"), () -> {
                    if (menu.getBlockEntity().isStopped()) {
                        if (!com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler.isProtocolMismatch()) {
                            Services.NETWORK.sendToServer(
                                    new SeekJukeboxPayload(menu.getBlockEntity().getBlockPos(), 0L));
                        }
                    } else {
                        curPaused = !curPaused;
                        sendConfig();
                    }
                }));
        this.nextButton = addRenderableWidget(new IconButton(leftPos + NEXT_X, topPos + TRANSPORT_Y,
                20, LOOP_SPRITE,
                Component.translatable("gui.music_disc_maker.golden_jukebox.next"), () -> sendNavigation(false)));
        this.seekBar = addRenderableWidget(new SeekBar(leftPos + SEEK_X, topPos + SEEK_Y, SEEK_W, SEEK_H));
        this.repeatButton = addRenderableWidget(new IconButton(leftPos + REPEAT_X, topPos + TRANSPORT_Y,
                20, LOOP_SPRITE, Component.translatable("gui.music_disc_maker.golden_jukebox.repeat_toggle"), () -> {
                    curRepeat = !curRepeat;
                    sendConfig();
                }));

        // ── 音量スライダー (細身・脇役)。音量は毎 tick 反映されるのでドラッグ中も逐次送る。
        addRenderableWidget(new SettingSlider(leftPos + 8, topPos + VOLUME_Y, 160, SLIDER_H,
                GoldenJukeboxBlockEntity.VOLUME_MIN, GoldenJukeboxBlockEntity.VOLUME_MAX, curVolume,
                "gui.music_disc_maker.golden_jukebox.volume", false, v -> {
                    curVolume = v;
                    sendConfig();
                }));
        // ── 範囲スライダー (細身・脇役)。範囲変更は再ストリームを伴うため、ドラッグ中は送らず
        // リリース (確定) 時に一度だけ送る。矢印キーは離散操作なので即確定する。
        addRenderableWidget(new SettingSlider(leftPos + 8, topPos + RANGE_Y, RANGE_W, SLIDER_H,
                GoldenJukeboxBlockEntity.RANGE_MIN, GoldenJukeboxBlockEntity.RANGE_MAX, curRange,
                "gui.music_disc_maker.golden_jukebox.range", true, v -> {
                    curRange = v;
                    sendConfig();
                }));
        // ── 指向性トグル (範囲バーの横)。ON = 定位と距離減衰つき / OFF = 範囲内フラット。
        // client 側は毎 tick の座標の書き方を変えるだけなので、切り替えても曲は途切れない。
        // 素置き (他の transport ボタンと同じ文法)。
        this.directionalButton = addRenderableWidget(new IconButton(leftPos + DIR_X, topPos + DIR_Y,
                DIR_W, LOOP_SPRITE,
                Component.translatable("gui.music_disc_maker.golden_jukebox.directional"), () -> {
                    curDirectional = !curDirectional;
                    sendConfig();
                }));

        refreshTransportSprites();
    }

    /** BE 状態からトグルのスプライト・活性を更新する。 */
    private void refreshTransportSprites() {
        final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
        final boolean navigable = be.navigableTrackCount() > 1;
        if (shuffleButton != null) {
            shuffleButton.active = navigable && be.getAlbumTrack() < 0;
            shuffleButton.setTransportSprite(curShuffle ? SPRITE_SHUFFLE_ON : SPRITE_SHUFFLE_OFF, LOOP_SPRITE);
        }
        if (previousButton != null) {
            previousButton.active = navigable;
            previousButton.setTransportSprite(navigable ? SPRITE_PREVIOUS : SPRITE_PREVIOUS_OFF, LOOP_SPRITE);
        }
        if (playPauseButton != null) {
            playPauseButton.active = be.hasDisc();
            // 一時停止中・自然終了後は「再生」アイコン、再生中は「一時停止」アイコン。
            playPauseButton.setTransportSprite((curPaused || be.isStopped()) ? SPRITE_PLAY : SPRITE_PAUSE, 20);
        }
        if (nextButton != null) {
            nextButton.active = navigable;
            nextButton.setTransportSprite(navigable ? SPRITE_NEXT : SPRITE_NEXT_OFF, LOOP_SPRITE);
        }
        if (repeatButton != null) {
            repeatButton.active = !be.isLiveStream();
            repeatButton.setTransportSprite(curRepeat && !be.isLiveStream()
                    ? SPRITE_REPEAT_ON : SPRITE_REPEAT_OFF, LOOP_SPRITE);
        }
        if (directionalButton != null) {
            // 現在のモードの形を出す (2 モードなので活性/非活性ではない)。何のトグルかは tooltip で言う。
            directionalButton.setAtlasSprite(curDirectional ? ICON_DIR_ON_U : ICON_DIR_OFF_U, ICON_DIR_V);
            //? if >=26.2 {
            directionalButton.setTooltip(Tooltip.create(Component.translatable(curDirectional
                    ? "gui.music_disc_maker.golden_jukebox.directional.on"
                    : "gui.music_disc_maker.golden_jukebox.directional.off")));
            //?} elif >=26.1 {
            //?} else {
            /*directionalButton.setTooltip(Tooltip.create(Component.translatable(curDirectional
                    ? "gui.music_disc_maker.golden_jukebox.directional.on"
                    : "gui.music_disc_maker.golden_jukebox.directional.off")));
            *///?}
        }
    }

    private void sendConfig() {
        if (com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler.isProtocolMismatch()) {
            return; // B5/C12: 版不一致で client 機能を無効化中
        }
        Services.NETWORK.sendToServer(new ConfigureJukeboxPayload(
                menu.getBlockEntity().getBlockPos(), curRange, curVolume, curRepeat, curPaused, curDirectional));
    }

    private void sendNavigation(boolean previous) {
        if (com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler.isProtocolMismatch()) {
            return;
        }
        final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
        Services.NETWORK.sendToServer(new NavigateJukeboxPayload(
                be.getBlockPos(), menu.containerId, be.playbackCursor().generation(), previous));
    }

    private void sendShuffle() {
        if (com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler.isProtocolMismatch()) {
            return;
        }
        final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
        Services.NETWORK.sendToServer(new ShuffleJukeboxPayload(
                be.getBlockPos(), menu.containerId, be.playbackCursor().generation(), curShuffle));
    }

    private static String formatMs(long ms) {
        final long totalSeconds = Math.max(0L, ms) / 1000L;
        return String.format("%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
    }

    /**
     * 1.21.11 の {@code AbstractContainerScreen#render} は {@code renderTooltip} を呼ばないので、
     * サブクラス側で明示的に呼ぶ (呼ばないとスロットのアイテムにホバーしても何も出ない)。
     */
    @Override
    //? if >=26.1 {
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, leftPos, topPos,
                0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
    //?} elif >=1.21.2 {
    /*public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, leftPos, topPos,
                0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
    *///?} else {
    /*protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.blit(TEXTURE, leftPos, topPos, 0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
    *///?}
    }

    @Override
    //? if >=26.1 {
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // ブロック名タイトルは出さず (super は呼ばない)、ヘッダはディスク + 2 行のトラック情報に専念する。
    //?} else {
    /*protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // ブロック名タイトルは出さず (super は呼ばない)、ヘッダはディスク + 2 行のトラック情報に専念する。
    *///?}
        // インベントリラベルだけ自前で描く。
        //? if >=26.1 {
        g.text(font, this.playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT, false);
        //?} else {
        /*g.drawString(font, this.playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT, false);
        *///?}

        final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
        // ヘッダ: 曲名 (1 行目) / 作者名 (2 行目)。各行を個別に折り返してパネル幅超過を防ぐ。
        //? if >=26.1 {
        // アルバムの時は 1 行目が現在トラック名になり、同じ行の右端に "3/8" を置く (行も widget も増やさない)。
        final int albumCount = be.albumTrackCount();
        final String counter = albumCount > 0 ? (be.getAlbumTrack() + 1) + "/" + albumCount : null;
        final int titleW = counter == null ? TRACK_TEXT_W : TRACK_TEXT_W - font.width(counter) - 4;
        if (counter != null) {
            g.text(font, counter, TRACK_TEXT_X + TRACK_TEXT_W - font.width(counter),
                    TITLE_Y, TIME_TEXT, false);
        }
        //?} elif >=1.21 {
        /*// アルバムの時は 1 行目が現在トラック名になり、同じ行の右端に "3/8" を置く (行も widget も増やさない)。
        final int albumCount = be.albumTrackCount();
        final String counter = albumCount > 0 ? (be.getAlbumTrack() + 1) + "/" + albumCount : null;
        final int titleW = counter == null ? TRACK_TEXT_W : TRACK_TEXT_W - font.width(counter) - 4;
        if (counter != null) {
            g.drawString(font, counter, TRACK_TEXT_X + TRACK_TEXT_W - font.width(counter),
                    TITLE_Y, TIME_TEXT, false);
        }
        *///?} else {
        //?}
        final CustomTrackData track = be.currentTrack();
        if (track != null) {
            final String title = track.title() == null ? "" : track.title();
            //? if >=26.1 {
            g.text(font, font.plainSubstrByWidth(title, titleW),
            //?} elif >=1.21 {
            /*g.drawString(font, font.plainSubstrByWidth(title, titleW),
            *///?} else {
            /*g.drawString(font, font.plainSubstrByWidth(title, TRACK_TEXT_W),
            *///?}
                    TRACK_TEXT_X, TITLE_Y, TEXT, false);
            final String author = track.author();
            if (author != null && !author.isBlank()) {
                //? if >=26.1 {
                g.text(font, font.plainSubstrByWidth(author, TRACK_TEXT_W),
                //?} else {
                /*g.drawString(font, font.plainSubstrByWidth(author, TRACK_TEXT_W),
                *///?}
                        TRACK_TEXT_X, AUTHOR_Y, TIME_TEXT, false);
            }
        } else if (be.hasDisc()) {
            // vanilla / 他 MOD のディスク: jukebox_song の description (例 "C418 - cat") を 1 行目、
            // アイテム表示名 (例 "ミュージックディスク") を 2 行目に。custom disc と同じ 2 行構造を汎用化する。
            // description の "Artist - Title" 分割はしない (書式は vanilla lang の慣習で API 契約でなく、
            // 他 MOD の任意 description を壊すため)。description が無ければアイテム名だけ。
            //? if >=1.21 {
            // アルバムなら現在トラックのディスク。空アルバム等で解決できなければアルバム本体の名前に落とす。
            final ItemStack shown = be.effectiveDisc().isEmpty() ? be.getDisc() : be.effectiveDisc();
            //?} else {
            //?}
            final Component desc = be.discSongDescription();
            //? if >=1.21 {
            final String itemName = shown.getHoverName().getString();
            //?} else {
            /*final String itemName = be.getDisc().getHoverName().getString();
            *///?}
            if (desc != null) {
                //? if >=26.1 {
                g.text(font, font.plainSubstrByWidth(desc.getString(), titleW),
                //?} elif >=1.21 {
                /*g.drawString(font, font.plainSubstrByWidth(desc.getString(), titleW),
                *///?} else {
                /*g.drawString(font, font.plainSubstrByWidth(desc.getString(), TRACK_TEXT_W),
                *///?}
                        TRACK_TEXT_X, TITLE_Y, TEXT, false);
                //? if >=26.1 {
                g.text(font, font.plainSubstrByWidth(itemName, TRACK_TEXT_W),
                //?} else {
                /*g.drawString(font, font.plainSubstrByWidth(itemName, TRACK_TEXT_W),
                *///?}
                        TRACK_TEXT_X, AUTHOR_Y, TIME_TEXT, false);
            } else {
                //? if >=26.1 {
                g.text(font, font.plainSubstrByWidth(itemName, titleW),
                //?} elif >=1.21 {
                /*g.drawString(font, font.plainSubstrByWidth(itemName, titleW),
                *///?} else {
                /*g.drawString(font, font.plainSubstrByWidth(itemName, TRACK_TEXT_W),
                *///?}
                        TRACK_TEXT_X, TITLE_Y, TEXT, false);
            }
        } else {
            //? if >=26.1 {
            g.text(font, Component.translatable("gui.music_disc_maker.golden_jukebox.no_track"),
            //?} else {
            /*g.drawString(font, Component.translatable("gui.music_disc_maker.golden_jukebox.no_track"),
            *///?}
                    TRACK_TEXT_X, TITLE_Y, TEXT, false);
        }

        // 経過 / 総時間 (シークバー下)。ラジオは経過 + LIVE。
        final long elapsed = be.currentElapsedMs();
        final String elapsedStr = formatMs(elapsed);
        if (be.isLiveStream()) {
            //? if >=26.1 {
            g.text(font, elapsedStr, TIME_X, TIME_Y, TIME_TEXT, false);
            //?} else {
            /*g.drawString(font, elapsedStr, TIME_X, TIME_Y, TIME_TEXT, false);
            *///?}
            final Component live = Component.translatable("tooltip.music_disc_maker.live");
            final int lw = font.width(live);
            //? if >=26.1 {
            g.text(font, live, TIME_RIGHT - lw, TIME_Y, 0xFFD03030, false);
            //?} elif >=1.21.2 {
            /*g.drawString(font, live, TIME_RIGHT - lw, TIME_Y, 0xFFD03030, false);
            *///?} else {
            /*g.drawString(font, live, TIME_RIGHT - lw, TIME_Y, 0xD03030, false);
            *///?}
        } else if (be.hasDisc() && be.trackDurationMs() > 0L) {
            //? if >=26.1 {
            g.text(font, elapsedStr, TIME_X, TIME_Y, TIME_TEXT, false);
            //?} else {
            /*g.drawString(font, elapsedStr, TIME_X, TIME_Y, TIME_TEXT, false);
            *///?}
            final String total = formatMs(be.trackDurationMs());
            //? if >=26.1 {
            g.text(font, total, TIME_RIGHT - font.width(total), TIME_Y, TIME_TEXT, false);
            //?} else {
            /*g.drawString(font, total, TIME_RIGHT - font.width(total), TIME_Y, TIME_TEXT, false);
            *///?}
        }

        renderFailureLabel(g, be);
    }

    //? if >=26.1 {
    /**
     * この jukebox が<b>鳴らなかった理由</b>を短い文で出す ({@link GoldenJukeboxFailures} が座標ごとに
     * 覚えているもの)。
     *
     * <h2>ここが唯一の出口である理由</h2>
     * 失敗はストリームを開く client の中でしか分からない ({@link PlaybackFailure} 参照)。チャットへ
     * 流すと<b>その jukebox を見ていない全員</b>に届き、ラジオの再接続や複数台の同時再生では
     * 読む気を無くす量になる。理由が要るのは「鳴らないな」と思って画面を開いた人だけなので、
     * 出口をこの 1 行に絞る。技術詳細 (分類ラベル・URL・例外) は {@code latest.log} 側が持つ。
     *
     * <p>失敗を覚えていない時は<b>何も描かない</b> — 空の帯を常時見せない。
     *
     * <p>文は幅 {@code FAIL_W} で折り返す。行数は言語で変わるので、確保してある
     * {@code FAIL_LINES} 行を<b>超えた分は描かない</b> — はみ出す代わりにインベントリの
     * ラベルへ重なるより、最後の 1 行が欠けるほうが画面として壊れない。
     */
    private void renderFailureLabel(GuiGraphicsExtractor g, GoldenJukeboxBlockEntity be) {
    //?} else {
    /*/^*
     * この jukebox が<b>鳴らなかった理由</b>を短い文で出す ({@link GoldenJukeboxFailures} が座標ごとに
     * 覚えているもの)。
     *
     * <h2>ここが唯一の出口である理由</h2>
     * 失敗はストリームを開く client の中でしか分からない ({@link PlaybackFailure} 参照)。チャットへ
     * 流すと<b>その jukebox を見ていない全員</b>に届き、ラジオの再接続や複数台の同時再生では
     * 読む気を無くす量になる。理由が要るのは「鳴らないな」と思って画面を開いた人だけなので、
     * 出口をこの 1 行に絞る。技術詳細 (分類ラベル・URL・例外) は {@code latest.log} 側が持つ。
     *
     * <p>失敗を覚えていない時は<b>何も描かない</b> — 空の帯を常時見せない。
     *
     * <p>文は幅 {@code FAIL_W} で折り返す。行数は言語で変わるので、確保してある
     * {@code FAIL_LINES} 行を<b>超えた分は描かない</b> — はみ出す代わりにインベントリの
     * ラベルへ重なるより、最後の 1 行が欠けるほうが画面として壊れない。
     ^/
    private void renderFailureLabel(GuiGraphics g, GoldenJukeboxBlockEntity be) {
    *///?}
        final PlaybackFailure failure = GoldenJukeboxFailures.get().latest(be.getBlockPos());
        if (failure == null) {
            return;
        }
        final Component sentence = Component.translatable(failure.kind().guiKey());
        final List<FormattedCharSequence> lines = font.split(sentence, FAIL_W);
        for (int i = 0; i < Math.min(lines.size(), FAIL_LINES); i++) {
            //? if >=26.1 {
            g.text(font, lines.get(i), FAIL_X, FAIL_Y + i * font.lineHeight, ERROR, false);
            //?} else {
            /*g.drawString(font, lines.get(i), FAIL_X, FAIL_Y + i * font.lineHeight, ERROR, false);
            *///?}
        }
    }

    @Override
    //? if >=1.21.2 {
    protected void containerTick() {
        super.containerTick();
        // 別プレイヤー操作等で BE 状態が変わったらトグルを追随させ、スプライトを更新する。
    //?} else {
    /*public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 別プレイヤー操作等で BE 状態が変わったらトグルを追随させる。
    *///?}
        final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
        if (be.isPaused() != curPaused) {
            curPaused = be.isPaused();
        }
        if (be.isRepeat() != curRepeat) {
            curRepeat = be.isRepeat();
        }
        if (be.isShuffle() != curShuffle) {
            curShuffle = be.isShuffle();
        }
        if (be.isDirectional() != curDirectional) {
            curDirectional = be.isDirectional();
        }
        refreshTransportSprites();
    //? if >=26.2 {
    //?} elif >=26.1 {
    /*}

    /^*
     * 指向性トグルにホバーしたら現在モードの tooltip を予約する。widget 自前の tooltip 機構は使わず、
     * {@link MusicDiscMakerScreen} と同じ手動 hover 判定 + {@code setTooltipForNextFrame} に揃える
     * (26.1.2 の extract パイプラインでは extractTooltip が hover ツールチップのフック)。
     ^/
    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
        final int left = leftPos + DIR_X;
        final int top = topPos + DIR_Y;
        if (mouseX >= left && mouseX < left + DIR_W && mouseY >= top && mouseY < top + DIR_W) {
            final Component tip = Component.translatable(curDirectional
                    ? "gui.music_disc_maker.golden_jukebox.directional.on"
                    : "gui.music_disc_maker.golden_jukebox.directional.off");
            graphics.setTooltipForNextFrame(font.split(tip, 200), mouseX, mouseY);
        }
    *///?} elif >=1.21.2 {
    //?} else {
        /*super.render(g, mouseX, mouseY, partialTick);
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
    *///?}
    }

    /** 整数値スライダー。ドラッグ/キーで値が変わったら {@code onChange} を呼ぶ (同一整数は dedup)。 */
    private static final class SettingSlider extends AbstractSliderButton {

        private final int min;
        private final int max;
        private final String labelKey;
        private final IntConsumer onChange;
        /** true = ドラッグ中は onChange を送らず、リリース/キー操作の確定時に一度だけ送る。 */
        private final boolean commitOnRelease;
        private int current;
        /** commitOnRelease 時、未送信の変更があるか。 */
        private boolean pendingCommit;

        SettingSlider(int x, int y, int width, int height, int min, int max, int initial,
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
        //? if >=1.21.2 {
        public boolean mouseReleased(MouseButtonEvent event) {
            final boolean handled = super.mouseReleased(event);
        //?} else {
        /*public boolean mouseReleased(double mouseX, double mouseY, int button) {
            final boolean handled = super.mouseReleased(mouseX, mouseY, button);
        *///?}
            commitPending(); // ドラッグ/クリックの確定
            return handled;
        }

        // 脇役スライダー: 金の transport に負ける暗く低コントラストな見た目のまま、
        // ラベルがバー内に読める太さ (溝を全高) で自前描画する。バニラの明るい widget は使わない。
        @Override
        //? if >=26.1 {
        public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        //?} else {
        /*public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        *///?}
            final int x = getX();
            final int y = getY();
            final int w = width;
            final int h = height;
            control(g, x, y, 0, 0, 1, h);
            control(g, x + 1, y, 1, 0, w - 2, h);
            control(g, x + w - 1, y, 255, 0, 1, h);
            final int fillW = (int) Math.round(this.value * (w - 2));
            control(g, x + 1, y + 1, 0, 16, fillW, h - 2);
            final int knobX = x + (int) Math.round(this.value * (w - 4));
            control(g, knobX, y, isHoveredOrFocused() ? 8 : 0, 32, 4, h);
            // ラベル (バー内に読めるよう影付き・中央)。
            final var font = net.minecraft.client.Minecraft.getInstance().font;
            final int tw = font.width(getMessage());
            //? if >=26.1 {
            g.text(font, getMessage(), x + (w - tw) / 2, y + (h - 8) / 2, 0xFFD8D8D8, true);
            //?} else {
            /*g.drawString(font, getMessage(), x + (w - tw) / 2, y + (h - 8) / 2, 0xFFD8D8D8, true);
            *///?}
        }

        // 矢印キー左右で整数値を正確に ±1 する。AbstractSliderButton の既定は fraction を
        // 微小量ずらすため丸め誤差で ±1/±2 が混ざる。整数側で離散ステップして fraction を
        // 再計算することで常に 1 ずつ動かす。
        @Override
        //? if >=1.21.2 {
        public boolean keyPressed(KeyEvent event) {
            final int dir = event.isRight() ? 1 : event.isLeft() ? -1 : 0;
        //?} else {
        /*public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            final int dir = keyCode == GLFW.GLFW_KEY_RIGHT ? 1
                    : keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 0;
        *///?}
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
            //? if >=1.21.2 {
            return super.keyPressed(event);
            //?} else {
            /*return super.keyPressed(keyCode, scanCode, modifiers);
            *///?}
        }
    }

    /** アイコンのみのフラットボタン (音楽プレイヤー風)。sprite は TEXTURE 内。focus/hover 背景は描かない。 */
    private static final class IconButton extends AbstractButton {

        private int u;
        private int v;
        private int spriteSize;
        private String transportSprite;
        //? if >=1.21.2 {
        private final Runnable action;
        //?} else {
        /*private final Runnable onPress;
        *///?}

        //? if >=1.21.2 {
        IconButton(int x, int y, int size, int spriteSize, Component narration, Runnable action) {
        //?} else {
        /*IconButton(int x, int y, int size, int spriteSize, Component narration, Runnable onPress) {
        *///?}
            super(x, y, size, size, narration);
            this.spriteSize = spriteSize;
            //? if >=1.21.2 {
            this.action = action;
            //?} else {
            /*this.onPress = onPress;
            *///?}
        }

        void setAtlasSprite(int u, int v) {
            this.transportSprite = null;
            this.u = u;
            this.v = v;
        }

        void setTransportSprite(String name, int spriteSize) {
            this.transportSprite = name;
            this.spriteSize = spriteSize;
        }

        @Override
        //? if >=1.21.2 {
        public void onPress(InputWithModifiers input) {
            action.run();
        //?} else {
        /*public void onPress() {
            onPress.run();
        *///?}
        }

        @Override
        //? if >=26.1 {
        protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            // スプライトのみ描画。widget 背景を描かないので focus/hover の四角は出ない
            // (AbstractButton の extractWidgetRenderState は extractContents + handleCursor のみ)。
        //?} elif >=1.21.2 {
        /*protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            // スプライトのみ描画。widget 背景を描かないので focus/hover の四角は出ない
            // (AbstractButton の renderWidget は renderContents + handleCursor のみ)。
        *///?} else {
        /*protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            // スプライトのみ描画。focus/hover 背景は出さない (renderWidget を super 無しで全上書き
            // しているのでバニラ AbstractWidget の背景も元々描かれない)。
        *///?}
            final int ix = getX() + (width - spriteSize) / 2;
            final int iy = getY() + (height - spriteSize) / 2;
            if (transportSprite != null) {
                //? if >=1.21.2 {
                final Identifier sprite = Identifier.fromNamespaceAndPath(
                        MusicDiscMaker.MODID, SPRITE_ROOT + transportSprite);
                g.blit(RenderPipelines.GUI_TEXTURED, sprite, ix, iy, 0.0F, 0.0F,
                        spriteSize, spriteSize, spriteSize, spriteSize);
                //?} elif >=1.21 {
                /*final ResourceLocation sprite = ResourceLocation.fromNamespaceAndPath(
                        MusicDiscMaker.MODID, SPRITE_ROOT + transportSprite);
                g.blit(sprite, ix, iy, 0.0F, 0.0F,
                        spriteSize, spriteSize, spriteSize, spriteSize);
                *///?} else {
                /*final ResourceLocation sprite = new ResourceLocation(
                        MusicDiscMaker.MODID, SPRITE_ROOT + transportSprite);
                g.blit(sprite, ix, iy, 0.0F, 0.0F,
                        spriteSize, spriteSize, spriteSize, spriteSize);
                *///?}
                return;
            }
            //? if >=1.21.2 {
            g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, ix, iy, (float) u, (float) v,
                    spriteSize, spriteSize, 256, 256);
            //?} else {
            /*g.blit(TEXTURE, ix, iy, (float) u, (float) v, spriteSize, spriteSize, 256, 256);
            *///?}
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
            if (width > 0) scrubFraction = Mth.clamp((mouseX - getX()) / (double) width, 0.0, 1.0);
        }

        @Override
        //? if >=26.1 {
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        //?} else {
        /*protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        *///?}
            final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
            final String elapsedText = formatMs(be.currentElapsedMs());
            final int rightWidth = be.isLiveStream()
                    ? font.width(Component.translatable("tooltip.music_disc_maker.live"))
                    : font.width(formatMs(be.trackDurationMs()));
            final GoldenJukeboxTransportLayout.SeekBounds bounds =
                    GoldenJukeboxTransportLayout.seekBounds(font.width(elapsedText), rightWidth);
            setX(leftPos + bounds.x());
            this.width = bounds.width();
            if (width == 0) return;
            final int gx = getX();
            final int gw = width;
            final int gy = getY() + (height - 6) / 2;
            control(g, gx - 1, gy - 1, 32, 32, 1, 8);
            control(g, gx, gy - 1, 33, 32, gw, 8);
            control(g, gx + gw, gy - 1, 255, 32, 1, 8);
            final boolean live = be.isLiveStream();
            final double f = live ? 1.0 : (scrubbing ? scrubFraction : displayFraction(be));
            final int fillW = (int) Math.round(f * gw);
            control(g, gx, gy, 32, live ? 50 : 42, fillW, 6);
            if (be.isSeekable()) {
                final int kx = gx + fillW;
                control(g, kx - 2, gy - 3, 20, 32, 5, 12);
            }
        }

        @Override
        //? if >=1.21.2 {
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() == 0 && this.active && this.visible
                    && isMouseOver(event.x(), event.y())
        //?} else {
        /*public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && this.active && this.visible && isMouseOver(mouseX, mouseY)
        *///?}
                    && menu.getBlockEntity().isSeekable()) {
                scrubbing = true;
                //? if >=1.21.2 {
                setFromMouse(event.x());
                //?} else {
                /*setFromMouse(mouseX);
                *///?}
                return true;
            }
            return false;
        }

        @Override
        //? if >=1.21.2 {
        public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        //?} else {
        /*public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        *///?}
            if (scrubbing) {
                //? if >=1.21.2 {
                setFromMouse(event.x());
                //?} else {
                /*setFromMouse(mouseX);
                *///?}
                return true;
            }
            return false;
        }

        @Override
        //? if >=1.21.2 {
        public boolean mouseReleased(MouseButtonEvent event) {
            if (scrubbing && event.button() == 0) {
        //?} else {
        /*public boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (scrubbing && button == 0) {
        *///?}
                scrubbing = false;
                final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
                final long offset = Math.round(scrubFraction * be.trackDurationMs());
                if (!com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler.isProtocolMismatch()) {
                    Services.NETWORK.sendToServer(new SeekJukeboxPayload(be.getBlockPos(), offset));
                }
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

