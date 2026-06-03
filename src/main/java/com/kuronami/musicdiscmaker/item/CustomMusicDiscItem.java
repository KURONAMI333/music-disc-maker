package com.kuronami.musicdiscmaker.item;

import java.util.List;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** custom music disc。tooltip に曲名/アーティスト/長さを表示する。 */
public class CustomMusicDiscItem extends Item {

    public CustomMusicDiscItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        final CustomTrackData track = stack.get(ModDataComponents.CUSTOM_TRACK.get());
        if (track != null && !track.isEmpty()) {
            // アイテム名はコモン(白)。曲名を水色+太字でアクセントにして「何の曲か」を最も目立たせる。
            tooltip.add(Component.literal(track.title()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
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
}
