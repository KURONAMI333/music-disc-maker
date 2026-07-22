package com.kuronami.musicdiscmaker;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entry (P1 scaffold)。26.2 build stack が両ローダーで通ることの最小確認用。
 * 実装 (レジストリ・payload・capability・compat) は P2/P3 で mod-049 から移植する。
 */
@Mod(MusicDiscMaker.MODID)
public class MusicDiscMakerNeoForge {

    public MusicDiscMakerNeoForge(IEventBus modEventBus, ModContainer modContainer) {
        MusicDiscMaker.LOGGER.info("Music Disc Maker (NeoForge) scaffold initialized");
    }
}
