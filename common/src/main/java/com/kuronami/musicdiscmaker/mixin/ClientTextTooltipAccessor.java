package com.kuronami.musicdiscmaker.mixin;

import net.minecraft.client.gui.screens.inventory.tooltip.ClientTextTooltip;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** ツールチップの 1 行の文字。ジャケット画像を盤の名前の全行の後ろへ置く時に、名前の行を数える。 */
@Mixin(ClientTextTooltip.class)
public interface ClientTextTooltipAccessor {
    @Accessor("text")
    FormattedCharSequence mdm$text();
}
