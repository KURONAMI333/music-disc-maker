package com.kuronami.musicdiscmaker.item;
//? if >=1.21 {

//? if >=1.21.2 {
//?} else {
/*import java.util.List;
*/
//?}
import java.util.Optional;
//? if >=1.21.2 {
import java.util.function.Consumer;
//?} else {
//?}

import com.kuronami.musicdiscmaker.client.jacket.JacketTooltip;
import com.kuronami.musicdiscmaker.client.jacket.JacketUrls;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.DiscDyeData;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
//? if >=1.21.2 {
import net.minecraft.world.item.component.TooltipDisplay;
//?} else {
//?}

/** custom music disc。tooltip に曲名/アーティスト/長さを表示する。 */
public class CustomMusicDiscItem extends Item {

    public CustomMusicDiscItem(Properties properties) {
        super(properties);
    }

    /**
     * 染色データを読む。<b>未染色なら {@code null}</b>。
     *
     * <p>v2 で作られたディスクは component を持たないので必ず {@code null} になり、
     * 呼ぶ側は確定青盤面と曲から決める自動アクセントへ落ちる。
     * 「未染色」を盤面まで既定の染料で染めた状態に読み替えないこと。
     */
    public static DiscDyeData getDye(ItemStack stack) {
        return stack.get(ModDataComponents.DISC_DYE.get());
    }

    /** 曲メタを読む。無ければ空値を返す。 */
    public static CustomTrackData getTrack(ItemStack stack) {
        final CustomTrackData track = stack.get(ModDataComponents.CUSTOM_TRACK.get());
        return track != null ? track : CustomTrackData.EMPTY;
    }

    /** 染色データを書く。{@code null} で未染色へ戻す。 */
    public static void setDye(ItemStack stack, DiscDyeData dye) {
        if (dye == null) {
            stack.remove(ModDataComponents.DISC_DYE.get());
        } else {
            stack.set(ModDataComponents.DISC_DYE.get(), dye);
        }
    }

    /** 染色されているか (= 計算した色で描く対象か)。 */
    public static boolean isDyed(ItemStack stack) {
        return getDye(stack) != null;
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
    //? if >=1.21.2 {
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
    //?} else {
/*    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    */
    //?}
        final CustomTrackData track = stack.get(ModDataComponents.CUSTOM_TRACK.get());
        if (track != null && !track.isEmpty()) {
            // カスタム名が付いている時だけ、アイテム名の下に曲名を水色+太字で補足する。
            // カスタム名が無ければアイテム名 (getName) が既に曲名なので重複表示しない。
            if (stack.has(DataComponents.CUSTOM_NAME) && !track.title().isBlank()) {
                //? if >=1.21.2 {
                tooltip.accept(Component.literal(track.title()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
                //?} else {
/*                tooltip.add(Component.literal(track.title()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
                */
                //?}
            }
            if (!track.author().isBlank()) {
                //? if >=1.21.2 {
                tooltip.accept(Component.translatable("tooltip.music_disc_maker.artist", track.author())
                //?} else {
/*                tooltip.add(Component.translatable("tooltip.music_disc_maker.artist", track.author())
                */
                //?}
                        .withStyle(ChatFormatting.GRAY));
            }
            if (track.radio()) {
                // 無限長ストリームは尺を出さず「LIVE」を出す。
                //? if >=1.21.2 {
                tooltip.accept(Component.translatable("tooltip.music_disc_maker.live")
                //?} else {
/*                tooltip.add(Component.translatable("tooltip.music_disc_maker.live")
                */
                //?}
                        .withStyle(ChatFormatting.RED));
            } else {
                //? if >=1.21.2 {
                tooltip.accept(Component.translatable("tooltip.music_disc_maker.duration", track.formattedDuration())
                        .withStyle(ChatFormatting.DARK_GRAY));
                //?} else {
/*                tooltip.add(Component.translatable("tooltip.music_disc_maker.duration", track.formattedDuration())
                        .withStyle(ChatFormatting.DARK_GRAY));
                */
                //?}
            }
            // 配信元 (YouTube 等) は尺の有無に関係なく出す。名前を引けない URL では 1 行も足さない。
            final String source = track.sourceName();
            if (!source.isEmpty()) {
                //? if >=1.21.2 {
                tooltip.accept(Component.translatable("tooltip.music_disc_maker.source", source)
                        .withStyle(ChatFormatting.DARK_GRAY));
                //?} else {
/*                tooltip.add(Component.translatable("tooltip.music_disc_maker.source", source)
                        .withStyle(ChatFormatting.DARK_GRAY));
                */
                //?}
            }
        } else {
            //? if >=1.21.2 {
            tooltip.accept(Component.translatable("tooltip.music_disc_maker.empty_disc")
            //?} else {
/*            tooltip.add(Component.translatable("tooltip.music_disc_maker.empty_disc")
            */
            //?}
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        //? if >=1.21.2 {
        super.appendHoverText(stack, context, display, tooltip, flag);
        //?} else {
/*        super.appendHoverText(stack, context, tooltip, flag);
        */
        //?}
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

//?}
