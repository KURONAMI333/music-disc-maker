package com.kuronami.musicdiscmaker.client;

import java.util.List;

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
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

/**
 * ボタンレス生成 GUI: URL を貼って空ディスクを入れると自動で右に custom disc ができる。
 * PASTE = クリップボードを URL 欄へ、DELETE = URL 欄をクリア。生成自体にボタンは無い。
 */
public class MusicDiscMakerScreen extends AbstractContainerScreen<MusicDiscMakerMenu> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/gui/music_disc_maker.png");
    private static final int TEXT = 0x404040;
    // 失敗ラベル: 行の全幅を左寄せで使う (板なし・濃い赤 + shadow)。
    // 出所: branding/b1-legibility (kura 却下後の候補シート「配置B・色4」)。
    private static final int ERROR = 0xB02020;
    private static final int FAILED_TEXT_X = 12; // パネル左右対称の余白8 + 4
    // 失敗の文の帯。スロット枠の下端 (63) から 4px 空けて始まり、末尾 (77+8 = 84) の 3px 下から
    // 持ち物ラベルが始まる。スロット行と同じ高さには置けない — 文は行の全幅を使うので、
    // 入出力スロット (x=62 / 114) に重なる。
    private static final int FAILED_TEXT_Y = 68;
    private static final int FAILED_TEXT_W = 180; // 折り返し幅 (12 → imageWidth 200 - 右余白 8)
    /**
     * 帯に常時確保する行数。パネル高さ ({@code imageHeight}) はこれで決まるので、
     * <b>文を書き換えたら実測し直す</b>。制作機に届くのは {@link FailureReason} の 9 種だけで、
     * 出荷している 14 ロケールとも幅 180 では 2 行に収まる (実測 {@code branding/b1-final/sweep.py})。
     */
    private static final int FAILED_LINES = 2;

    private EditBox urlField;
    private boolean urlWasFocused;
    private String lastSentUrl = "";

    public MusicDiscMakerScreen(MusicDiscMakerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 200;
        // 失敗の帯が FAILED_LINES 行 (68 / 77 行目) を占めるぶん、下半分が 16px 下がる。
        // 持ち物ラベルはバニラ標準式 (H-94)、在庫スロットとホットバーはこの画面が元から持って
        // いる間隔のまま +16 する (MusicDiscMakerMenu 側の addSlot と必ず一致させる)。
        this.imageHeight = 182;
        this.inventoryLabelX = 19;
        this.inventoryLabelY = this.imageHeight - 94; // 88
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
            // 確保してある行数を超えた分は描かない (はみ出して持ち物ラベルに重ねない)。
            final List<FormattedCharSequence> lines = failedLines(menu.getBlockEntity().getFailureReason());
            for (int i = 0; i < Math.min(lines.size(), FAILED_LINES); i++) {
                g.drawString(font, lines.get(i), FAILED_TEXT_X,
                        FAILED_TEXT_Y + i * font.lineHeight, ERROR, false);
            }
        }
    }

    /** 失敗の文を帯の幅で折り返したもの。描画とホバー判定で同じものを使う。 */
    private List<FormattedCharSequence> failedLines(FailureReason reason) {
        return font.split(failedMessage(reason), FAILED_TEXT_W);
    }

    /**
     * 失敗理由に対応する翻訳キーの文。未知は汎用の「取得失敗」。
     *
     * <p>対応表は {@link FailureReason#guiKey()} が持つ。ここに switch を戻さないこと —
     * 画面側に置くと {@code default} が要り、理由を 1 つ増やした時に黙って汎用の文面へ落ちる。
     */
    private static Component failedMessage(FailureReason reason) {
        return Component.translatable((reason == null ? FailureReason.UNKNOWN : reason).guiKey());
    }

    /**
     * 失敗の文にホバーした時の補足ツールチップ。今は bot 判定だけに付ける。
     *
     * <p>帯に収まる長さの文には「これは接続の問題ではない」「送信元 IP で決まるのでシングル
     * プレイでも起きる」までは入らない。この 2 つが無いと利用者は回線を疑い続けるので、
     * ここだけ長い説明への逃げ道を残す。他の理由は帯の文で行動まで言えているので null。
     */
    private static Component failedTooltip(FailureReason reason) {
        if (reason == FailureReason.BOT_CHECK) {
            return Component.translatable("gui.music_disc_maker.failed.botcheck.tip");
        }
        return null;
    }

    /**
     * 失敗の文の上にマウスがあれば補足ツールチップを描く (screen 座標で判定)。
     *
     * <p>判定の矩形は<b>折り返した実物</b>から取る — 1 行ぶんの幅と高さで見ていると、
     * 2 行に折り返した時に下の行がホバーに反応しない (かつ 1 行目の右側の空白が反応する)。
     */
    private void renderFailedTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (!menu.getBlockEntity().isResolveFailed()) {
            return;
        }
        final FailureReason reason = menu.getBlockEntity().getFailureReason();
        final Component tip = failedTooltip(reason);
        if (tip == null) {
            return;
        }
        final List<FormattedCharSequence> lines = failedLines(reason);
        final int shown = Math.min(lines.size(), FAILED_LINES);
        int width = 0;
        for (int i = 0; i < shown; i++) {
            width = Math.max(width, font.width(lines.get(i)));
        }
        final int left = leftPos + FAILED_TEXT_X;
        final int top = topPos + FAILED_TEXT_Y;
        // 最終行だけは行送り (9) でなく文字の高さ (8) までを当たり判定にする。
        final int height = (shown - 1) * font.lineHeight + 8;
        if (mouseX >= left && mouseX <= left + width && mouseY >= top && mouseY <= top + height) {
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
