package com.kuronami.musicdiscmaker.client;

import java.util.List;

//? if >=1.21.11 {
//?} else {

/*import org.lwjgl.glfw.GLFW;
*///?}

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.LocalAudioClient;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerLayout;
import com.kuronami.musicdiscmaker.network.ResolveUrlPayload;
//? if >=1.21.11 {
//?} else {
/*import com.kuronami.musicdiscmaker.platform.Services;
*///?}

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
//? if >=1.21.11 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.RenderPipelines;
//?} else {
//?}
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
//? if >=1.21.11 {
import com.kuronami.musicdiscmaker.platform.Services;
//?} else {
//?}

import net.minecraft.client.Minecraft;

/**
 * URL と空ディスクが揃うと自動で生成する。主経路は画面のスロット配置で示す。
 */
public class MusicDiscMakerScreen extends AbstractContainerScreen<MusicDiscMakerMenu> {

    //? if >=1.21.11 {
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/gui/music_disc_maker.png");
    private static final int TEXT = 0xFF404040;
    //?} elif >=1.21 {
    /*private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/gui/music_disc_maker.png");
    private static final int TEXT = 0x404040;
    *///?} else {
    /*private static final ResourceLocation TEXTURE =
            new ResourceLocation(MusicDiscMaker.MODID, "textures/gui/music_disc_maker.png");
    private static final int TEXT = 0x404040;
    *///?}
    // 解決失敗は、主経路から外した小さな警告印とホバー詳細で示す。
    //? if >=1.21.11 {
    // 出所: 正リポ mod-047 branding/b1-legibility (design review 却下後の候補シート「配置B・色4」)。
    private static final int ERROR = 0xFFB02020;
    //?} else {
    /*// 出所: branding/b1-legibility (design review 却下後の候補シート「配置B・色4」)。
    private static final int ERROR = 0xB02020;
    *///?}

    private EditBox urlField;
    private IconButton clearButton;
    private boolean urlWasFocused;
    private String lastSentUrl = "";
    /**
     * URL 欄に出している出力の盤の曲名。{@code null} なら URL 欄として使っている。作った盤を取り出す前は
     * 欄に曲名を出し、そこで付け直せる (2026-10-10 本人要望)。
     */
    private String shownTitle;
    /** 送った曲名と、サーバーからの反映を待つ期限 (ms)。拒否された時に欄を実際の曲名へ戻す。 */
    private String pendingName;
    private long pendingUntilMs;
    /** 送った URL がサーバーに届いたと分かるまで欄を巻き戻さないための印 (送信時の出力の曲名と期限)。 */
    private String awaitingUrl;
    private String awaitingOutput;
    private long awaitingUntilMs;
    private static final long CONFIRM_WAIT_MS = 3_000L;
    private static final java.util.regex.Pattern BARE_URL =
            java.util.regex.Pattern.compile("^([a-z0-9-]+\\.)+[a-z]{2,}(:\\d+)?/\\S*$");
    /** ytsearch: / scsearch: などの検索。「Research: X」のような曲名は拾わない。 */
    private static final java.util.regex.Pattern SEARCH =
            java.util.regex.Pattern.compile("^[a-z]{2,5}search:\\S");

    public MusicDiscMakerScreen(MusicDiscMakerMenu menu, Inventory playerInventory, Component title) {
        //? if >=26.1 {
        //?} else {
        /*super(menu, playerInventory, title);
        this.imageWidth = MusicDiscMakerLayout.PANEL_WIDTH;
        *///?}
        // 持ち物ラベルはバニラ標準式 (H-94)、在庫スロットとホットバーはこの画面が元から持って
        // いる間隔のまま +16 する (MusicDiscMakerMenu 側の addSlot と必ず一致させる)。
        //? if >=26.1 {
        super(menu, playerInventory, title,
                MusicDiscMakerLayout.PANEL_WIDTH, MusicDiscMakerLayout.PANEL_HEIGHT);
        //?} else {
        /*this.imageHeight = MusicDiscMakerLayout.PANEL_HEIGHT;
        *///?}
        this.inventoryLabelX = MusicDiscMakerLayout.INVENTORY_X;
        this.inventoryLabelY = MusicDiscMakerLayout.INVENTORY_LABEL_Y;
    }

