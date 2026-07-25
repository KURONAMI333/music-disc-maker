package com.kuronami.musicdiscmaker.item;

import java.util.List;

import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.event.BoomboxCarry;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * ブームボックス。<b>設置しない純アイテムの携帯プレイヤー</b>。
 *
 * <h2>操作 (kura 裁定)</h2>
 * <ul>
 *   <li><b>右クリック = 再生/停止トグル</b>。ブロックを見ていても見ていなくても常にトグルする。
 *       ブロックを狙った右クリックの取り回しは各ローダーの相互作用フックが担う
 *       ({@code BoomboxInteraction}) — バニラの順序ではブロック側の相互作用が先に走るので、
 *       その前で横取りしないと「チェストを見ている間は止められない」になる。</li>
 *   <li><b>シフト + 右クリック = 専用の小さい設定 GUI</b> (ディスクスロット + 音量 + 指向性)。</li>
 * </ul>
 *
 * <p>ディスクの出し入れはインベントリ内でも完結する (バンドルと同じ操作): ディスクを持って
 * ブームボックスを右クリックで装填、空手で右クリックで取り出し。GUI を開かずに曲を変えられる。
 *
 * <p>再生の tick 源はこのアイテムには無い ({@code inventoryTick} を使わない)。継続の境界を
 * バニラの都合に委ねないため、server tick の定期走査が {@link BoomboxPlayback} 側にある。
 */
public class BoomboxItem extends Item {

    public BoomboxItem(Item.Properties properties) {
        super(properties);
    }

    // ── 右クリック (空中) ───────────────────────────────────────────────

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        interact(player, stack);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /**
     * 右クリックの共通入口。空中クリック ({@link #use}) と、ブロックを狙ったクリックを横取りする
     * ローダー側フックの両方がここへ入る。<b>実処理は server 側だけ</b>で、client は動作結果を
     * packet で受け取る。
     *
     * @return 何かしら反応した (= 相互作用を消費した) か。ブームボックスは常に反応する
     */
    public static boolean interact(Player player, ItemStack stack) {
        if (!(player instanceof ServerPlayer serverPlayer) || !BoomboxCarry.isBoombox(stack)) {
            return BoomboxCarry.isBoombox(stack);
        }
        if (player.isSecondaryUseActive()) {
            Services.MENU.openBoomboxMenu(serverPlayer,
                    BoomboxPlayback.identify(serverPlayer, stack));
            return true;
        }
        final boolean wasPlaying = BoomboxPlayback.isPlaying(stack);
        if (!BoomboxPlayback.toggle(serverPlayer, stack)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("music_disc_maker.boombox.no_disc"), true);
            return true;
        }
        serverPlayer.displayClientMessage(Component.translatable(
                wasPlaying ? "music_disc_maker.boombox.stopped" : "music_disc_maker.boombox.playing"), true);
        return true;
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
        tooltip.add(Component.translatable("tooltip.music_disc_maker.boombox.gui_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
