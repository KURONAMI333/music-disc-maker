package com.kuronami.musicdiscmaker.client;

//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
//? if >=1.21.11 {
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}

/** Player inventory region drawn from the same resource and UVs as vanilla ContainerScreen. */
final class VanillaPlayerInventoryBackground {
    //? if >=1.21.11 {
    private static final Identifier TEXTURE = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    //?} elif >=1.21 {
    /*private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/gui/container/generic_54.png");
    *///?} else {
    /*private static final ResourceLocation TEXTURE = new ResourceLocation("minecraft", "textures/gui/container/generic_54.png");
    *///?}

    private VanillaPlayerInventoryBackground() { }

    //? if >=26.1 {
    static void draw(GuiGraphicsExtractor graphics, int left, int firstSlotY) {
    //?} else {
    /*static void draw(GuiGraphics graphics, int left, int firstSlotY) {
    *///?}
        // The first slot is at source y=140; the inventory slice begins at y=126.
        //? if >=1.21.11 {
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, left, firstSlotY - 14,
                0.0F, 126.0F, 176, 96, 256, 256);
        //?} else {
        /*GuiTextureBlit.blit(graphics, TEXTURE, left, firstSlotY - 14, 0.0F, 126.0F, 176, 96, 256, 256);
        *///?}
    }

    /**
     * Slice の任意矩形だけを借りる版。slice が持つ外枠 (左右3px・下3px) を外した
     * 内側だけを重畳したい画面で使う。
     */
    //? if >=26.1 {
    static void draw(GuiGraphicsExtractor graphics, int x, int y, float u, float v, int w, int h) {
    //?} else {
    /*static void draw(GuiGraphics graphics, int x, int y, float u, float v, int w, int h) {
    *///?}
        //? if >=1.21.11 {
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, u, v, w, h, 256, 256);
        //?} else {
        /*GuiTextureBlit.blit(graphics, TEXTURE, x, y, u, v, w, h, 256, 256);
        *///?}
    }
}