    @Override
    protected void init() {
        super.init();
        this.urlField = new EditBox(this.font, leftPos + MusicDiscMakerLayout.URL_X,
                topPos + MusicDiscMakerLayout.URL_Y, MusicDiscMakerLayout.URL_W,
                MusicDiscMakerLayout.URL_H,
                Component.translatable("gui.music_disc_maker.url_placeholder"));
        this.urlField.setMaxLength(2048);
        this.urlField.setBordered(false);
        //? if >=1.21.11 {
        // 26.1: EditBox の hint 既定色が DARK_GRAY になり濃く見えるので、従来の薄いグレーを明示する。
        this.urlField.setHint(Component.translatable("gui.music_disc_maker.url_placeholder")
                .withStyle(ChatFormatting.GRAY));
        //?} else {
        /*this.urlField.setHint(Component.translatable("gui.music_disc_maker.url_placeholder"));
        *///?}
        this.urlField.setValue(menu.getBlockEntity().getCurrentUrl());
        this.lastSentUrl = this.urlField.getValue();
        this.shownTitle = null; // 画面サイズの変更で欄を作り直した時も、次の tick で曲名を入れ直す
        // 1 文字ごとに自動コミットすると部分 URL の解決失敗が GUI で点滅するので行わない。
        // コミット契機 = Enter / フォーカス喪失 / PASTE / CLEAR / GUI を閉じる時。
        addRenderableWidget(this.urlField);
        // URL 欄の右上に小さな3ボタン。左から 貼り付け / ファイル選択(取込の確認も兼ねる) / 消去+転送取消。
        final IconButton paste = new IconButton(leftPos + MusicDiscMakerLayout.TOOL_PASTE_X,
                topPos + MusicDiscMakerLayout.TOOL_Y,
                Component.translatable("gui.music_disc_maker.paste"),
                "textures/gui/music_disc_maker/paste.png", () -> pasteFromClipboard());
        paste.setTooltip(Tooltip.create(Component.translatable("gui.music_disc_maker.paste")));
        addRenderableWidget(paste);
        final IconButton folder = new IconButton(leftPos + MusicDiscMakerLayout.TOOL_FOLDER_X,
                topPos + MusicDiscMakerLayout.TOOL_Y,
                Component.translatable("gui.music_disc_maker.local.select"),
                "textures/gui/music_disc_maker/folder.png", () -> {
                    if (LocalAudioClient.uploading()) {
                        LocalAudioClient.confirm();
                    } else {
                        leaveRename();
                        urlField.setValue(""); commitUrl();
                        LocalAudioClient.select(menu);
                    }
                });
        folder.setTooltip(Tooltip.create(Component.translatable("gui.music_disc_maker.local.select")));
        addRenderableWidget(folder);
        this.clearButton = new IconButton(leftPos + MusicDiscMakerLayout.TOOL_CLEAR_X,
                topPos + MusicDiscMakerLayout.TOOL_Y,
                Component.translatable("gui.music_disc_maker.clear"),
                "textures/gui/music_disc_maker/clear.png", () -> {
                    LocalAudioClient.cancel(menu);
                    clearUrl();
                });
        addRenderableWidget(this.clearButton);
    }

    private static boolean clipboardIsUrl() {
        final String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        return clip != null && looksLikeUrl(clip.strip());
    }

    private void pasteFromClipboard() {
        final String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (clip == null || clip.isBlank()) {
            return;
        }
        // 貼り付けは常に URL として扱う (曲名の付け直し中でも、次の盤の URL を入れる操作)。
        leaveRename();
        urlField.setValue(clip.trim());
        commitUrl();
    }

    private void clearUrl() {
        urlField.setFocused(false);
        urlWasFocused = false; // 書きかけの曲名を確定しない
        if (shownTitle != null) {
            // 取り出す前の盤がある時の × は、その盤を取りやめて空のディスクに戻す (2026-10-10 本人要望)。
            urlField.setValue(shownTitle);
            if (!com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler.isProtocolMismatch()) {
                // 出力枠の盤の今の曲名 (付け直しの反映待ちでも、反映されるまでは元の曲名のまま)。
                Services.NETWORK.sendToServer(new com.kuronami.musicdiscmaker.network.CancelDiscPayload(
                        menu.getBlockEntity().getBlockPos(), rawOutputTitle() != null ? rawOutputTitle() : shownTitle));
            }
            return;
        }
        urlField.setValue("");
        commitUrl();
    }

