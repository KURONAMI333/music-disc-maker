package com.kuronami.musicdiscmaker.item;

import java.util.List;

import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * ブームボックスのブロックアイテム。手持ちのまま鳴らせる。
 *
 * <p>操作: <b>何も見ていない状態 (空中) でシフト + 右クリックすると再生/停止</b>。
 * ブロックに向けている時は常に設置になる — シフト右クリックを無条件にトグルへ食わせると、
 * チェスト・かまど等の相互作用ブロックの面にブームボックスを置けなくなる
 * (シフト + アイテム所持の右クリックはバニラがブロック相互作用をスキップして
 * {@code stack.useOn} に落とすので、それが唯一の設置経路になるため)。
 *
 * <p>ディスクの出し入れはインベントリ内で完結する (バンドルと同じ操作): ディスクを持って
 * ブームボックスを右クリックで装填、空手で右クリックで取り出し。設置して GUI を開いても同じことが
 * できるが、曲を変えるたびに設置・破壊を強いない。
 *
 * <p>手持ち再生の tick 源はこのアイテムの {@link #inventoryTick}。専用の server tick フックを
 * 増やさずに済み、「落とした / チェストに入れた」は tick が来なくなることで自然に止まる。
 */
public class BoomboxBlockItem extends BlockItem {

    public BoomboxBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    // ── 再生トグル (空中でシフト + 右クリック) ──────────────────────────

    // useOn は override しない = ブロックに向けた右クリックは常にバニラの設置に任せる。
    // 設置できない時は BlockItem#place が FAIL を返し、Minecraft#startUseItem がそこで
    // 打ち切る (1.21.1 逆コンパイルで確認) ので、「ブロックを見ている時にトグルへ落ちる」
    // 経路は無い。トグルは空中クリック = この use だけ。

    /** 空中でのシフト + 右クリックで再生/停止。ブロックを見ている時はここに来ない。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (!player.isSecondaryUseActive()) {
            return InteractionResultHolder.pass(stack);
        }
        toggle(level, player, stack);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private static void toggle(Level level, Player player, ItemStack stack) {
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        final boolean playing = BoomboxPlayback.isPlaying(stack);
        if (!BoomboxPlayback.toggle(serverPlayer, stack)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("music_disc_maker.boombox.no_disc"), true);
            return;
        }
        serverPlayer.displayClientMessage(Component.translatable(
                playing ? "music_disc_maker.boombox.stopped" : "music_disc_maker.boombox.playing"), true);
    }

    // ── 手持ち再生の tick 源 ────────────────────────────────────────────

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slotId, boolean isSelected) {
        super.inventoryTick(stack, level, entity, slotId, isSelected);
        if (!level.isClientSide && entity instanceof ServerPlayer player) {
            BoomboxPlayback.heartbeat(player, stack);
        }
    }

    // ── インベントリ内でのディスク装填/取り出し (バンドル方式) ──────────

    /** スロットのブームボックスに、カーソルのスタックを右クリックした時。 */
    @Override
    public boolean overrideOtherStackedOnMe(ItemStack stack, ItemStack other, Slot slot, ClickAction action,
            Player player, SlotAccess access) {
        if (action != ClickAction.SECONDARY || !slot.allowModification(player)) {
            return false;
        }
        final BoomboxContents contents = contentsOf(stack);
        if (other.isEmpty()) {
            if (!contents.hasDisc()) {
                return false;
            }
            access.set(contents.disc().copy()); // カーソルへ取り出す
            stack.set(ModDataComponents.BOOMBOX_CONTENTS.get(), contents.withDisc(ItemStack.EMPTY));
            return true;
        }
        if (contents.hasDisc() || !other.has(DataComponents.JUKEBOX_PLAYABLE)) {
            return false;
        }
        stack.set(ModDataComponents.BOOMBOX_CONTENTS.get(), contents.withDisc(other.split(1)));
        return true;
    }

    /** カーソルのブームボックスで、スロットのディスクを右クリックした時 (逆向きの同じ操作)。 */
    @Override
    public boolean overrideStackedOnOther(ItemStack stack, Slot slot, ClickAction action, Player player) {
        if (action != ClickAction.SECONDARY || !slot.allowModification(player)) {
            return false;
        }
        final BoomboxContents contents = contentsOf(stack);
        final ItemStack target = slot.getItem();
        if (target.isEmpty()) {
            if (!contents.hasDisc()) {
                return false;
            }
            slot.safeInsert(contents.disc().copy());
            stack.set(ModDataComponents.BOOMBOX_CONTENTS.get(), contents.withDisc(ItemStack.EMPTY));
            return true;
        }
        if (contents.hasDisc() || !target.has(DataComponents.JUKEBOX_PLAYABLE)) {
            return false;
        }
        stack.set(ModDataComponents.BOOMBOX_CONTENTS.get(),
                contents.withDisc(slot.safeTake(1, 1, player)));
        return true;
    }

    private static BoomboxContents contentsOf(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.BOOMBOX_CONTENTS.get(), BoomboxContents.EMPTY);
    }

    // ── tooltip ─────────────────────────────────────────────────────────

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
            TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        final BoomboxContents contents = contentsOf(stack);
        if (contents.hasDisc()) {
            final CustomTrackData track = contents.disc().get(ModDataComponents.CUSTOM_TRACK.get());
            final Component name = (track != null && !track.isEmpty() && !track.title().isBlank())
                    ? Component.literal(track.title())
                    : contents.disc().getHoverName();
            tooltip.add(Component.translatable("tooltip.music_disc_maker.boombox.loaded", name)
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.music_disc_maker.boombox.empty")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.add(Component.translatable("tooltip.music_disc_maker.boombox.hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
