package com.kuronami.musicdiscmaker.client.jacket;

import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

/**
 * ツールチップにジャケット画像を出すためのデータ ({@link net.minecraft.world.item.Item#getTooltipImage}
 * が返す)。実描画は client の {@link JacketClientTooltip}。
 *
 * @param url  ジャケット画像の URL
 * @param name ツールチップ先頭行に出る盤の名前。折り返された名前の後ろへ画像を置くのに使う
 */
public record JacketTooltip(String url, Component name) implements TooltipComponent {
}

