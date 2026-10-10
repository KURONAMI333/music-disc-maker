package com.kuronami.musicdiscmaker.client;

import java.util.ArrayList;
import java.util.List;

import com.kuronami.musicdiscmaker.client.jacket.JacketClientTooltip;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

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
     * 名前の 1 行目の後ろに差し込まれたジャケット画像を、折り返された盤の名前の全行の後ろへ移す。
     *
     * <p>名前の行は文字で数える。NeoForge は画面に収まらない時にツールチップを自分でさらに折り返すので、
     * こちらの折り返し幅 ({@link #MAX_WIDTH}) で数えると行数がずれ、画像が名前の途中に残っていた
     * (2026-10-10 修理)。ジャケットの無いツールチップ、名前が 1 行に収まるツールチップはそのまま返す。
     */
    public static List<ClientTooltipComponent> jacketAfterName(List<ClientTooltipComponent> components) {
        int at = -1;
        for (int i = 1; i < components.size() && i <= 8; i++) {
            if (components.get(i) instanceof JacketClientTooltip) {
                at = i;
                break;
            }
        }
        if (at < 0) {
            return components;
        }
        final JacketClientTooltip jacket = (JacketClientTooltip) components.get(at);
        // 行は描画用の文字 (§ の書式記号を除いたもの) なので、名前も同じ形にして数える。
        final int name = letters(text(jacket.name().getVisualOrderText()));
        final List<ClientTooltipComponent> out = new ArrayList<>(components);
        out.remove(at);
        int seen = 0;
        int after = 0;
        while (after < out.size() && seen < name
                && out.get(after) instanceof com.kuronami.musicdiscmaker.mixin.ClientTextTooltipAccessor line) {
            seen += letters(text(line.mdm$text()));
            after++;
        }
        if (after == at) {
            return components;
        }
        out.add(Math.max(1, after), jacket);
        return out;
    }

    /** 空白を除いた文字数 (折り返しで行末の空白が落ちても数が合うように)。 */
    private static int letters(String s) {
        return (int) s.codePoints().filter(cp -> !Character.isWhitespace(cp)).count();
    }

    private static String text(FormattedCharSequence sequence) {
        final StringBuilder out = new StringBuilder();
        sequence.accept((index, style, codePoint) -> {
            out.appendCodePoint(codePoint);
            return true;
        });
        return out.toString();
    }
}
