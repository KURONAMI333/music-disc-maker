package com.kuronami.musicdiscmaker.event;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge: 携帯ブームボックスの走査を server tick に繋ぐ。
 *
 * <p>{@code Phase.END} (tick の後半) を使うのは、設置してある機体の打刻を BlockEntity の ticker が
 * 行うため。{@code START} で走査すると、その tick の打刻を見ずに掃除してしまう。
 *
 * <p>forge ブランチのノードは 1.20.1 だけなので、このクラスは帯で切っていない。
 * NeoForge 側の同じ役は {@code NeoForgeBoomboxEvents}。
 */
@Mod.EventBusSubscriber(modid = MusicDiscMaker.MODID)
public final class ForgeBoomboxEvents {

    private ForgeBoomboxEvents() {
    }

    /**
     * 毎 server tick。
     *
     * @param event tick イベント
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            BoomboxPlayback.tick(event.getServer());
        }
    }

    /**
     * server 停止でセッションを破棄する (シングルプレイのワールド退出含む)。
     *
     * @param event 停止イベント
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        BoomboxPlayback.clear();
    }
}
