package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.network.ResolveUrlPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * ボタンレス生成 GUI: URL を貼って空ディスクを入れると自動で右に custom disc ができる。
 * PASTE = クリップボードを URL 欄へ、DELETE = URL 欄をクリア。生成自体にボタンは無い。
 */
public class MusicDiscMakerScreen extends AbstractContainerScreen<MusicDiscMakerMenu> {

    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/gui/music_disc_maker.png");
    private static final int TEXT = 0xFF404040;
    private static final int ERROR = 0xFFCC3333;

    private EditBox urlField;
    private boolean urlWasFocused;
    private String lastSentUrl = "";

    public MusicDiscMakerScreen(MusicDiscMakerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title, 200, 166);
        this.inventoryLabelX = 19;
        this.inventoryLabelY = 72;
    }

    @Override
    protected void init() {
        super.init();
        // 金床の名前入力欄 (テクスチャ側) の上に乗せる。setBordered(false) で二重枠を防ぐ。
        this.urlField = new EditBox(this.font, leftPos + 11, topPos + 25, 98, 12,
                Component.translatable("gui.music_disc_maker.url_placeholder"));
        this.urlField.setMaxLength(2048);
        this.urlField.setBordered(false);
        // 26.1: EditBox の hint 既定色が DARK_GRAY になり濃く見えるので、従来の薄いグレーを明示する。
        this.urlField.setHint(Component.translatable("gui.music_disc_maker.url_placeholder")
                .withStyle(ChatFormatting.GRAY));
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
        final String url = urlField.getValue().trim();
        if (url.equals(lastSentUrl)) {
            return;
        }
        lastSentUrl = url;
        ClientPacketDistributor.sendToServer(new ResolveUrlPayload(menu.getBlockEntity().getBlockPos(), url));
    }

    @Override
    public void onClose() {
        commitUrl(); // 入力したまま閉じた URL を取りこぼさない
        super.onClose();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, leftPos, topPos,
                0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY); // title + Inventory ラベル (矢印はテクスチャ側)
        // 出力スロット右の空き領域に右寄せ (パネル幅 200 をはみ出さない)
        if (menu.getBlockEntity().isResolving()) {
            final Component fetching = Component.translatable("gui.music_disc_maker.fetching");
            graphics.text(font, fetching, imageWidth - 8 - font.width(fetching), 51, TEXT, false);
        } else if (menu.getBlockEntity().isResolveFailed()) {
            final Component failed = Component.translatable("gui.music_disc_maker.failed");
            graphics.text(font, failed, imageWidth - 8 - font.width(failed), 51, ERROR, false);
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            this.onClose();
            return true;
        }
        if (event.isConfirmation() && urlField.isFocused()) {
            commitUrl();
            urlField.setFocused(false);
            return true;
        }
        if (this.urlField.keyPressed(event) || this.urlField.canConsumeInput()) {
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (this.urlField.canConsumeInput()) {
            return this.urlField.charTyped(event);
        }
        return super.charTyped(event);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
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
    }
}
