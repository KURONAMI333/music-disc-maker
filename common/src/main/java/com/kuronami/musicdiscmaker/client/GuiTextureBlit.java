package com.kuronami.musicdiscmaker.client;

//? if <1.21.11 {
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
//? if >=1.21.4 {
import net.minecraft.client.renderer.RenderType;
//?}

/** GUI texture blit の 1.21.4 引数追加を吸収する。 */
final class GuiTextureBlit {
    private GuiTextureBlit() { }

    static void blit(GuiGraphics graphics, ResourceLocation texture, int x, int y,
            float u, float v, int width, int height, int textureWidth, int textureHeight) {
        //? if >=1.21.4 {
        graphics.blit(RenderType::guiTextured, texture, x, y, u, v,
                width, height, textureWidth, textureHeight);
        //?} else {
        /*graphics.blit(texture, x, y, u, v,
                width, height, textureWidth, textureHeight);
        *///?}
    }
}
//?}
