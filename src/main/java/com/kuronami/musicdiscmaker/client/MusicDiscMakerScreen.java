package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.client.jacket.JacketCache;
import com.kuronami.musicdiscmaker.client.jacket.JacketUrls;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.network.ResolveUrlPayload;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

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
import net.minecraft.world.item.ItemStack;
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

    // 取得中スピナー: 4 分の 1 回転を 120ms ごとに進める点棒。
    private static final String[] SPINNER = {"|", "/", "-", "\\"};

    // ジャケットプレビューの枠 (パネルローカル座標)。出力スロット右上の空き領域。
    private static final int PREVIEW_X = 166;
    private static final int PREVIEW_Y = 8;
    private static final int PREVIEW_SIZE = 26;

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY); // title + Inventory ラベル (矢印はテクスチャ側)
        renderJacketPreview(graphics);
        // 出力スロット右の空き領域に右寄せ (パネル幅 200 をはみ出さない)
        if (menu.getBlockEntity().isResolving()) {
            final String spin = SPINNER[(int) ((System.currentTimeMillis() / 120L) % SPINNER.length)];
            final Component fetching = Component.translatable("gui.music_disc_maker.fetching")
                    .copy().append(" " + spin);
            graphics.text(font, fetching, imageWidth - 8 - font.width(fetching), 51, TEXT, false);
        } else if (menu.getBlockEntity().isResolveFailed()) {
            final Component failed = failedMessage(menu.getBlockEntity().getFailureReason());
            graphics.text(font, failed, imageWidth - 8 - font.width(failed), 51, ERROR, false);
        }
    }

    /**
     * 解決結果のジャケットをプレビュー枠に描く。対象は出力スロットの custom disc、無ければ
     * 解決済みトラック。準備前・失敗時は何も描かない (fail-soft)。
     */
    private void renderJacketPreview(GuiGraphicsExtractor graphics) {
        final CustomTrackData track = previewTrack();
        if (track == null || track.isEmpty()) {
            return;
        }
        final JacketCache.Jacket jacket = JacketCache.get(JacketUrls.effectiveUrl(track));
        if (jacket == null) {
            return;
        }
        final double scale = Math.min(
                (double) PREVIEW_SIZE / jacket.width(), (double) PREVIEW_SIZE / jacket.height());
        final int w = Math.max(1, (int) Math.round(jacket.width() * scale));
        final int h = Math.max(1, (int) Math.round(jacket.height() * scale));
        // 枠内で中央寄せ + 1px の暗い縁取り。
        final int dx = PREVIEW_X + (PREVIEW_SIZE - w) / 2;
        final int dy = PREVIEW_Y + (PREVIEW_SIZE - h) / 2;
        graphics.fill(dx - 1, dy - 1, dx + w + 1, dy + h + 1, 0xFF3A3A3A);
        graphics.blit(RenderPipelines.GUI_TEXTURED, jacket.texture(), dx, dy, 0.0F, 0.0F,
                w, h, jacket.width(), jacket.height(), jacket.width(), jacket.height());
    }

    /** プレビュー対象のトラック: 出力ディスク優先、無ければ解決済みトラック。 */
    private CustomTrackData previewTrack() {
        final ItemStack output = menu.getBlockEntity().getItem(MusicDiscMakerBlockEntity.SLOT_OUTPUT);
        final CustomTrackData fromDisc = output.get(ModDataComponents.CUSTOM_TRACK.get());
        if (fromDisc != null && !fromDisc.isEmpty()) {
            return fromDisc;
        }
        if (menu.getBlockEntity().hasResolvedTrack()) {
            return menu.getBlockEntity().getResolvedTrack();
        }
        return null;
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
            default -> "gui.music_disc_maker.failed";
        };
        return Component.translatable(key);
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
