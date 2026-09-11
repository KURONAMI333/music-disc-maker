package com.kuronami.musicdiscmaker.client;

//? if >=1.21.2 {
//?} else {

/*import org.lwjgl.glfw.GLFW;
*///?}

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerLayout;
import com.kuronami.musicdiscmaker.network.ResolveUrlPayload;
//? if >=1.21.2 {
//?} else {
/*import com.kuronami.musicdiscmaker.platform.Services;
*///?}

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
//? if >=1.21.2 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.RenderPipelines;
//?} else {
//?}
import net.minecraft.network.chat.Component;
//? if >=1.21.2 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.world.entity.player.Inventory;
//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.platform.Services;
//?} else {
//?}

import net.minecraft.client.Minecraft;

/**
 * URL と空ディスクが揃うと自動で生成する。主経路は画面のスロット配置で示す。
 */
public class MusicDiscMakerScreen extends AbstractContainerScreen<MusicDiscMakerMenu> {

    //? if >=1.21.2 {
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
    //? if >=1.21.2 {
    // 出所: 正リポ mod-047 branding/b1-legibility (KURONAMI333 却下後の候補シート「配置B・色4」)。
    private static final int ERROR = 0xFFB02020;
    //?} else {
    /*// 出所: branding/b1-legibility (KURONAMI333 却下後の候補シート「配置B・色4」)。
    private static final int ERROR = 0xB02020;
    *///?}
    // v2.2.4 と同じく取得中表示は右端8px、主工程と同じ高さに置く。
    private static final int FETCHING_Y = 51;
    // 失敗は常設文で主経路を塞がない小さな印とホバー詳細にする。
    private static final int WARNING_X = 112;
    private static final int WARNING_Y = 72;
    private static final int WARNING_SIZE = 8;

