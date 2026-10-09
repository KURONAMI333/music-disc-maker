package com.kuronami.musicdiscmaker.client;

import java.util.ArrayList;
import java.util.List;

import com.kuronami.musicdiscmaker.client.jacket.JacketClientTooltip;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;

/**
 * custom music disc のツールチップの折り返し。
 *
 * <p>長い曲名 (全角 30 文字超など) でツールチップが画面幅まで横長にならないよう、各行を
 * {@link #MAX_WIDTH} で複数の行へ分ける ({@code MixinItemStack})。バニラはジャケット画像を
 * 常に 2 行目に差し込むので、盤の名前 (先頭行) が折り返されると画像が名前の 1 行目と 2 行目の間に
 * 挟まる。{@link #jacketAfterName} で画像を名前の全行の後ろへ移す ({@code MixinTooltipJacketOrder})。
 */
public final class DiscTooltipLines {

    /** 折り返し最大幅 (px)。バニラのツールチップ折り返し慣行に合わせた目安。 */
    public static final int MAX_WIDTH = 200;

    private DiscTooltipLines() {
    }

    /** 1 行を折り返した行。収まる行は元の Component をそのまま返す (sub-span のスタイルを保つ)。 */
    public static List<Component> wrap(Component line, Font font) {
        if (font.width(line) <= MAX_WIDTH) {
            return List.of(line);
        }
        final List<Component> out = new ArrayList<>();
        for (final FormattedText part : font.getSplitter().splitLines(line, MAX_WIDTH, line.getStyle())) {
            out.add(Component.literal(part.getString()).setStyle(line.getStyle()));
        }
        return out;
    }

    /**
     * 2 行目に差し込まれたジャケット画像を、折り返された盤の名前の後ろへ移す。
     * ジャケットの無いツールチップ、名前が 1 行に収まるツールチップはそのまま返す。
     */
    public static List<ClientTooltipComponent> jacketAfterName(List<ClientTooltipComponent> components) {
        if (components.size() < 3 || !(components.get(1) instanceof JacketClientTooltip jacket)) {
            return components;
        }
        final int nameLines = wrap(jacket.name(), Minecraft.getInstance().font).size();
        if (nameLines <= 1) {
            return components;
        }
        final List<ClientTooltipComponent> out = new ArrayList<>(components);
        out.remove(1);
        out.add(Math.min(nameLines, out.size()), jacket);
        return out;
    }
}
