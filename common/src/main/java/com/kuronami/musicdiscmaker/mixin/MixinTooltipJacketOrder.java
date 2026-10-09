package com.kuronami.musicdiscmaker.mixin;

import java.util.List;

import com.kuronami.musicdiscmaker.client.DiscTooltipLines;

import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
//? if >=26.1 {
import net.minecraft.client.gui.GuiGraphicsExtractor;
@Mixin(GuiGraphicsExtractor.class)
//?} else {
/*import net.minecraft.client.gui.GuiGraphics;
@Mixin(GuiGraphics.class)
*///?}
/**
 * 折り返された盤の名前の途中にジャケット画像が挟まらないよう、描く直前の部品の並びを直す
 * ({@link DiscTooltipLines#jacketAfterName})。どのローダー・どの画面のツールチップもここを通る。
 */
public abstract class MixinTooltipJacketOrder {

    //? if >=26.3 {
    /*// 26.3 は同名の private メソッドが 2 つ (7 引数・8 引数) あり、名前だけでは片方にしか入らない。
    // アイテムのツールチップは 8 引数版を通るので、両方に入れる (26.x は難読化が無く refmap も不要)。
    @ModifyVariable(method = "setTooltipForNextFrameInternal*", at = @At("HEAD"), argsOnly = true)
    *///?} elif >=1.21.6 {
    @ModifyVariable(method = "setTooltipForNextFrameInternal", at = @At("HEAD"), argsOnly = true)
    //?} else {
    /*@ModifyVariable(method = "renderTooltipInternal", at = @At("HEAD"), argsOnly = true)
    *///?}
    private List<ClientTooltipComponent> mdm$jacketAfterName(List<ClientTooltipComponent> components) {
        return DiscTooltipLines.jacketAfterName(components);
    }
}