    private EditBox urlField;
    private boolean urlWasFocused;
    private String lastSentUrl = "";

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
        // 金床の名前入力欄と同様に、入力欄そのものを URL の置き場として見せる。
        this.urlField = new EditBox(this.font, leftPos + 11, topPos + 25, 98, 12,
                Component.translatable("gui.music_disc_maker.url_placeholder"));
        this.urlField.setMaxLength(2048);
        this.urlField.setBordered(false);
        //? if >=1.21.2 {
        // 26.1: EditBox の hint 既定色が DARK_GRAY になり濃く見えるので、従来の薄いグレーを明示する。
        this.urlField.setHint(Component.translatable("gui.music_disc_maker.url_placeholder")
                .withStyle(ChatFormatting.GRAY));
        //?} else {
        /*this.urlField.setHint(Component.translatable("gui.music_disc_maker.url_placeholder"));
        *///?}
        this.urlField.setValue(menu.getBlockEntity().getCurrentUrl());
        this.lastSentUrl = this.urlField.getValue();
        // 1 文字ごとに自動コミットすると部分 URL の解決失敗が GUI で点滅するので行わない。
        // コミット契機 = Enter / フォーカス喪失 / PASTE / CLEAR / GUI を閉じる時。
        addRenderableWidget(this.urlField);
        addRenderableWidget(Button.builder(Component.translatable("gui.music_disc_maker.paste"),
                b -> pasteFromClipboard()).bounds(leftPos + 120, topPos + 20, 36, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.music_disc_maker.clear"),
                b -> clearUrl()).bounds(leftPos + 158, topPos + 20, 36, 18).build());
    }

    private void pasteFromClipboard() {
        final String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        if (clip != null && !clip.isBlank()) {
            urlField.setValue(clip.trim());
        }
        commitUrl();
    }

    private void clearUrl() {
        urlField.setValue("");
        urlField.setFocused(false);
        commitUrl();
    }

    /** URL をサーバへコミット (URL + 空ディスクが揃えば server が自動生成)。同じ URL は再送しない。 */
    private void commitUrl() {
        if (com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler.isProtocolMismatch()) {
            return; // B5/C12: 版不一致で client 機能を無効化中
        }
        final String url = urlField.getValue().trim();
        if (url.equals(lastSentUrl)) {
            return;
        }
        lastSentUrl = url;
        Services.NETWORK.sendToServer(new ResolveUrlPayload(menu.getBlockEntity().getBlockPos(), url));
    }

    @Override
    public void onClose() {
        commitUrl(); // 入力したまま閉じた URL を取りこぼさない
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

    // 取得中スピナー: 4 分の 1 回転を 120ms ごとに進める点棒。
    private static final String[] SPINNER = {"|", "/", "-", "\\"};

    @Override
    //? if >=26.1 {
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY); // title + Inventory ラベル (矢印はテクスチャ側)
    //?} elif >=1.21.2 {
    /*protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY); // title + Inventory ラベル (矢印はテクスチャ側)
    *///?} else {
    /*protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY); // title + Inventory ラベル (矢印はテクスチャ側)
    *///?}
        // 取得中は v2.2.4 と同じ右端8px・Y=51に置く。
        if (menu.getBlockEntity().isResolving()) {
            final String spin = SPINNER[(int) ((System.currentTimeMillis() / 120L) % SPINNER.length)];
            final Component fetching = Component.translatable("gui.music_disc_maker.fetching")
                    .copy().append(" " + spin);
            //? if >=26.1 {
            graphics.text(font, fetching, imageWidth - 8 - font.width(fetching), FETCHING_Y, TEXT, false);
            //?} elif >=1.21.2 {
            /*graphics.drawString(font, fetching, imageWidth - 8 - font.width(fetching), FETCHING_Y, TEXT, false);
            *///?} else {
            /*g.drawString(font, fetching, imageWidth - 8 - font.width(fetching), FETCHING_Y, TEXT, false);
            *///?}
        } else if (menu.getBlockEntity().isResolveFailed()) {
            // 固定文で主経路を説明しない。詳細は同じ座標のホバーで出す。
            //? if >=26.1 {
            graphics.text(font, "!", WARNING_X, WARNING_Y, ERROR, false);
            //?} elif >=1.21.2 {
            /*graphics.drawString(font, "!", WARNING_X, WARNING_Y, ERROR, false);
            *///?} else {
            /*g.drawString(font, "!", WARNING_X, WARNING_Y, ERROR, false);
            *///?}
        }
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
    //?} elif >=1.21.2 {
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
        if (!menu.getBlockEntity().isResolveFailed()) {
            return;
        }
        final FailureReason reason = menu.getBlockEntity().getFailureReason();
        final int left = leftPos + WARNING_X;
        final int top = topPos + WARNING_Y;
        if (mouseX >= left && mouseX < left + WARNING_SIZE && mouseY >= top && mouseY < top + WARNING_SIZE) {
            //? if >=1.21.2 {
            graphics.setTooltipForNextFrame(
                    font.split(failedMessage(reason), 200),
                    mouseX, mouseY);
            //?} else {
            /*g.renderTooltip(font, font.split(failedTooltip(reason), 200), mouseX, mouseY);
            *///?}
        }
    }

    @Override
    //? if >=1.21.2 {
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
    //?} else {
    /*public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
    *///?}
            this.onClose();
            return true;
        }
        //? if >=1.21.2 {
        if (event.isConfirmation() && urlField.isFocused()) {
        //?} else {
        /*if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && urlField.isFocused()) {
        *///?}
            commitUrl();
            urlField.setFocused(false);
            return true;
        }
        //? if >=1.21.2 {
        if (this.urlField.keyPressed(event) || this.urlField.canConsumeInput()) {
        //?} else {
        /*if (this.urlField.keyPressed(keyCode, scanCode, modifiers) || this.urlField.canConsumeInput()) {
        *///?}
            return true;
        }
        //? if >=1.21.2 {
        return super.keyPressed(event);
        //?} else {
        /*return super.keyPressed(keyCode, scanCode, modifiers);
        *///?}
    }

    @Override
    //? if >=1.21.2 {
    public boolean charTyped(CharacterEvent event) {
    //?} else {
    /*public boolean charTyped(char codePoint, int modifiers) {
    *///?}
        if (this.urlField.canConsumeInput()) {
            //? if >=1.21.2 {
            return this.urlField.charTyped(event);
            //?} else {
            /*return this.urlField.charTyped(codePoint, modifiers);
            *///?}
        }
        //? if >=1.21.2 {
        return super.charTyped(event);
        //?} else {
        /*return super.charTyped(codePoint, modifiers);
        *///?}
    }

    @Override
    //? if >=1.21.2 {
    protected void containerTick() {
        super.containerTick();
    //?} else {
    /*public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    *///?}
        final boolean focused = urlField.isFocused();
        if (urlWasFocused && !focused) {
            commitUrl(); // フォーカスを外した瞬間に URL をコミット
        }
        urlWasFocused = focused;

        // サーバーが URL をクリアした (ディスク生成後のニュートラル化) ら、入力中でなければフィールドも空に戻す
        final String serverUrl = menu.getBlockEntity().getCurrentUrl();
        if (!focused && !serverUrl.equals(lastSentUrl)) {
            lastSentUrl = serverUrl; // 先に更新 → 以後の commitUrl が dedup で再送しない
            urlField.setValue(serverUrl);
        }
        //? if >=1.21.2 {
        //?} else {

        /*super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
        renderFailedTooltip(g, mouseX, mouseY);
        *///?}
    }
}

