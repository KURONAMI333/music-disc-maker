package com.kuronami.musicdiscmaker.mixin;

import com.kuronami.musicdiscmaker.client.render.PedestalJacketHud;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.2 {
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
@Mixin(Hud.class)
//?} elif >=26.1 {
/*import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
@Mixin(Gui.class)
*///?} elif >=1.21 {
/*import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
@Mixin(Gui.class)
*///?} else {
/*import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
@Mixin(Gui.class)
*///?}
/**
 * 照準を描いた後に、見ている台座の盤のジャケットを照準の横へ描く ({@link PedestalJacketHud})。
 * 照準のメソッドは三人称でも呼ばれるので、出すかどうかは {@link PedestalJacketHud} が決める。
 */
public abstract class MixinPedestalJacketHud {

    //? if >=26.1 {
    @Inject(method = "extractCrosshair", at = @At("TAIL"))
    private void mdm$pedestalJacket(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
    //?} elif >=1.21 {
    /*@Inject(method = "renderCrosshair", at = @At("TAIL"))
    private void mdm$pedestalJacket(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
    *///?} else {
    /*@Inject(method = "renderCrosshair", at = @At("TAIL"))
    private void mdm$pedestalJacket(GuiGraphics graphics, CallbackInfo ci) {
    *///?}
        PedestalJacketHud.render(graphics);
    }
}
