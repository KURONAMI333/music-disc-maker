package com.kuronami.musicdiscmaker.client.jacket;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;

/**
 * custom music disc のツールチップを一定幅で折り返す。曲名は disc の表示名 (先頭行) や補足行に入るため、
 * 長い曲名 (全角 30 文字超など) では既定のツールチップが画面幅レベルに横長になる。gather 段で
 * {@link RenderTooltipEvent.GatherComponents#setMaxWidth(int)} に上限を与えると、NeoForge の
 * ツールチップ描画が各テキスト行を {@code font.split} でこの幅に折り返す (スタイル保持)。
 * バニラのツールチップ折り返し慣行に近づける。custom disc 以外には触らない。
 */
@EventBusSubscriber(modid = MusicDiscMaker.MODID, value = Dist.CLIENT)
public final class DiscTooltipWidth {

    /** 折り返し最大幅 (px)。バニラのツールチップ折り返し慣行に合わせた目安。 */
    private static final int MAX_WIDTH = 200;

    private DiscTooltipWidth() {
    }

    @SubscribeEvent
    public static void onGatherComponents(RenderTooltipEvent.GatherComponents event) {
        if (event.getItemStack().getItem() instanceof CustomMusicDiscItem) {
            event.setMaxWidth(MAX_WIDTH);
        }
    }
}
