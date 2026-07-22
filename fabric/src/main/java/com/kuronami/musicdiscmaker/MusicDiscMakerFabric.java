package com.kuronami.musicdiscmaker;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric entry (P1 scaffold)。26.2 build stack が両ローダーで通ることの最小確認用。
 * 実装 (payload 登録・受信配線・jukebox イベント・compat) は P3 で移植する。
 */
public class MusicDiscMakerFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        MusicDiscMaker.LOGGER.info("Music Disc Maker (Fabric) scaffold initialized");
    }
}
