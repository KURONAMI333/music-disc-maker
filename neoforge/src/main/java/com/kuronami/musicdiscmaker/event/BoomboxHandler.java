package com.kuronami.musicdiscmaker.event;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.item.BoomboxItem;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge 側のブームボックス配線: ①ブロックを狙った右クリックの横取り ②再生走査の tick 源。
 *
 * <h2>なぜブロック相互作用より前で横取りするのか</h2>
 * kura 裁定は「右クリック = 再生/停止トグル。<b>ブロック非対象/対象を問わず常に</b>」。バニラの
 * 順序では非シフトの右クリックはブロック側の相互作用 ({@code BlockState#useItemOn}) が先に走るので、
 * 素の {@code Item#useOn} で実装するとチェスト・かまど等を見ている間はトグルできない。
 * {@link PlayerInteractEvent.RightClickBlock} は {@code ServerPlayerGameMode#useItemOn} と
 * {@code MultiPlayerGameMode#performUseItemOn} の<b>先頭</b>で発火する (21.1.227 の実ソースで確認)
 * ので、ここで消費すればブロック側へ落ちない。
 *
 * <p>両側 (client / server) で発火する。client 側は {@link BoomboxItem#interact} が即 return して
 * 消費だけを返し、実処理は server の同じ event で行う。
 *
 * <p>手の走査順は MAIN_HAND → OFF_HAND。メインに別のアイテムを持っていればそちらの event が先に
 * 来て素通りするので、「ツルハシ片手にチェストを開く」は従来どおり動く。
 */
@EventBusSubscriber(modid = MusicDiscMaker.MODID)
public final class BoomboxHandler {

    private BoomboxHandler() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        final ItemStack stack = event.getItemStack();
        if (!BoomboxCarry.isBoombox(stack)) {
            return;
        }
        BoomboxItem.interact(event.getEntity(), stack);
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        BoomboxPlayback.tick(event.getServer());
    }
}
