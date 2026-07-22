package com.kuronami.musicdiscmaker.client;

import org.lwjgl.glfw.GLFW;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.network.ResolveUrlPayload;
import com.kuronami.musicdiscmaker.platform.Services;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * ボタンレス生成 GUI: URL を貼って空ディスクを入れると自動で右に custom disc ができる。
 * PASTE = クリップボードを URL 欄へ、DELETE = URL 欄をクリア。生成自体にボタンは無い。
 */
public class MusicDiscMakerScreen extends AbstractContainerScreen<MusicDiscMakerMenu> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/gui/music_disc_maker.png");
    private static final int TEXT = 0x404040;
    private static final int ERROR = 0xCC3333;

    private EditBox urlField;
    private boolean urlWasFocused;
    private String lastSentUrl = "";

    public MusicDiscMakerScreen(MusicDiscMakerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 200;
        this.imageHeight = 166;
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
        this.urlField.setHint(Component.translatable("gui.music_disc_maker.url_placeholder"));
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
        Services.NETWORK.sendToServer(new ResolveUrlPayload(menu.getBlockEntity().getBlockPos(), url));
    }

    @Override
    public void onClose() {
        commitUrl(); // 入力したまま閉じた URL を取りこぼさない
        super.onClose();
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.blit(TEXTURE, leftPos, topPos, 0.0F, 0.0F, imageWidth, imageHeight, 256, 256);
    }

    // 取得中スピナー: 8 分の 1 回転を 100ms ごとに進める点棒。
    private static final String[] SPINNER = {"|", "/", "-", "\\"};

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY); // title + Inventory ラベル (矢印はテクスチャ側)
        // 出力スロット右の空き領域に右寄せ (パネル幅 200 をはみ出さない)
        if (menu.getBlockEntity().isResolving()) {
            final String spin = SPINNER[(int) ((System.currentTimeMillis() / 120L) % SPINNER.length)];
            final Component fetching = Component.translatable("gui.music_disc_maker.fetching")
                    .copy().append(" " + spin);
            g.drawString(font, fetching, imageWidth - 8 - font.width(fetching), 51, TEXT, false);
        } else if (menu.getBlockEntity().isResolveFailed()) {
            final Component failed = failedMessage(menu.getBlockEntity().getFailureReason());
            g.drawString(font, failed, imageWidth - 8 - font.width(failed), 51, ERROR, false);
        }
    }

    /** 失敗理由に対応する翻訳キーの短いメッセージ。未知は汎用の「取得失敗」。 */
    private static Component failedMessage(FailureReason reason) {
        final String key = switch (reason == null ? FailureReason.UNKNOWN : reason) {
            case UNSUPPORTED_URL -> "gui.music_disc_maker.failed.unsupported";
            case PRIVATE_OR_REMOVED -> "gui.music_disc_maker.failed.private";
            case REGION_LOCKED -> "gui.music_disc_maker.failed.region";
            case AGE_RESTRICTED -> "gui.music_disc_maker.failed.age";
            case CONNECTION_FAILED -> "gui.music_disc_maker.failed.connection";
            case BLOCKED_URL -> "gui.music_disc_maker.failed.blocked";
            case BOT_CHECK -> "gui.music_disc_maker.failed.botcheck";
            default -> "gui.music_disc_maker.failed";
        };
        return Component.translatable(key);
    }

    /**
     * 失敗ラベルにホバーした時の補足ツールチップ。今は bot 判定 (接続失敗と紛らわしい) だけ、
     * 「接続の問題ではない」旨を案内する。他の理由はラベルだけで自明なので null。
     */
    private static Component failedTooltip(FailureReason reason) {
        if (reason == FailureReason.BOT_CHECK) {
            return Component.translatable("gui.music_disc_maker.failed.botcheck.tip");
        }
        return null;
    }

    /** 失敗ラベルの上にマウスがあれば補足ツールチップを描く (screen 座標で判定)。 */
    private void renderFailedTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (!menu.getBlockEntity().isResolveFailed()) {
            return;
        }
        final FailureReason reason = menu.getBlockEntity().getFailureReason();
        final Component tip = failedTooltip(reason);
        if (tip == null) {
            return;
        }
        final Component failed = failedMessage(reason);
        final int right = leftPos + imageWidth - 8;
        final int left = right - font.width(failed);
        final int top = topPos + 51;
        if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= top + font.lineHeight) {
            g.renderTooltip(font, font.split(tip, 200), mouseX, mouseY);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.onClose();
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && urlField.isFocused()) {
            commitUrl();
            urlField.setFocused(false);
            return true;
        }
        if (this.urlField.keyPressed(keyCode, scanCode, modifiers) || this.urlField.canConsumeInput()) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.urlField.canConsumeInput()) {
            return this.urlField.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
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

        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
        renderFailedTooltip(g, mouseX, mouseY);
    }
}
