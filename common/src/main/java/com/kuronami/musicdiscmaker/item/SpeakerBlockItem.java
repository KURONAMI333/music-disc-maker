package com.kuronami.musicdiscmaker.item;

import java.util.List;

import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * スピーカーのブロックアイテム。強化版ジュークボックスをシフト右クリックすると音源として記憶し
 * ({@code SPEAKER_SOURCE} component)、そのまま設置するとリンクが成立する。
 *
 * <p>シフト + 手にアイテムがある右クリックはバニラのブロック相互作用 ({@code useItemOn} /
 * {@code useWithoutItem}) をスキップして {@code stack.useOn} まで落ちるため、金ジュークの設定 GUI に
 * 食われない (1.21.1 の {@code ServerPlayerGameMode#useItemOn} / {@code MultiPlayerGameMode#
 * performUseItemOn} を逆コンパイルで確認済み)。
 */
public class SpeakerBlockItem extends BlockItem {

    public SpeakerBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        final Player player = context.getPlayer();
        final Level level = context.getLevel();
        final BlockPos target = context.getClickedPos();
        if (player != null && player.isSecondaryUseActive()
                && level.getBlockState(target).is(ModBlocks.GOLDEN_JUKEBOX.get())) {
            if (!level.isClientSide) {
                context.getItemInHand().set(ModDataComponents.SPEAKER_SOURCE.get(),
                        GlobalPos.of(level.dimension(), target.immutable()));
                player.displayClientMessage(
                        Component.translatable("music_disc_maker.speaker.source_stored",
                                target.getX(), target.getY(), target.getZ()),
                        true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return super.useOn(context);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
            TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        final GlobalPos link = stack.get(ModDataComponents.SPEAKER_SOURCE.get());
        if (link != null) {
            tooltip.add(Component.translatable("tooltip.music_disc_maker.speaker.linked_to",
                    link.pos().getX(), link.pos().getY(), link.pos().getZ())
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.music_disc_maker.speaker.link_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