    /** 出力枠にある作ったばかりの盤の曲名。付け直せる盤が無ければ {@code null}。 */
    private String outputTitle() {
        // 送った URL がまだサーバーに残っている間 (次の盤の取得待ち) は URL 欄のまま。
        if (!menu.getBlockEntity().getCurrentUrl().isEmpty() || !lastSentUrl.isEmpty()
                || LocalAudioClient.uploading()) {
            return null;
        }
        return rawOutputTitle();
    }

    /** 出力枠の盤の曲名 (URL 欄の状態を問わない)。盤が無ければ {@code null}。 */
    private String rawOutputTitle() {
        final net.minecraft.world.item.ItemStack output =
                menu.getSlot(com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity.SLOT_OUTPUT).getItem();
        if (!output.is(com.kuronami.musicdiscmaker.register.ModItems.CUSTOM_MUSIC_DISC.get())) {
            return null;
        }
        final com.kuronami.musicdiscmaker.component.CustomTrackData track =
                com.kuronami.musicdiscmaker.item.CustomMusicDiscItem.getTrack(output);
        return track == null || track.isEmpty() ? null : track.title();
    }

    /** 曲名の付け直しをやめて URL 欄に戻す (欄の文字は呼び出し側が入れる)。 */
    private void leaveRename() {
        if (shownTitle != null) {
            shownTitle = null;
            urlField.setValue("");
        }
    }

    /** 欄の内容を確定する。付け直し中は曲名の変更、それ以外は URL のコミット。 */
    private void commitField() {
        if (shownTitle == null) {
            commitUrl();
            return;
        }
        String name = urlField.getValue().strip();
        if (looksLikeUrl(name)) {
            // 付け直し中の欄へ Ctrl+V などで次の盤の URL を入れた時は、曲名ではなく URL として送る。
            shownTitle = null;
            urlField.setValue(name);
            commitUrl();
            return;
        }
        if (name.isEmpty() || name.equals(shownTitle)) {
            urlField.setValue(shownTitle);
            return;
        }
        final int max = com.kuronami.musicdiscmaker.network.RenameDiscPayload.MAX_TITLE;
        if (name.length() > max) {
            // 上限を超えると送信側で例外になり、マルチでは切断される。サロゲート対は切らない。
            final int end = Character.isHighSurrogate(name.charAt(max - 1)) ? max - 1 : max;
            name = name.substring(0, end);
        }
        if (!com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler.isProtocolMismatch()) {
            // 元の曲名を添え、その間に盤が入れ替わっていたらサーバーは付け直さない。
            Services.NETWORK.sendToServer(new com.kuronami.musicdiscmaker.network.RenameDiscPayload(
                    menu.getBlockEntity().getBlockPos(), pendingName != null ? pendingName : shownTitle, name));
            urlField.setValue(name);
            pendingName = name;
            pendingUntilMs = System.currentTimeMillis() + CONFIRM_WAIT_MS;
        }
    }

    /**
     * 曲名ではなく URL として扱う入力か。スキーム付き・www. 始まり・検索 (ytsearch: 等) と、
     * スキームを省いた小文字の「ホスト名.tld/パス」(youtu.be/… など、UrlGuard が https とみなす形)。
     * 「Mr.Children/HANABI」「Vol.2/3」のような曲名は曲名のまま。
     */
    private static boolean looksLikeUrl(String value) {
        return value.contains("://") || value.startsWith("www.")
                || SEARCH.matcher(value).find()
                || BARE_URL.matcher(value).matches();
    }

    /** URL をサーバへコミット (URL + 空ディスクが揃えば server が自動生成)。同じ URL は再送しない。 */
    private void commitUrl() {
        if (com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler.isProtocolMismatch()) {
            return; // B5/C12: 版不一致で client 機能を無効化中
        }
        if (shownTitle != null) {
            return; // 欄は曲名を出している。URL として送らない
        }
        final String url = urlField.getValue().trim();
        if (url.equals(lastSentUrl)) {
            return;
        }
        LocalAudioClient.cancel(menu);
        lastSentUrl = url;
        Services.NETWORK.sendToServer(new ResolveUrlPayload(menu.getBlockEntity().getBlockPos(), url));
        if (url.isEmpty()) {
            awaitingUrl = null;
        } else {
            awaitingUrl = url;
            awaitingOutput = rawOutputTitle();
            awaitingUntilMs = System.currentTimeMillis() + CONFIRM_WAIT_MS;
        }
    }

