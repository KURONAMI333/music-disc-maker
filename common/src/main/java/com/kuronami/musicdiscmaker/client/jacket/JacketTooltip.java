package com.kuronami.musicdiscmaker.client.jacket;

import net.minecraft.world.inventory.tooltip.TooltipComponent;

/**
 * ツールチップにジャケット画像を出すためのデータ ({@link net.minecraft.world.item.Item#getTooltipImage}
 * が返す)。実描画は client の {@link JacketClientTooltip}。ここには画像 URL だけを持つ。
 */
public record JacketTooltip(String url) implements TooltipComponent {
}
