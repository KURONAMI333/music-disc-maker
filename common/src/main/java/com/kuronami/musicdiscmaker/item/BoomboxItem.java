package com.kuronami.musicdiscmaker.item;

import com.kuronami.musicdiscmaker.block.BoomboxBlockEntity;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
//? if >=1.21.2 {
//?} else {
/*import net.minecraft.world.InteractionResultHolder;
*///?}
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 携帯ブームボックス。<b>{@code BlockItem} ではない</b> — 本体は持ち歩くアイテムで、
 * 置けるのはおまけ (KURONAMI333 裁定 2026-09-07「持ち歩くアイテムが本体で、置くこともできる」)。
 * 先行実装の Sophisticated Backpacks の {@code BackpackItem} と同じ形
 * (向こうも {@code BlockItem} ではなく {@code useOn} で自分で設置する)。
 *
 * <h2>操作</h2>
 * <ul>
 *   <li>右クリック → GUI。<b>シフトは使わない</b> (KURONAMI333 裁定 2026-09-07)</li>
 *   <li>シフト＋右クリック (ブロックに向けて) → 地面に設置。置いた機体を右クリックすると同じ GUI</li>
 * </ul>
 *
 * <p>バニラの処理順はブロック側の相互作用が先なので、<b>持っている間もチェストは普通に開く</b>。
 * 旧設計の「右クリック横取り」は常時トグルを要求していたための代償で、GUI へ寄せた裁定で消えた
 * (MDM_DECISIONS「未裁定 8 件のうち 3 件が解ける」)。
 *
 * <p>再生 (インベントリに在れば鳴る・複数台同時) はここにまだ無い。第 1 スライスの範囲外。
 */
public class BoomboxItem extends Item {

    public BoomboxItem(Properties properties) {
        super(properties);
    }

    @Override
    //? if >=1.21.2 {
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            final ItemStack stack = player.getItemInHand(hand);
            final BoomboxSource source = BoomboxSource.held(hand);
            if (BoomboxMenu.fitsInitialMenuSync(player.getInventory(), source)) {
                Services.MENU.openBoomboxMenu(serverPlayer, source);
            } else if (player.isSecondaryUseActive()) {
                OversizeMediaRecovery.notify(serverPlayer, OversizeMediaRecovery.recoverHeldBoombox(serverPlayer, stack));
            } else {
                serverPlayer.sendSystemMessage(Component.translatable("message.music_disc_maker.boombox.open_too_large"), true);
            }
        }
        return InteractionResult.SUCCESS;
    }
    //?} else {
        /*public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            final BoomboxSource source = BoomboxSource.held(hand);
            if (BoomboxMenu.fitsInitialMenuSync(player.getInventory(), source)) {
                Services.MENU.openBoomboxMenu(serverPlayer, source);
            } else if (player.isSecondaryUseActive()) {
                OversizeMediaRecovery.notify(serverPlayer, OversizeMediaRecovery.recoverHeldBoombox(serverPlayer, stack));
            } else {
                serverPlayer.sendSystemMessage(Component.translatable("message.music_disc_maker.boombox.open_too_large"), true);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
    *///?}

    @Override
    public InteractionResult useOn(UseOnContext context) {
        final Player player = context.getPlayer();
        // 通常の右クリックは設置しない。PASS で use() へ渡して GUI を開かせる。
        if (player == null || !player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        return place(context);
    }

    /**
     * {@code BlockItem#place} 相当を自前で行う。{@code BlockItem} を継承していないので、
     * 置ける場所の判定・BlockState の決定・設置音・スタックの消費をここで書く。
     *
     * <p>設置したブロックの BlockEntity に<b>手に持っていたスタックそのもの</b>を預ける。
     * 中身 (ディスク) は component ごとそこに残るので、撤去すると同じ機体が返る。creative は
     * 設置側へ元の再生を移し、手元に残るコピーを現在位置で一時停止した別機体へ分離する。
     */
    private InteractionResult place(UseOnContext context) {
        final Level level = context.getLevel();
        final BlockPlaceContext placement = new BlockPlaceContext(context);
        if (!placement.canPlace()) {
            return InteractionResult.FAIL;
        }
        final BlockState state = ModBlocks.BOOMBOX.get().getStateForPlacement(placement);
        if (state == null) {
            return InteractionResult.FAIL;
        }
        final BlockPos pos = placement.getClickedPos();
        //? if >=1.21.2 {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        //?} else {
        /*if (level.isClientSide) {
            return InteractionResult.sidedSuccess(true);
        }
        *///?}
        if (!level.setBlock(pos, state, 3)) {
            return InteractionResult.FAIL;
        }
        final ItemStack stack = context.getItemInHand();
        if (level.getBlockEntity(pos) instanceof BoomboxBlockEntity boombox) {
            boombox.setStored(stack.copyWithCount(1));
        }
        final SoundType sound = state.getSoundType();
        level.playSound((Entity) null, pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        final Player placer = context.getPlayer();
        if (placer != null && placer.getAbilities().instabuild) {
            BoomboxPlayback.separateDuplicate(stack);
        } else {
            stack.shrink(1);
        }
        //? if >=1.21.2 {
        return InteractionResult.SUCCESS;
        //?} else {
        /*return InteractionResult.sidedSuccess(false);
        *///?}
    }
}
