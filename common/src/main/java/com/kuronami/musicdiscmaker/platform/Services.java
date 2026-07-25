package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.platform.services.IConfigHelper;
import com.kuronami.musicdiscmaker.platform.services.IMenuHelper;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

/**
 * ServiceLoader 経由で loader 実装を解決する SPI 入口。common コードはここから
 * plaform 固有の実装 (NeoForge / Fabric) を透過的に呼ぶ。
 */
public class Services {

    /** 実行中のプラットフォーム情報・BlockEntityType 生成の loader 抽象。 */
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    /** payload 送信の loader 抽象。 */
    public static final INetworkHelper NETWORK = load(INetworkHelper.class);

    /** extended menu の生成・open の loader 抽象。 */
    public static final IMenuHelper MENU = load(IMenuHelper.class);

    /** client 設定の loader 抽象。 */
    public static final IConfigHelper CONFIG = load(IConfigHelper.class);

    public static <T> T load(Class<T> clazz) {
        final T loadedService = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        MusicDiscMaker.LOGGER.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}
