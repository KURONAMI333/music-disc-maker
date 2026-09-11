package com.kuronami.musicdiscmaker.item;

//? if >=1.21.2 {
import java.util.function.Consumer;
//?} else {
/*import java.util.List;
*/
//?}

import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.menu.AlbumMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.platform.Services;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.register.ModDataComponents;
//?} else {
//?}

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.DyeColor;
//? if >=1.21.2 {
import net.minecraft.world.item.component.TooltipDisplay;
//?} else {
//?}

/** 完成済みの MDM ディスクを順序付きで収める手持ち Album。 */
public class AlbumItem extends Item {

    /**
     * 2026-09-09確定の製品容量9枚。保存値・通信の防御上限 ({@link AlbumContents}) とは意図的に別にする。
     */
    public static final int GUI_CAPACITY = 9;
    private static final int TOOLTIP_NAME_CODEPOINT_LIMIT = 32;

    public AlbumItem(Properties properties) {
        super(properties);
    }

    /**
     * Album を手に取った時、収納した盤を保存順で確認できるようにする。
     * 最大9盤だけを出し、旧データなどがそれを超える場合もツールチップを無制限に育てない。
     */
    @Override
    //? if >=1.21.2 {
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
                                Consumer<Component> tooltip, TooltipFlag flag) {
    //?} elif >=1.21 {
    /*public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
    *///?} else {
    /*public void appendHoverText(ItemStack stack, @org.jetbrains.annotations.Nullable net.minecraft.world.level.Level context,
                                List<Component> tooltip, TooltipFlag flag) {
    *///?}
        final AlbumContents stored = contents(stack);
        if (stored.isEmpty()) {
            //? if >=1.21.2 {
            tooltip.accept(Component.translatable("tooltip.music_disc_maker.album.empty")
            //?} else {
            /*tooltip.add(Component.translatable("tooltip.music_disc_maker.album.empty")
            */
            //?}
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            //? if >=1.21.2 {
            tooltip.accept(Component.translatable("tooltip.music_disc_maker.album.contents", stored.size())
            //?} else {
            /*tooltip.add(Component.translatable("tooltip.music_disc_maker.album.contents", stored.size())
            */
            //?}
                    .withStyle(ChatFormatting.GRAY));
            final int visible = Math.min(stored.size(), GUI_CAPACITY);
            for (int index = 0; index < visible; index++) {
                final ItemStack disc = stored.discAt(index);
                final Component line = Component.literal((index + 1) + ". ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(tooltipName(disc)).withStyle(ChatFormatting.GRAY));
                //? if >=1.21.2 {
                tooltip.accept(line);
                //?} else {
                /*tooltip.add(line);
                */
                //?}
            }
            if (stored.size() > GUI_CAPACITY) {
                //? if >=1.21.2 {
                tooltip.accept(Component.literal("…").withStyle(ChatFormatting.DARK_GRAY));
                //?} else {
                /*tooltip.add(Component.literal("…").withStyle(ChatFormatting.DARK_GRAY));
                */
                //?}
            }
        }
        //? if >=1.21.2 {
        super.appendHoverText(stack, context, display, tooltip, flag);
        //?} else {
        /*super.appendHoverText(stack, context, tooltip, flag);
        */
        //?}
    }

    /** 横に画面外まで伸びる曲名を抑え、サロゲート対も途中で切らない。 */
    private static String tooltipName(ItemStack disc) {
        final String name = disc.getHoverName().getString();
        if (name.codePointCount(0, name.length()) <= TOOLTIP_NAME_CODEPOINT_LIMIT) {
            return name;
        }
        final int end = name.offsetByCodePoints(0, TOOLTIP_NAME_CODEPOINT_LIMIT);
        return name.substring(0, end) + "…";
    }

    @Override
    //? if >=1.21.2 {
    public InteractionResult use(net.minecraft.world.level.Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            final ItemStack stack = player.getItemInHand(hand);
            final BoomboxSource source = BoomboxSource.held(hand);
            if (AlbumMenu.fitsInitialMenuSync(player.getInventory(), source)) {
                Services.MENU.openAlbumMenu(serverPlayer, source);
            } else if (player.isSecondaryUseActive()) {
                OversizeMediaRecovery.notify(serverPlayer, OversizeMediaRecovery.recoverAlbum(serverPlayer, stack));
            } else {
                serverPlayer.sendSystemMessage(Component.translatable("message.music_disc_maker.album.open_too_large"), true);
            }
        }
        return InteractionResult.SUCCESS;
    }
    //?} else {
    /*public net.minecraft.world.InteractionResultHolder<ItemStack> use(net.minecraft.world.level.Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            final BoomboxSource source = BoomboxSource.held(hand);
            if (AlbumMenu.fitsInitialMenuSync(player.getInventory(), source)) {
                Services.MENU.openAlbumMenu(serverPlayer, source);
            } else if (player.isSecondaryUseActive()) {
                OversizeMediaRecovery.notify(serverPlayer, OversizeMediaRecovery.recoverAlbum(serverPlayer, stack));
            } else {
                serverPlayer.sendSystemMessage(Component.translatable("message.music_disc_maker.album.open_too_large"), true);
            }
        }
        return net.minecraft.world.InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
    *///?}

    public static AlbumContents contents(ItemStack stack) {
        //? if >=1.21 {
        final AlbumContents contents = stack.get(ModDataComponents.ALBUM_CONTENTS.get());
        return contents == null ? AlbumContents.EMPTY : contents;
        //?} else {
        /*return AlbumContents.of(stack);
        *///?}
    }

    public static void setContents(ItemStack stack, AlbumContents contents) {
        //? if >=1.21 {
        if (contents == null || contents.isEmpty()) {
            stack.remove(ModDataComponents.ALBUM_CONTENTS.get());
        } else {
            stack.set(ModDataComponents.ALBUM_CONTENTS.get(), contents);
        }
        //?} else {
        /*AlbumContents.store(stack, contents);
        *///?}
    }

    /**
     * 外装色を読む。未染色の既存 Album は {@code null} を返す。
     * 描画側はこの値が無い場合の既定色を持ち、保存データへ既定色を書き戻さない。
     */
    public static DyeColor getColor(ItemStack stack) {
        //? if >=1.21 {
        return stack.get(ModDataComponents.ALBUM_COLOR.get());
        //?} else {
        /*final net.minecraft.nbt.CompoundTag tag = stack.getTag();
        return tag == null ? null : DyeColor.byName(tag.getString("album_color"), null);
        *///?}
    }

    /** 外装色を書く。{@code null} は未染色へ戻す。 */
    public static void setColor(ItemStack stack, DyeColor color) {
        //? if >=1.21 {
        if (color == null) {
            stack.remove(ModDataComponents.ALBUM_COLOR.get());
        } else {
            stack.set(ModDataComponents.ALBUM_COLOR.get(), color);
        }
        //?} else {
        /*if (color == null) {
            final net.minecraft.nbt.CompoundTag tag = stack.getTag();
            if (tag != null) {
                tag.remove("album_color");
            }
        } else {
            stack.getOrCreateTag().putString("album_color", color.getName());
        }
        *///?}
    }
}

