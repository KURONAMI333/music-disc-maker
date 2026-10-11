package com.kuronami.musicdiscmaker.item;

//? if >=1.21.11 {
import java.util.function.Consumer;
//?} else {
/*import java.util.List;
*/
//?}

import com.kuronami.musicdiscmaker.component.AlbumContents;
import com.kuronami.musicdiscmaker.menu.AlbumMenu;
import com.kuronami.musicdiscmaker.menu.AlbumMenuLayout;
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
//? if >=1.21.11 {
import net.minecraft.world.item.component.TooltipDisplay;
//?} else {
//?}

/** 完成済みの MDM ディスクを順序付きで収める手持ち Album。Golden Album は段数だけが違う同じ型。 */
public class AlbumItem extends Item {

    /**
     * Album の製品GUI容量。保存値・通信の防御上限 ({@link AlbumContents}) とは意図的に別にする。
     * Golden Album の容量は {@link #capacity()}。
     */
    public static final int GUI_CAPACITY = AlbumMenuLayout.CAPACITY;
    private static final int TOOLTIP_NAME_CODEPOINT_LIMIT = 32;
    /** 54 曲を全部並べると画面から溢れるので、ツールチップは Album と同じ行数で止める。 */
    private static final int TOOLTIP_TRACK_LINES = AlbumMenuLayout.CAPACITY;

    private final int rows;

    public AlbumItem(Properties properties) {
        this(properties, AlbumMenuLayout.ROWS);
    }

    public AlbumItem(Properties properties, int rows) {
        super(properties);
        this.rows = rows;
        AlbumMenuLayout.capacity(rows);
    }

    /** 画面の段数。Album は 2、Golden Album は 6。 */
    public int rows() {
        return rows;
    }

    /** 画面に並ぶ枠の数。 */
    public int capacity() {
        return AlbumMenuLayout.capacity(rows);
    }

    /** Album と Golden Album のどちらか。 */
    public static boolean isAlbum(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof AlbumItem;
    }

    /** Album でなければ 0。 */
    public static int capacity(ItemStack stack) {
        return isAlbum(stack) ? ((AlbumItem) stack.getItem()).capacity() : 0;
    }

    /**
     * Album を手に取った時、収納した盤を保存順で確認できるようにする。
     * 先頭の {@link #TOOLTIP_TRACK_LINES} 枚だけを出し、残りは枚数で示す。ツールチップを無制限に育てない。
     */
    @Override
    //? if >=1.21.11 {
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
            //? if >=1.21.11 {
            tooltip.accept(Component.translatable("tooltip.music_disc_maker.album.empty")
            //?} else {
            /*tooltip.add(Component.translatable("tooltip.music_disc_maker.album.empty")
            */
            //?}
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            //? if >=1.21.11 {
            tooltip.accept(Component.translatable("tooltip.music_disc_maker.album.contents", stored.size())
            //?} else {
            /*tooltip.add(Component.translatable("tooltip.music_disc_maker.album.contents", stored.size())
            */
            //?}
                    .withStyle(ChatFormatting.GRAY));
            final int visible = Math.min(stored.size(), TOOLTIP_TRACK_LINES);
            for (int index = 0; index < visible; index++) {
                final ItemStack disc = stored.discAt(index);
                final Component line = Component.literal((index + 1) + ". ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal(tooltipName(discName(disc, context))).withStyle(ChatFormatting.GRAY));
                //? if >=1.21.11 {
                tooltip.accept(line);
                //?} else {
                /*tooltip.add(line);
                */
                //?}
            }
            if (stored.size() > visible) {
                final Component more = Component.literal("… +" + (stored.size() - visible)).withStyle(ChatFormatting.DARK_GRAY);
                //? if >=1.21.11 {
                tooltip.accept(more);
                //?} else {
                /*tooltip.add(more);
                */
                //?}
            }
        }
        //? if >=1.21.11 {
        super.appendHoverText(stack, context, display, tooltip, flag);
        //?} else {
        /*super.appendHoverText(stack, context, tooltip, flag);
        */
        //?}
    }

    /**
     * 盤の表示名。バニラ・他 MOD の盤は名前がどれも「Music Disc」で見分けがつかないので、曲名
     * (例「C418 - cat」) を出す。MDM の盤は名前 (曲名・金床での名前) のまま。
     */
    //? if >=1.21 {
    private static String discName(ItemStack disc, TooltipContext context) {
    //?} else {
    /*private static String discName(ItemStack disc, @org.jetbrains.annotations.Nullable net.minecraft.world.level.Level context) {
    *///?}
        if (!disc.is(com.kuronami.musicdiscmaker.register.ModItems.CUSTOM_MUSIC_DISC.get())) {
            //? if >=26.1 {
            final var song = net.minecraft.world.item.JukeboxSong.fromStack(disc);
            if (song.isPresent()) return song.get().value().description().getString();
            //?} elif >=1.21 {
            /*if (context.registries() != null) {
                final var song = net.minecraft.world.item.JukeboxSong.fromStack(context.registries(), disc);
                if (song.isPresent()) return song.get().value().description().getString();
            }
            *///?} else {
            /*if (disc.getItem() instanceof net.minecraft.world.item.RecordItem record) {
                return record.getDisplayName().getString();
            }
            *///?}
        }
        return disc.getHoverName().getString();
    }

    /** 横に画面外まで伸びる曲名を抑え、サロゲート対も途中で切らない。 */
    private static String tooltipName(String name) {
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
            final var syncBudget = AlbumMenu.inspectInitialMenuSync(player.getInventory(), source);
            if (syncBudget.fits()) {
                Services.MENU.openAlbumMenu(serverPlayer, source);
            } else if (player.isSecondaryUseActive()) {
                OversizeMediaRecovery.notify(serverPlayer, OversizeMediaRecovery.recoverAlbum(serverPlayer, stack));
            } else {
                serverPlayer.sendSystemMessage(Component.translatable(syncBudget.rejectionKey("message.music_disc_maker.album.open_too_large")), true);
            }
        }
        return InteractionResult.SUCCESS;
    }
    //?} else {
    /*public net.minecraft.world.InteractionResultHolder<ItemStack> use(net.minecraft.world.level.Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            final BoomboxSource source = BoomboxSource.held(hand);
            final var syncBudget = AlbumMenu.inspectInitialMenuSync(player.getInventory(), source);
            if (syncBudget.fits()) {
                Services.MENU.openAlbumMenu(serverPlayer, source);
            } else if (player.isSecondaryUseActive()) {
                OversizeMediaRecovery.notify(serverPlayer, OversizeMediaRecovery.recoverAlbum(serverPlayer, stack));
            } else {
                serverPlayer.sendSystemMessage(Component.translatable(syncBudget.rejectionKey("message.music_disc_maker.album.open_too_large")), true);
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

