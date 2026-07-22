package com.kuronami.musicdiscmaker.client.jacket;

import java.util.List;

import com.mojang.datafixers.util.Either;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;

/**
 * ジャケット画像をツールチップの末尾へ並べ替える。{@code Item#getTooltipImage} 由来の画像コンポーネントは
 * NeoForge が要素列の index 1 (アイテム名の直後) へ挿入するため、既定では曲名とアーティストの間に出る。
 * テキスト全行 (曲名・アーティスト・長さ) の下に置くため、gather 段で画像要素を末尾へ移す。
 *
 * <p>26.1.2: {@link RenderTooltipEvent.GatherComponents#getTooltipElements()} が返す
 * {@code List<Either<FormattedText, TooltipComponent>>} は可変。ジャケット ({@link JacketTooltip} =
 * {@code TooltipComponent}) を含まないツールチップには影響しない。
 */
@EventBusSubscriber(modid = MusicDiscMaker.MODID, value = Dist.CLIENT)
public final class JacketTooltipReorder {

    private JacketTooltipReorder() {
    }

    @SubscribeEvent
    public static void onGatherComponents(RenderTooltipEvent.GatherComponents event) {
        final List<Either<FormattedText, TooltipComponent>> elements = event.getTooltipElements();
        final int size = elements.size();
        for (int i = 0; i < size - 1; i++) { // 既に末尾なら触らない
            final Either<FormattedText, TooltipComponent> el = elements.get(i);
            if (el.right().filter(c -> c instanceof JacketTooltip).isPresent()) {
                elements.add(elements.remove(i)); // 末尾へ移動
                return;
            }
        }
    }
}
