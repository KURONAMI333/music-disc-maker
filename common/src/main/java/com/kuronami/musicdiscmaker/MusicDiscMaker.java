package com.kuronami.musicdiscmaker;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * loader 非依存の共通定数ホルダ。各ローダーの entry point は
 * {@code MusicDiscMakerNeoForge} / {@code MusicDiscMakerFabric}。
 */
public final class MusicDiscMaker {

    public static final String MODID = "music_disc_maker";
    public static final Logger LOGGER = LogUtils.getLogger();

    private MusicDiscMaker() {
    }
}
