package com.kuronami.musicdiscmaker.event;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * NeoForge: 携帯ブームボックスの走査を server tick に繋ぐ。
 *
 * <p>{@code Post} (tick の後半) を使うのは、設置してある機体の打刻を BlockEntity の ticker が
 * 行うため。{@code Pre} で走査すると、その tick の打刻を見ずに掃除してしまう。
 *
 * <p>neoforge ブランチに 1.20.1 のノードは無いので、このクラスは帯で切っていない
 * (common 側の {@link BoomboxPlayback} は {@code >=1.21} で切ってある)。
 */
@EventBusSubscriber(modid = MusicDiscMaker.MODID)
public final class NeoForgeBoomboxEvents {

    private NeoForgeBoomboxEvents() {
    }

    /**
     * 毎 server tick。
     *
     * @param event tick イベント
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        BoomboxPlayback.tick(event.getServer());
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
