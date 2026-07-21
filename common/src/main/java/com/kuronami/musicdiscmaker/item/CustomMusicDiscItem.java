package com.kuronami.musicdiscmaker.item;

import java.util.List;
import java.util.Optional;

import com.kuronami.musicdiscmaker.client.jacket.JacketTooltip;
import com.kuronami.musicdiscmaker.client.jacket.JacketUrls;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** custom music disc。tooltip に曲名/アーティスト/長さ/ジャケットを表示する。 */
public class CustomMusicDiscItem extends Item {

    public CustomMusicDiscItem(Properties properties) {
        super(properties);
    }

    /**
     * 表示名: 金床で付けたカスタム名 (あれば) > 自動取得の曲名 > 既定のアイテム名。
     * カスタム名は vanilla の {@link ItemStack#getHoverName()} が CUSTOM_NAME で上書きするので、
     * ここでは「曲名 > 既定名」だけを担う。
     */
    @Override
    public Component getName(ItemStack stack) {
        final CustomTrackData track = stack.get(ModDataComponents.CUSTOM_TRACK.get());
        if (track != null && !track.isEmpty() && !track.title().isBlank()) {
            return Component.literal(track.title());
        }
        return super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        final CustomTrackData track = stack.get(ModDataComponents.CUSTOM_TRACK.get());
        if (track != null && !track.isEmpty()) {
            // カスタム名が付いている時だけ、アイテム名の下に曲名を水色+太字で補足する。
            // カスタム名が無ければアイテム名 (getName) が既に曲名なので重複表示しない。
            if (stack.has(DataComponents.CUSTOM_NAME) && !track.title().isBlank()) {
                tooltip.add(Component.literal(track.title()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            }
            if (!track.author().isBlank()) {
                tooltip.add(Component.translatable("tooltip.music_disc_maker.artist", track.author())
                        .withStyle(ChatFormatting.GRAY));
            }
            tooltip.add(Component.translatable("tooltip.music_disc_maker.duration", track.formattedDuration())
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.music_disc_maker.empty_disc")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        super.appendHoverText(stack, context, tooltip, flag);
    }

    /** ツールチップにジャケット画像を差し込む (client 側で非同期 DL → 描画)。 */
    @Override
    public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
        final CustomTrackData track = stack.get(ModDataComponents.CUSTOM_TRACK.get());
        if (track == null || track.isEmpty()) {
            return Optional.empty();
        }
        final String jacketUrl = JacketUrls.effectiveUrl(track);
        if (jacketUrl.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new JacketTooltip(jacketUrl));
    }
}