    @Override
    public void onClose() {
        commitField(); // 入力したまま閉じた URL・曲名を取りこぼさない
        LocalAudioClient.cancel(menu);
        super.onClose();
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
        // 在庫帯はバニラの generic_54 slice を外枠 (左右3px・下3px) と右下の斜めの角だけ除いて重畳する。
        // スロット起点 (8,98) に slice 内セル (8,140) が一致する配置 (バニラと同じ幅 176)。
        VanillaPlayerInventoryBackground.draw(graphics, leftPos + 3, topPos + 84,
                3.0F, 126.0F, 170, 92);
    //?} elif >=1.21.11 {
    /*public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, leftPos, topPos,
                0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
        VanillaPlayerInventoryBackground.draw(g, leftPos + 3, topPos + 84,
                3.0F, 126.0F, 170, 92);
    *///?} else {
    /*protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        GuiTextureBlit.blit(g, TEXTURE, leftPos, topPos, 0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
        VanillaPlayerInventoryBackground.draw(g, leftPos + 3, topPos + 84,
                3.0F, 126.0F, 170, 92);
    *///?}
    }

    // 取得中スピナー: 4 分の 1 回転を 120ms ごとに進める点棒。
    private static final String[] SPINNER = {"|", "/", "-", "\\"};

    @Override
    //? if >=26.1 {
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY); // title + Inventory ラベル (矢印はテクスチャ側)
    //?} elif >=1.21.11 {
    /*protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY); // title + Inventory ラベル (矢印はテクスチャ側)
    *///?} else {
    /*protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY); // title + Inventory ラベル (矢印はテクスチャ側)
    *///?}
        // 欄に入力している間は「!」を出さない (満杯の欄の末尾のカーソルと重なるため)。
        if (menu.getBlockEntity().isResolveFailed() && !menu.getBlockEntity().isResolving() && !urlField.isFocused()) {
            // 固定文で主経路を説明しない。詳細は同じ座標のホバーで出す。
            //? if >=26.1 {
            graphics.text(font, "!", MusicDiscMakerLayout.WARNING_X, MusicDiscMakerLayout.WARNING_Y, ERROR, false);
            //?} elif >=1.21.11 {
            /*graphics.drawString(font, "!", MusicDiscMakerLayout.WARNING_X, MusicDiscMakerLayout.WARNING_Y, ERROR, false);
            *///?} else {
            /*g.drawString(font, "!", MusicDiscMakerLayout.WARNING_X, MusicDiscMakerLayout.WARNING_Y, ERROR, false);
            *///?}
        }
        // ローカル取込の状態 (無ければ URL の取得中表示) は入出力の下の帯へ。最大2行で折り返す。
        final List<FormattedCharSequence> statusLines = statusLines();
        final int statusY = statusLines.size() > 1
                ? MusicDiscMakerLayout.STATUS_TWO_LINE_Y : MusicDiscMakerLayout.STATUS_Y;
        // URL の取得中は矢印の下に中央揃え (2026-10-10 本人指示)。回る記号で幅が変わって揺れないよう、
        // 記号を除いた文の幅で位置を決める。ローカル取込の状態は従来どおり左寄せ。
        final int statusX = !LocalAudioClient.hasStatus() && menu.getBlockEntity().isResolving()
                ? MusicDiscMakerLayout.ARROW_CENTER_X
                        - font.width(Component.translatable("gui.music_disc_maker.fetching")) / 2
                : MusicDiscMakerLayout.STATUS_X;
        for (int i = 0; i < statusLines.size(); i++) {
            //? if >=26.1 {
            graphics.text(font, statusLines.get(i), statusX, statusY + i * font.lineHeight,
                    TEXT, false);
            //?} elif >=1.21.11 {
            /*graphics.drawString(font, statusLines.get(i), statusX,
                    statusY + i * font.lineHeight, TEXT, false);
            *///?} else {
            /*g.drawString(font, statusLines.get(i), statusX,
                    statusY + i * font.lineHeight, TEXT, false);
            *///?}
        }
    }

    private static final Component ELLIPSIS = Component.literal("…");

    /** 帯に出す文。ローカル取込の状態が優先で、無ければ URL の取得中表示。どちらも無ければ {@code null}。 */
    private Component statusMessage() {
        if (LocalAudioClient.hasStatus()) {
            return LocalAudioClient.status();
        }
        if (menu.getBlockEntity().isResolving()) {
            final String spin = SPINNER[(int) ((System.currentTimeMillis() / 120L) % SPINNER.length)];
            return Component.translatable("gui.music_disc_maker.fetching").copy().append(" " + spin);
        }
        return null;
    }

    private List<FormattedText> statusParts() {
        final Component status = statusMessage();
        return status == null ? List.of()
                : font.getSplitter().splitLines(status, MusicDiscMakerLayout.STATUS_MAX_W, Style.EMPTY);
    }

    /** 帯に描く行。2 行に収まらない文 (長いファイル名など) は 2 行目の末尾を「…」にし、ホバーで全文を出す。 */
    private List<FormattedCharSequence> statusLines() {
        final List<FormattedText> parts = statusParts();
        final Language language = Language.getInstance();
        if (parts.size() <= MusicDiscMakerLayout.STATUS_MAX_LINES) {
            return parts.stream().map(language::getVisualOrder).toList();
        }
        final FormattedText cut = FormattedText.composite(font.substrByWidth(parts.get(1),
                MusicDiscMakerLayout.STATUS_MAX_W - font.width(ELLIPSIS)), ELLIPSIS);
        return List.of(language.getVisualOrder(parts.get(0)), language.getVisualOrder(cut));
    }

    private boolean statusOverflows() {
        return LocalAudioClient.hasStatus() && statusParts().size() > MusicDiscMakerLayout.STATUS_MAX_LINES;
    }

    private boolean overStatus(int mouseX, int mouseY) {
        final int left = leftPos + MusicDiscMakerLayout.STATUS_X;
        final int top = topPos + MusicDiscMakerLayout.STATUS_TWO_LINE_Y;
        return mouseX >= left && mouseX < left + MusicDiscMakerLayout.STATUS_MAX_W
                && mouseY >= top && mouseY < top + font.lineHeight * MusicDiscMakerLayout.STATUS_MAX_LINES;
    }

    /**
     * 失敗理由に対応する翻訳キーの文。未知は汎用の「取得失敗」。
     *
     * <p>対応表は {@link FailureReason#guiKey()} が持つ。ここに switch を戻さないこと —
     * 画面側に置くと {@code default} が要り、理由を 1 つ増やした時に黙って汎用の文面へ落ちる
     * (実際 {@code SOURCE_REFUSED} が汎用の「取得失敗」に落ちていた)。
     */
    private Component failedMessage(FailureReason reason) {
        return Component.translatable((reason == null ? FailureReason.UNKNOWN : reason).guiKey());
    }

    //? if >=26.1 {
    /** 小さな警告印へホバーした時だけ、失敗理由を予約する。 */
    @Override
    protected void extractTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractTooltip(graphics, mouseX, mouseY);
    //?} elif >=1.21.11 {
    /*/^*
     * 小さな警告印へホバーした時だけ、失敗理由を描く。hover ツールチップのフックは
     * renderTooltip。
     ^/
    @Override
    protected void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(graphics, mouseX, mouseY);
    *///?} else {
    /*/^*
     * 小さな警告印にホバーした時の詳細。失敗原因は常設文を置かず、ここから必ず読める。
     ^/
    private Component failedTooltip(FailureReason reason) {
        return failedMessage(reason);
    }

    /^*
     * 警告印の上にマウスがあれば詳細を描く (screen 座標で判定)。
     ^/
    private void renderFailedTooltip(GuiGraphics g, int mouseX, int mouseY) {
    *///?}
        if (statusOverflows() && overStatus(mouseX, mouseY)) {
            //? if >=1.21.11 {
            graphics.setTooltipForNextFrame(font.split(LocalAudioClient.status(), 200), mouseX, mouseY);
            //?} else {
            /*g.renderTooltip(font, font.split(LocalAudioClient.status(), 200), mouseX, mouseY);
            *///?}
            return;
        }
        if (!menu.getBlockEntity().isResolveFailed() || menu.getBlockEntity().isResolving() || urlField.isFocused()) {
            return;
        }
        final FailureReason reason = menu.getBlockEntity().getFailureReason();
        final int left = leftPos + MusicDiscMakerLayout.WARNING_X;
        final int top = topPos + MusicDiscMakerLayout.WARNING_Y;
        if (mouseX >= left && mouseX < left + MusicDiscMakerLayout.WARNING_SIZE
                && mouseY >= top && mouseY < top + MusicDiscMakerLayout.WARNING_SIZE) {
            //? if >=1.21.11 {
            graphics.setTooltipForNextFrame(
                    font.split(failedMessage(reason), 200),
                    mouseX, mouseY);
            //?} else {
            /*g.renderTooltip(font, font.split(failedTooltip(reason), 200), mouseX, mouseY);
            *///?}
        }
    }

    @Override
    //? if >=1.21.11 {
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
    //?} else {
    /*public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
    *///?}
            this.onClose();
            return true;
        }
        //? if >=1.21.11 {
        if (event.isConfirmation() && urlField.isFocused()) {
        //?} else {
        /*if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && urlField.isFocused()) {
        *///?}
            commitField();
            urlField.setFocused(false);
            urlWasFocused = false; // 次の tick のフォーカス喪失で同じ内容を二重に確定しない
            return true;
        }
        // 曲名を出している欄へ URL を貼った時は、曲名に足さずに次の盤の URL として扱う。
        //? if >=1.21.11 {
        if (shownTitle != null && urlField.isFocused() && event.isPaste() && clipboardIsUrl()) {
        //?} else {
        /*if (shownTitle != null && urlField.isFocused()
                && net.minecraft.client.gui.screens.Screen.isPaste(keyCode) && clipboardIsUrl()) {
        *///?}
            urlField.setFocused(false);
            urlWasFocused = false;
            pasteFromClipboard();
            return true;
        }
        //? if >=1.21.11 {
        if (this.urlField.keyPressed(event) || this.urlField.canConsumeInput()) {
        //?} else {
        /*if (this.urlField.keyPressed(keyCode, scanCode, modifiers) || this.urlField.canConsumeInput()) {
        *///?}
            return true;
        }
        //? if >=1.21.11 {
        return super.keyPressed(event);
        //?} else {
        /*return super.keyPressed(keyCode, scanCode, modifiers);
        *///?}
    }

    @Override
    //? if >=1.21.11 {
    public boolean charTyped(CharacterEvent event) {
    //?} else {
    /*public boolean charTyped(char codePoint, int modifiers) {
    *///?}
        if (this.urlField.canConsumeInput()) {
            //? if >=1.21.11 {
            return this.urlField.charTyped(event);
            //?} else {
            /*return this.urlField.charTyped(codePoint, modifiers);
            *///?}
        }
        //? if >=1.21.11 {
        return super.charTyped(event);
        //?} else {
        /*return super.charTyped(codePoint, modifiers);
        *///?}
    }

    @Override
    //? if >=1.21.11 {
    protected void containerTick() {
        super.containerTick();
    //?} else {
    /*public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    *///?}
        // × は進行中だけ「取消」、それ以外は「消去」。状態に合わせてツールチップを替える。
        clearButton.setTooltip(Tooltip.create(Component.translatable(
                LocalAudioClient.uploading() || shownTitle != null
                        ? "gui.music_disc_maker.local.cancel" : "gui.music_disc_maker.clear")));
        final boolean focused = urlField.isFocused();
        if (urlWasFocused && !focused) {
            commitField(); // フォーカスを外した瞬間に URL (付け直し中は曲名) をコミット
        }
        urlWasFocused = focused;

        final String serverUrl = menu.getBlockEntity().getCurrentUrl();
        final long now = System.currentTimeMillis();
        if (awaitingUrl != null && (serverUrl.equals(awaitingUrl) || now > awaitingUntilMs
                || !java.util.Objects.equals(rawOutputTitle(), awaitingOutput))) {
            awaitingUrl = null; // サーバーが URL を受け取った (または盤ができた)
        }
        if (pendingName != null && now > pendingUntilMs) {
            // 付け直しが反映されなかった (拒否・盤の入れ替わり)。欄を実際の曲名へ戻す。
            pendingName = null;
            if (!focused && shownTitle != null) urlField.setValue(shownTitle);
        }
        if (!focused && awaitingUrl == null) {
            // サーバーが URL をクリアした (ディスク生成後のニュートラル化) ら、欄も空に戻す。
            // 盤の生成と URL のクリアは同じ同期で届くので、曲名を出す判断より先に済ませる。
            if (!serverUrl.equals(lastSentUrl)) {
                leaveRename();
                lastSentUrl = serverUrl; // 先に更新 → 以後の commitUrl が dedup で再送しない
                urlField.setValue(serverUrl);
            }
            // 作った盤が出力枠にある間は、欄に曲名を出して付け直せるようにする。取り出したら URL 欄へ戻す。
            final String title = outputTitle();
            if (title != null && !title.equals(shownTitle)) {
                shownTitle = title;
                pendingName = null;
                urlField.setValue(title);
                urlField.setCursorPosition(0);
                urlField.setHighlightPos(0);
            } else if (title == null && shownTitle != null) {
                leaveRename();
            }
        }
        //? if >=1.21.11 {
        //?} else {

        /*super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
        renderFailedTooltip(g, mouseX, mouseY);
        *///?}
    }

    /** URL 欄右上の小ボタン。バニラのボタンの面の中央へ16px図を1枚だけ描く。 */
    private static final class IconButton extends AbstractButton {

        private final String icon;
        private final Runnable action;

        IconButton(int x, int y, Component narration, String icon, Runnable action) {
            super(x, y, MusicDiscMakerLayout.TOOL_SIZE, MusicDiscMakerLayout.TOOL_SIZE, narration);
            this.icon = icon;
            this.action = action;
        }

        @Override
        //? if >=1.21.11 {
        public void onPress(InputWithModifiers input) {
            action.run();
        //?} else {
        /*public void onPress() {
            action.run();
        *///?}
        }

        private boolean hasVisibleKeyboardFocus() {
            // マウスで押した後も focus は残る。枠はキーボードで選んだ時だけ出す（バニラの最後の入力で判定）。
            return isFocused() && net.minecraft.client.Minecraft.getInstance().getLastInputType().isKeyboard();
        }

        @Override
        //? if >=26.1 {
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        //?} elif >=1.21.11 {
        /*protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        *///?} else {
        /*protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        *///?}
            // 面はバニラのボタンそのもの (3.0.5 の「貼り付け」「消去」と同じ見た目)。図は白に影で、
            // バニラのボタンの文字と同じ描き方 (影は図の PNG に焼いてある)。
            final boolean lit = isHovered() || hasVisibleKeyboardFocus();
            final String sprite = !active ? "widget/button_disabled" : lit ? "widget/button_highlighted" : "widget/button";
            //? if >=1.21.11 {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, Identifier.withDefaultNamespace(sprite),
                    getX(), getY(), width, height, net.minecraft.util.ARGB.white(this.alpha));
            //?} elif >=1.21.2 {
            /*graphics.blitSprite(net.minecraft.client.renderer.RenderType::guiTextured,
                    ResourceLocation.withDefaultNamespace(sprite), getX(), getY(), width, height);
            *///?} elif >=1.21 {
            /*com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            graphics.blitSprite(ResourceLocation.withDefaultNamespace(sprite), getX(), getY(), width, height);
            *///?} else {
            /*com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            graphics.blitNineSliced(WIDGETS_LOCATION, getX(), getY(), width, height, 20, 4, 200, 20, 0,
                    46 + (!active ? 0 : lit ? 2 : 1) * 20);
            *///?}
            final int iconSize = 16;
            final int iconX = getX() + (width - iconSize) / 2;
            final int iconY = getY() + (height - iconSize) / 2;
            //? if >=1.21.11 {
            graphics.blit(RenderPipelines.GUI_TEXTURED,
                    Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, icon),
                    iconX, iconY, 0.0F, 0.0F, iconSize, iconSize, iconSize, iconSize);
            //?} elif >=1.21 {
            /*GuiTextureBlit.blit(graphics, ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, icon),
                    iconX, iconY, 0.0F, 0.0F, iconSize, iconSize, iconSize, iconSize);
            *///?} else {
            /*GuiTextureBlit.blit(graphics, new ResourceLocation(MusicDiscMaker.MODID, icon),
                    iconX, iconY, 0.0F, 0.0F, iconSize, iconSize, iconSize, iconSize);
            *///?}
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}

