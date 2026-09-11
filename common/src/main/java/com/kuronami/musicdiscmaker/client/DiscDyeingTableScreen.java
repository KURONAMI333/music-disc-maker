package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.menu.DiscDyeingTableMenu;

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
//? if >=1.21.2 {
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
import net.minecraft.world.item.ItemStack;

/**
 * Disc Dyeing Table の GUI。背景テクスチャと、プレビュー窓に完成結果を 3 倍 (48x48) で描く。
 *
 * <p>座標は MDM_DECISIONS.md「染色ブロック GUI の配置が確定した」の確定表そのまま。
 * プレビューのディスクはテクスチャに焼かれておらず、ここで MC 側に描かせる。
 */
public class DiscDyeingTableScreen extends AbstractContainerScreen<DiscDyeingTableMenu> {

    //? if >=1.21.2 {
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/gui/disc_dyeing_table.png");
    //?} elif >=1.21 {
    /*private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/gui/disc_dyeing_table.png");
    *///?} else {
    /*private static final ResourceLocation TEXTURE =
            new ResourceLocation(MusicDiscMaker.MODID, "textures/gui/disc_dyeing_table.png");
    *///?}

    private static final int PANEL_W = 176;
    private static final int PANEL_H = 195;

    /** プレビューのディスクの左上 (確定表)。窓の凹み (60,16) の内側 4px。 */
    private static final int PREVIEW_X = 64;
    private static final int PREVIEW_Y = 20;
    /** 16x16 のアイテムを 48x48 にする倍率。 */
    private static final float PREVIEW_SCALE = 3.0F;

    public DiscDyeingTableScreen(DiscDyeingTableMenu menu, Inventory playerInventory, Component title) {
        //? if >=26.1 {
        super(menu, playerInventory, title, PANEL_W, PANEL_H);
        //?} else {
        /*super(menu, playerInventory, title);
        this.imageWidth = PANEL_W;
        this.imageHeight = PANEL_H;
        *///?}
        // インベントリラベルは addSlot y0 (113) の 12px 上 = バニラと同じ間隔。
        // 既定式 (imageHeight - 94 = 92) はこのパネル高さでは 9px 浮く。
        this.inventoryLabelY = 101;
    }

    @Override
    //? if >=26.1 {
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, leftPos, topPos,
                0.0F, 0.0F, imageWidth, 99, 256, 256);
        VanillaPlayerInventoryBackground.draw(graphics, leftPos, topPos + 113);
    //?} elif >=1.21.2 {
    /*public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 1.21.11 の AbstractContainerScreen#render は renderTooltip を呼ばないので明示的に呼ぶ。
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, leftPos, topPos,
                0.0F, 0.0F, imageWidth, 99, 256, 256);
        VanillaPlayerInventoryBackground.draw(g, leftPos, topPos + 113);
    *///?} else {
    /*public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.blit(TEXTURE, leftPos, topPos, 0.0F, 0.0F, imageWidth, 99, 256, 256);
        VanillaPlayerInventoryBackground.draw(g, leftPos, topPos + 113);
    *///?}
    }

    /**
     * プレビュー窓のディスク。パネルローカル座標で描くので確定表の値をそのまま使える。
     * ラベル層に置くのは、背景テクスチャの凹みより上・持ち上げ中のアイテムより下に来るため。
     */
    @Override
    //? if >=26.1 {
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        final ItemStack preview = menu.getPreviewStack();
        if (!preview.isEmpty()) {
            g.pose().pushMatrix();
            g.pose().translate(PREVIEW_X, PREVIEW_Y);
            g.pose().scale(PREVIEW_SCALE);
            g.item(preview, 0, 0);
            g.pose().popMatrix();
        }
    //?} elif >=1.21.2 {
    /*protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        final ItemStack preview = menu.getPreviewStack();
        if (!preview.isEmpty()) {
            g.pose().pushMatrix();
            g.pose().translate(PREVIEW_X, PREVIEW_Y);
            g.pose().scale(PREVIEW_SCALE);
            g.renderItem(preview, 0, 0);
            g.pose().popMatrix();
        }
    *///?} else {
    /*protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        final ItemStack preview = menu.getPreviewStack();
        if (!preview.isEmpty()) {
            // 1.20.1 / 1.21.1 は PoseStack。z を 3 倍にすると renderItem 内部の translate(z=150) が
            // 450 になってクリップしうるので、拡大は x/y だけに掛ける。
            g.pose().pushPose();
            g.pose().translate(PREVIEW_X, PREVIEW_Y, 0.0);
            g.pose().scale(PREVIEW_SCALE, PREVIEW_SCALE, 1.0F);
            g.renderItem(preview, 0, 0);
            g.pose().popPose();
        }
    *///?}
    }
}
