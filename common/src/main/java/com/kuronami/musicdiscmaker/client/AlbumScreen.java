package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.menu.AlbumMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
//? if >=1.21.2 {
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}

/**
 * Albumの収納操作面。既存9枠に合うバニラの一段コンテナ描画を使う。
 * Album自体の外形や最終容量を決めるものではなく、背景はリソースパックから差し替えられる。
 */
public class AlbumScreen extends AbstractContainerScreen<AlbumMenu> {
    //? if >=1.21.2 {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    //?} elif >=1.21 {
    /*private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");
    *///?} else {
    /*private static final ResourceLocation BACKGROUND = new ResourceLocation("minecraft", "textures/gui/container/generic_54.png");
    *///?}

    private boolean openedSoundPlayed;
    private boolean closedSoundPlayed;

    public AlbumScreen(AlbumMenu menu, Inventory inventory, Component title) {
        //? if >=26.1 {
        super(menu, inventory, title, 176, 132);
        //?} else {
        /*super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 132;
        *///?}
        this.inventoryLabelY = 38;
    }

    @Override
    protected void init() {
        super.init();
        // resize は同じ Screen に再度 init を掛けるため、初回だけに限定する。
        if (!openedSoundPlayed) {
            openedSoundPlayed = true;
            playBinderSound(SoundEvents.BUNDLE_INSERT);
        }
    }

    @Override
    public void removed() {
        // Esc・インベントリキー・サーバーによる終了を同じ経路で扱う。resize はここを通らない。
        if (openedSoundPlayed && !closedSoundPlayed) {
            closedSoundPlayed = true;
            playBinderSound(SoundEvents.BOOK_PUT);
        }
        super.removed();
    }

    /** 開く時は革袋、閉じる時は本を置く短いバニラ音を使う。 */
    private static void playBinderSound(SoundEvent sound) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, 1.0F, 0.6F));
    }

    //? if >=26.1 {
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, leftPos, topPos, 0.0F, 0.0F, 176, 35, 256, 256);
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, leftPos, topPos + 35, 0.0F, 126.0F, 176, 96, 256, 256);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.text(font, visibleTitle(), titleLabelX, titleLabelY, 0xFF404040, false);
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFF404040, false);
    }
    //?} elif >=1.21.2 {
    /*@Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, leftPos, topPos, 0.0F, 0.0F, 176, 35, 256, 256);
        graphics.blit(RenderPipelines.GUI_TEXTURED, BACKGROUND, leftPos, topPos + 35, 0.0F, 126.0F, 176, 96, 256, 256);
    }
    *///?} else {
    /*@Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(BACKGROUND, leftPos, topPos, 0.0F, 0.0F, 176, 35, 256, 256);
        graphics.blit(BACKGROUND, leftPos, topPos + 35, 0.0F, 126.0F, 176, 96, 256, 256);
    }
    *///?}

    //? if <26.1 {
    /*@Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, visibleTitle(), titleLabelX, titleLabelY, 0xFF404040, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xFF404040, false);
    }
    *///?}

    private String visibleTitle() {
        final String text = title.getString();
        final int available = imageWidth - titleLabelX - 8;
        return font.width(text) <= available ? text : font.plainSubstrByWidth(text, available - font.width("…")) + "…";
    }
}
